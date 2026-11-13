/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of PKSPKMS.
 * Copyright (C) 2026 Paul Kenny
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.pskenny.pkspkms.luabase;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sandbox session for note-controlled Lua (base/luabase filter + order-column
 * expressions, user formulas). Each instance is a SELF-CONTAINED disposable
 * environment: globals, compile caches and user formulas all live and die with
 * the session. Construct one per render pass — instances are NOT thread-safe
 * and must never be shared across notes (a note could otherwise poison the
 * trusted helpers used by the next note).
 *
 * Environment policy is an ALLOWLIST (BUGS.md S1-S3): only the standard
 * comparison base functions ({@code type}, {@code tostring}, {@code tonumber}),
 * the {@code string}/{@code table}/{@code math} libraries and the trusted
 * helpers of {@code /luabase/functions.lua} exist. {@code PackageLib} IS
 * installed but only as install-time plumbing — stock luaj libraries resolve
 * {@code package.loaded} during their constructors — and the {@code package}
 * global is swept away immediately after: its {@code package.searchers[3]}
 * ({@code Class.forName} + {@code newInstance()} on any classpath LuaValue
 * class, which can re-arm {@code os}/{@code luajava}) and {@code searchpath}
 * become unreachable. Filesystem primitives ({@code loadfile}), {@code print},
 * a live {@code _G}, and the legacy deny list ({@code os}, {@code io},
 * {@code luajava}, {@code load}, {@code loadstring}, {@code dofile},
 * {@code require}) are all denied by the same sweep.
 */
public final class LuaBaseInterpreter {

    // Generous ceiling on note-controlled filters: the cheapest CPU-DoS guard
    // (a full watchdog is deferred; see docs/core/Security.md residual notes)
    static final int MAX_EXPRESSION_LENGTH = 4096;

    private static final String FUNCTIONS_LUA;
    static {
        try (InputStream is = LuaBaseInterpreter.class.getResourceAsStream("/luabase/functions.lua")) {
            if (is == null) {
                throw new IllegalStateException("Could not find /luabase/functions.lua");
            }
            FUNCTIONS_LUA = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // Base-library functions the filter grammar and user formulas may reach.
    // Everything else BaseLib installs (print, pcall, select, pairs, rawset,
    // setfenv, loadfile, dofile, load, ...) is removed right after install.
    private static final Set<String> KEPT_GLOBALS = Set.of(
            "type", "tostring", "tonumber",
            "string", "table", "math");

    // functions.lua exports (local helpers listHas/compareNumeric need no entry).
    private static final Set<String> FUNCTIONS_LUA_EXPORTS = Set.of(
            "hasProperty", "hasPropertyValue", "hasPropertyContaining",
            "hasPropertyValueIn", "getPropertyValue",
            "hasPropertyValueGreaterThan", "hasPropertyValueGreaterThanOrEqual",
            "hasPropertyValueLessThan", "hasPropertyValueLessThanOrEqual",
            "fileField", "fileFieldStartsWith", "hasPropertyValueStartsWith",
            "fileInFolder", "hasEmptyProperty", "hasPropertyContainingAll");

    private final Globals globals;
    private final Map<String, LuaValue> compiledExpressions = lruCache();
    private final Map<String, LuaValue> compiledLuaExpressions = lruCache();

    private static Map<String, LuaValue> lruCache() {
        int cap = 512;
        return java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, LuaValue>(cap, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, LuaValue> eldest) {
                        return size() > cap;
                    }
                });
    }

    public LuaBaseInterpreter() {
        Globals g = new Globals();
        g.load(new JseBaseLib());
        g.load(new PackageLib());
        g.load(new StringLib());
        g.load(new TableLib());
        g.load(new JseMathLib());
        // The Lua compiler is required to compile note expressions and the
        // trusted wrappers; no `load/loadstring` global exists so note code
        // still cannot compile new chunks itself.
        org.luaj.vm2.compiler.LuaC.install(g);

        // Trusted helpers first: they compile against the full-but-soon-thinned
        // env, then the allowlist sweep strips what they must not reach. Their
        // definitions themselves stay (they are in FUNCTIONS_LUA_EXPORTS).
        g.load(FUNCTIONS_LUA).call();

        // ALLOWLIST SWEEP: nil every global not on the keep list. This removes
        // `package` (S1: an installed-only plumbing table whose searchers
        // re-arm os/luajava), `loadfile` (S2), `print`, `assert`, `pcall`,
        // `pairs`, `select`, `rawset`, `setfenv`... — the legacy deny list
        // (os, io, luajava, load, loadstring, dofile, require) is denied by
        // the same sweep.
        for (Map.Entry<String, LuaValue> entry : Map.copyOf(toJavaMap(g)).entrySet()) {
            String name = entry.getKey();
            if (!name.equals("_G") && !KEPT_GLOBALS.contains(name)
                    && !FUNCTIONS_LUA_EXPORTS.contains(name)) {
                g.set(name, LuaValue.NIL);
            }
        }

        // Frozen `_G` snapshot (S3 backstop): note code assigning
        // `_G.hasProperty = ...` writes into the COPY, never the live env.
        LuaTable snapshot = new LuaTable();
        for (Map.Entry<String, LuaValue> entry : toJavaMap(g).entrySet()) {
            if (!"_G".equals(entry.getKey())) {
                snapshot.set(entry.getKey(), entry.getValue());
            }
        }
        g.set("_G", snapshot);

        this.globals = g;
    }

