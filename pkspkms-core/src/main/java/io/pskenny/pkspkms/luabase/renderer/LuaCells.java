package io.pskenny.pkspkms.luabase.renderer;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Cell-appropriate rendering of an evaluated ORDER expression: multi-valued
 * frontmatter (aliases, multitext, nested maps) coerces into a LuaTable and
 * used to reach the cell as Lua's "table: 0x…". Contract:
 * - nil → empty cell
 * - scalars → LuaJ tostring (integral numbers render "10" — pinned goldens)
 * - list-like table → values joined ", " (Obsidian Bases display)
 * - map-only table → "k: v" pairs joined ", " (hash order)
 * - nested tables recurse (depth-capped)
 */
final class LuaCells {

    private static final int MAX_DEPTH = 4;

    private LuaCells() {}

    static String text(LuaValue value) {
        return text(value, 0);
    }

    private static String text(LuaValue value, int depth) {
        // BUGS.md fix scope: ONLY table values change — all scalar behavior
        // (including Lua nil rendering as "nil") stays byte-identical to the
        // pinned goldens
        if (value != null && depth < MAX_DEPTH && value.type() == LuaValue.TTABLE) {
            return tableText(value.checktable(), depth);
        }
        return value == null ? "" : value.tojstring();
    }

    private static String tableText(LuaTable table, int depth) {
        // array part first: list-typed properties (aliases, multitext, tags)
        int n = table.length();
        if (n > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                if (i > 1) {
                    sb.append(", ");
                }
                sb.append(text(table.get(i), depth + 1));
            }
            return sb.toString();
        }
        // map-only table: "k: v" pairs, hash-order iteration
        StringBuilder sb = new StringBuilder();
        LuaValue key = LuaValue.NIL;
        while (true) {
            var next = table.next(key);
            key = next.arg1();
            if (key.isnil()) {
                break;
            }
            LuaValue v = next.arg(2);
            if (v.isnil()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(text(key, depth + 1)).append(": ").append(text(v, depth + 1));
        }
        return sb.toString();
    }
}
