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

import java.util.List;
import java.util.Map;

/**
 * Native Java evaluation for converter-whitelisted filter ASTs, mirroring the
 * Lua semantics of {@code luabase/functions.lua} value-by-value:
 *
 * - all numbers are doubles (Lua float coercion)
 * - {@code hasPropertyContaining} on scalars is a plain substring of
 *   {@code tostring(val)}; on lists it is exact membership
 * - {@code tonumber}-based numeric comparisons accept numeric strings
 * - mixed-type ordering comparisons crash in Lua → here: non-match
 * - absent keys read as nil; nil truthiness is false
 *
 * Parity is pinned by the differential harness
 * ({@code FilterEvalDifferentialTest}); unrecognized filters never reach this
 * class — they keep the Lua interpreter path.
 */
final class FilterEval {

    private FilterEval() {}

    static boolean eval(FilterExpr expr, Map<String, Object> properties) {
        if (expr instanceof FilterExpr.BoolLit b) {
            return b.value();
        }
        if (expr instanceof FilterExpr.NilLit) {
            return false; // nil truthiness in Lua
        }
        if (expr instanceof FilterExpr.Not n) {
            return !eval(n.inner(), properties);
        }
        if (expr instanceof FilterExpr.And a) {
            return eval(a.left(), properties) && eval(a.right(), properties);
        }
        if (expr instanceof FilterExpr.Or o) {
            return eval(o.left(), properties) || eval(o.right(), properties);
        }
        if (expr instanceof FilterExpr.Call c) {
            return truthy(evalCallValue(c, properties));
        }
        if (expr instanceof FilterExpr.Compare cmp) {
            return evalCompare(cmp, properties);
        }
        throw new IllegalStateException("unexpected filter node: " + expr);
    }

    private interface DoubleBiPredicate {
        boolean test(double val, double limit);
    }

    private static boolean truthy(Object value) {
        // Lua truthiness: only nil and false are falsy
        return value != null && !Boolean.FALSE.equals(value);
    }

    // --- call surface (functions.lua parity) ---

    private static Object evalCallValue(FilterExpr.Call call, Map<String, Object> properties) {
        List<Object> args = call.arguments();
        String prop = (String) args.get(1);
        switch (call.function()) {
            case "hasProperty":
                // C1: Lua is `file[prop] ~= nil` — boolean FALSE is a value
                return properties.get(prop) != null;
            case "hasPropertyValue":
                return membership(properties.get(prop), args.get(2));
            case "hasPropertyContaining":
                return containing(properties.get(prop), args.get(2));
            case "hasPropertyValueIn":
                return inList(properties.get(prop), args.get(2));
            case "hasPropertyContainingAll":
                return containingAll(properties.get(prop), args.get(2));
            case "hasPropertyValueStartsWith":
                return startsWith(properties.get(prop), (String) args.get(2));
            case "hasPropertyValueGreaterThan":
                return numericFn(properties.get(prop), args.get(2), (v, l) -> v > l);
            case "hasPropertyValueGreaterThanOrEqual":
                return numericFn(properties.get(prop), args.get(2), (v, l) -> v >= l);
            case "hasPropertyValueLessThan":
                return numericFn(properties.get(prop), args.get(2), (v, l) -> v < l);
            case "hasPropertyValueLessThanOrEqual":
                return numericFn(properties.get(prop), args.get(2), (v, l) -> v <= l);
            case "fileFieldStartsWith":
                return fileFieldStartsWith(properties.get("filePath"), prop, (String) args.get(2));
            case "fileInFolder":
                return inFolder(properties.get("filePath"), (String) args.get(1));
            case "hasEmptyProperty":
                return empty(properties.get(prop));
            case "getPropertyValue":
                return getPropertyValue(properties, prop, args.get(2));
            default:
                throw new IllegalStateException("unexpected whitelisted fn: " + call.function());
        }
    }

    private static boolean evalCompare(FilterExpr.Compare compare, Map<String, Object> properties) {
        Object left = valueOf(compare.left(), properties);
        Object right = compare.right();

        boolean eq = luajEq(left, right);
        switch (compare.operator()) {
            case EQ:
                return eq;
            case NEQ:
                return !eq;
            default:
                return orderCompare(left, right, compare.operator());
        }
    }