    // Globals and LuaTable both are LuaTables; entrySet gives the flat view.
    private static Map<String, LuaValue> toJavaMap(LuaTable table) {
        java.util.Map<String, LuaValue> out = new java.util.LinkedHashMap<>();
        LuaValue k = LuaValue.NIL;
        while (true) {
            var next = table.next(k);
            if (next.isnil(1)) {
                break;
            }
            k = next.arg1();
            LuaValue v = next.arg(2);
            out.put(k.tojstring(), v);
        }
        return out;
    }

    public boolean evaluateExpression(String expression, Map<String, Object> properties) {
        return evaluateExpression(expression, properties, null);
    }

    // referencedProperties limits the per-row Lua table to the keys the filter
    // provably reads (FilterAnalyzer result); null coerces every property.
    // Only a proven subset may pass here — a key the filter actually reads but
    // that is missing from the set would read as nil and change results.
    public boolean evaluateExpression(String expression, Map<String, Object> properties, Set<String> referencedProperties) {
        checkExpressionLength(expression);
        LuaValue chunk = compiledExpressions.computeIfAbsent(expression, expr -> {
            String script = "local file = ...; return (" + expr + ")";
            return globals.load(script);
        });

        LuaValue luaFile = coerceFile(properties, referencedProperties);
        return chunk.call(luaFile).toboolean();
    }

    public void addFunction(String name, String function) {
        String script = "function " + name + "(file) " + function + " end";
        compiledLuaExpressions.clear();
        globals.load(script).call();
    }

    public LuaValue evaluateLuaExpression(String expression, Map<String, Object> file) {
        checkExpressionLength(expression);
        LuaValue function = compiledLuaExpressions.computeIfAbsent(expression, expr -> {
            String script = "return function(file) return " + expr + " end";
            LuaValue functionChunk = globals.load(script);
            return functionChunk.call();
        });

        LuaValue luaFile = coerceFile(file);
        return function.call(luaFile);
    }

    private static void checkExpressionLength(String expression) {
        if (expression != null && expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new org.luaj.vm2.LuaError("Filter expression exceeds " + MAX_EXPRESSION_LENGTH + " characters");
        }
    }

    public void validateExpression(String expression) {
        checkExpressionLength(expression);
        String script = "local file = ...; return (" + expression + ")";
        globals.load(script);
    }

    // Pure-Lua value coercion: no luajava exists in the sandbox, so Java-object
    // method dispatch is unavailable. The file is a Lua table; functions.lua
    // indexes it directly.
    //
    // referencedProperties == null coerces the whole map; a non-null set coerces
    // only those keys (partial coercion for recognized filters). Missing or
    // nil-valued keys are skipped in both modes, so a trimmed table is
    // indistinguishable from the full one to a filter that only reads the
    // referenced keys.
    private static LuaValue coerceFile(Map<String, Object> properties, Set<String> referencedProperties) {
        LuaTable table = new LuaTable();
        if (referencedProperties == null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                putCoerced(table, entry.getKey(), entry.getValue());
            }
            return table;
        }
        for (String key : referencedProperties) {
            putCoerced(table, key, properties.get(key));
        }
        return table;
    }

    private static LuaValue coerceFile(Map<String, Object> properties) {
        return coerceFile(properties, null);
    }

    private static void putCoerced(LuaTable table, String key, Object value) {
        if (value == null) {
            return;
        }
        LuaValue coerced = coerceValue(value);
        if (coerced != LuaValue.NIL) {
            table.set(key, coerced);
        }
    }

    private static LuaValue coerceValue(Object value) {
        if (value == null) {
            return LuaValue.NIL;
        }
        if (value instanceof String) {
            return LuaValue.valueOf((String) value);
        }
        if (value instanceof Boolean) {
            return LuaValue.valueOf((Boolean) value);
        }
        if (value instanceof Integer) {
            return LuaValue.valueOf(((Integer) value).doubleValue());
        }
        if (value instanceof Long) {
            return LuaValue.valueOf(((Long) value).doubleValue());
        }
        if (value instanceof Double) {
            return LuaValue.valueOf((Double) value);
        }
        if (value instanceof Float) {
            return LuaValue.valueOf(((Float) value).doubleValue());
        }
        if (value instanceof List) {
            LuaTable list = new LuaTable();
            int index = 1;
            for (Object item : (List<?>) value) {
                list.set(index++, coerceValue(item));
            }
            return list;
        }
        if (value instanceof Map) {
            LuaTable map = new LuaTable();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                map.set(String.valueOf(e.getKey()), coerceValue(e.getValue()));
            }
            return map;
        }
        return LuaValue.valueOf(String.valueOf(value));
    }
}
