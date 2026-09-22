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

public final class LuaBaseInterpreter {

    // Generous ceiling on note-controlled filters: the cheapest CPU-DoS guard
    // (a full watchdog is deferred; see SECURITY HARDENING 1 residual notes)
    private static final int MAX_EXPRESSION_LENGTH = 4096;

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

    private final ThreadLocal<ThreadState> threadState = ThreadLocal.withInitial(ThreadState::new);

    // Compile caches are LRU-bounded: eviction just recompiles on next use
    // (P3: these were unbounded per-thread before)
    private static final int COMPILE_CACHE_CAP = 512;

    private static class ThreadState {
        final Globals globals;
        final Map<String, LuaValue> compiledExpressions = lruCache();
        final Map<String, LuaValue> compiledLuaExpressions = lruCache();

        ThreadState() {
            // Allow-list environment: only what functions.lua and the generated
            // filters need. Note-controlled Lua must never reach the OS, the
            // filesystem, or Java reflection.
            this.globals = new Globals();
            this.globals.load(new JseBaseLib());
            this.globals.load(new PackageLib());
            this.globals.load(new StringLib());
            this.globals.load(new TableLib());
            this.globals.load(new JseMathLib());
            // The Lua compiler is required to compile our trusted wrappers and
            // functions.lua — the `load` global itself is denied below, so
            // note-controlled code still cannot compile new chunks
            org.luaj.vm2.compiler.LuaC.install(this.globals);

            LuaValue nil = LuaValue.NIL;
            this.globals.set("os", nil);
            this.globals.set("io", nil);
            this.globals.set("luajava", nil);
            this.globals.set("load", nil);
            this.globals.set("loadstring", nil);
            this.globals.set("dofile", nil);
            this.globals.set("require", nil);

            this.globals.load(FUNCTIONS_LUA).call();
        }
    }

    private static Map<String, LuaValue> lruCache() {
        return java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<>(COMPILE_CACHE_CAP, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, LuaValue> eldest) {
                        return size() > COMPILE_CACHE_CAP;
                    }
                });
    }

    public boolean evaluateExpression(String expression, Map<String, Object> properties) {
        checkExpressionLength(expression);
        ThreadState ts = threadState.get();
        LuaValue chunk = ts.compiledExpressions.computeIfAbsent(expression, expr -> {
            String script = "local file = ...; return (" + expr + ")";
            return ts.globals.load(script);
        });

        LuaValue luaFile = coerceFile(properties);
        return chunk.call(luaFile).toboolean();
    }

    public void addFunction(String name, String function) {
        ThreadState ts = threadState.get();
        String script = "function " + name + "(file) " + function + " end";
        ts.globals.load(script).call();
    }

    public LuaValue evaluateLuaExpression(String expression, Map<String, Object> file) {
        checkExpressionLength(expression);
        ThreadState ts = threadState.get();
        LuaValue function = ts.compiledLuaExpressions.computeIfAbsent(expression, expr -> {
            String script = "return function(file) return " + expr + " end";
            LuaValue functionChunk = ts.globals.load(script);
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
        ThreadState ts = threadState.get();
        String script = "local file = ...; return (" + expression + ")";
        ts.globals.load(script);
    }

    // Pure-Lua value coercion: the sandbox denies luajava, so Java-object
    // method dispatch (file:get, list:contains) is unavailable. The file is
    // a Lua table; functions.lua indexes it directly.
    private static LuaValue coerceFile(Map<String, Object> properties) {
        LuaTable table = new LuaTable();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            LuaValue value = coerceValue(entry.getValue());
            if (value != LuaValue.NIL) {
                table.set(entry.getKey(), value);
            }
        }
        return table;
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