    // The Compare evaluation reads the left's VALUE (a call result, or a
    // boolean once a not-chain wraps it — boolean-vs-number always misses,
    // which is exactly Lua's runtime error behavior).
    private static Object valueOf(FilterExpr node, Map<String, Object> properties) {
        if (node instanceof FilterExpr.Call call) {
            return evalCallValue(call, properties);
        }
        if (node instanceof FilterExpr.Not not) {
            return eval(not.inner(), properties);
        }
        throw new IllegalStateException("compare left must be a call or a not-chain over a call: " + node);
    }

    // hasProperty / hasPropertyValue / hasPropertyValueIn: Lua equality —
    // a number argument matches number-typed values only, a string argument
    // matches string-typed values only (Lua `==`).
    private static boolean membership(Object raw, Object wanted) {
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (luajEq(item, wanted)) {
                    return true;
                }
            }
            return false;
        }
        return luajEq(raw, wanted);
    }

    // hasPropertyContaining: scalar → plain substring of tostring(val);
    // list → exact membership (listHas, NOT substring).
    private static boolean containing(Object raw, Object wanted) {
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (luajEq(item, wanted)) {
                    return true;
                }
            }
            return false;
        }
        if (raw instanceof Map) {
            // C3: Lua listHas over a map-only table is `#t==0` → blind loop → false
            return false;
        }
        if (raw == null) {
            return false; // string.find(nil, …) errors → non-match
        }
        return luajString(raw).contains(luajString(wanted));
    }

    private static boolean containingAll(Object raw, Object values) {
        if (!(raw instanceof List<?> list)) {
            return false; // functions.lua: type(val) ~= "table" → false
        }
        var wanted = (List<?>) values;
        for (Object w : wanted) {
            boolean found = false;
            for (Object item : list) {
                if (luajEq(item, w)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true; // empty table constructor → vacuous true
    }

    private static boolean inList(Object raw, Object arg) {
        if (arg instanceof List<?> values) {
            for (Object value : values) {
                if (membership(raw, value)) {
                    return true;
                }
            }
            return false;
        }
        return membership(raw, arg);
    }

    private static boolean startsWith(Object raw, Object prefix) {
        if (raw == null) {
            return false;
        }
        // C14: Lua tostring(table) = "table: 0x…" — authored prefixes never match
        if (raw instanceof Map || raw instanceof List) {
            return false;
        }
        return luajString(raw).startsWith(luajString(prefix));
    }

    private static boolean empty(Object raw) {
        if (raw == null) {
            return true;
        }
        if (raw instanceof String s) {
            return s.isEmpty();
        }
        if (raw instanceof List<?> list) {
            return list.isEmpty();
        }
        if (raw instanceof Map<?, ?> map) {
            return map.isEmpty(); // Lua #table over a map-only table is 0
        }
        return false;
    }

    private static boolean fileFieldStartsWith(Object filePath, String field, String prefix) {
        return fileField(filePath, field).startsWith(prefix);
    }

    // functions.lua fileField: name / basename / ext derived from filePath
    private static String fileField(Object filePath, String field) {
        String path = filePath == null ? "" : String.valueOf(filePath);
        String name = lastSegment(path);
        switch (field) {
            case "basename": {
                int dot = name.lastIndexOf('.');
                return dot == -1 ? name : name.substring(0, dot);
            }
            case "name":
                return name;
            case "ext": {
                int dot = name.lastIndexOf('.');
                return dot == -1 ? "" : name.substring(dot + 1);
            }
            default:
                return path;
        }
    }

    private static String lastSegment(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash == -1 ? path : path.substring(slash + 1);
    }

    private static boolean inFolder(Object filePath, String folder) {
        if (filePath == null) {
            return false;
        }
        String path = String.valueOf(filePath);
        String dir = folder; // functions.lua: "" matches all; trailing slash stripped
        if (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        if (dir.isEmpty()) {
            return true;
        }
        return path.startsWith(dir + "/");
    }

    private static Object getPropertyValue(Map<String, Object> properties, String prop, Object defaultArg) {
        Object raw = properties.get(prop);
        // Lua coerce skips nil-valued keys entirely — absent and explicit-nil
        // both fall through to the default
        if (raw == null) {
            return defaultArg;
        }
        return coerce(raw);
    }

    // tonumber(val) then cmp — tonumber failures are non-matches
    private static boolean numericFn(Object raw, Object wanted, DoubleBiPredicate test) {
        Double val = tonumber(raw);
        Double limit = (wanted instanceof Double d) ? d : null;
        if (val == null || limit == null) {
            return false;
        }
        return test.test(val, limit);
    }

    // Lua `==`: same-type compare, double-numeric for numbers; mixed → false
    // (both sides pass the Lua value coercion first — JSON integers surface
    // as doubles in Lua, so 1 == 1.0 holds)
    private static boolean luajEq(Object a, Object b) {
        Object na = coerce(a);
        Object nb = coerce(b);
        if (na == null || nb == null) {
            return na == null && nb == null;
        }
        if (na instanceof Double && nb instanceof Double) {
            return ((Double) na).doubleValue() == ((Double) nb).doubleValue();
        }
        if (na instanceof String && nb instanceof String) {
            return na.equals(nb);
        }
        if (na instanceof Boolean && nb instanceof Boolean) {
            return na.equals(nb);
        }
        return false; // mixed-type `==`: false; Lua table equality is identity
    }

    // Lua ordering `< <= > >=`: numbers arithmetically, strings
    // lexicographically; mixed types crash at runtime → non-match
    private static boolean orderCompare(Object a, Object b, FilterExpr.Op op) {
        int cmp;
        if (a instanceof Double && b instanceof Double) {
            cmp = Double.compare(((Double) a).doubleValue(), ((Double) b).doubleValue());
        } else if (a instanceof String && b instanceof String) {
            cmp = luaBytesCompare((String) a, (String) b);
        } else {
            return false; // mixed-type ordering = Lua runtime error → non-match
        }
        switch (op) {
            case LT:
                return cmp < 0;
            case LE:
                return cmp <= 0;
            case GT:
                return cmp > 0;
            case GE:
                return cmp >= 0;
            default:
                return false;
        }
    }

    // C6: LuaJ (LuaString.strcmp) compares UTF-8 BYTES as signed values —
    // not UTF-16 code units. é (0xC3 first byte) sorts BEFORE 'e'.
    private static int luaBytesCompare(String a, String b) {
        byte[] ab = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(ab.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = ab[i]; // implicit sign-extension — the point
            int y = bb[i];
            if (x != y) {
                return x - y;
            }
        }
        return ab.length - bb.length;
    }

    // C5: LuaJ LuaString.scannumber parity — SPACES-only trim, hex integer
    // integrals accepted (0x…), plain decimal with optional exponent; no
    // Infinity/NaN, no d/f suffixes, no other whitespace.
    private static Double tonumber(Object raw) {
        if (raw instanceof Double d) {
            return d;
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            return luaScannumber(s);
        }
        return null;
    }

    private static Double luaScannumber(String s) {
        String trimmed = trimSpaces(s);
        if (trimmed.isEmpty()) {
            return null;
        }
        int i = 0;
        boolean negative = false;
        if (trimmed.charAt(0) == '-') {
            negative = true;
            i = 1;
        } else if (trimmed.charAt(0) == '+') {
            i = 1;
        }
        String body = trimmed.substring(i);
        if (body.startsWith("0x") || body.startsWith("0X")) {
            try {
                return negative ? -(double) Long.parseLong(body.substring(2), 16)
                        : (double) Long.parseLong(body.substring(2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        boolean decimal = body.matches("\\d+(?:\\.\\d*)?(?:[eE][+-]?\\d+)?")
                || body.matches("\\d*\\.\\d+(?:[eE][+-]?\\d+)?");
        if (!decimal) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(body);
            return negative ? -parsed : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimSpaces(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(start, end);
    }

    private static Object coerce(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof List<?> list) {
            var out = new java.util.ArrayList<Object>(list.size());
            for (Object item : list) {
                Object coerced = coerce(item);
                if (coerced != null) {
                    out.add(coerced);
                }
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            return map; // nested maps stay structured (parity: LuaTable assignment)
        }
        return null;
    }

    // Lua `tostring`: numbers via LuaDouble.tojstring — integral doubles
    // render without ".0" ("1000"), non-integral via Float.toString,
    // NaN→"nan", inf→"inf" (C2).
    private static String luajString(Object value) {
        if (value == null) {
            return "nil";
        }
        if (value instanceof Double d) {
            return luaNumberToString(d);
        }
        return String.valueOf(value);
    }

    // C2: Lua `tostring` parity, replacing the past dead double-ternary.
    private static String luaNumberToString(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (d == Double.POSITIVE_INFINITY) {
            return "inf";
        }
        if (d == Double.NEGATIVE_INFINITY) {
            return "-inf";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d); // no ".0"
        }
        return Float.toString((float) d);
    }
}
