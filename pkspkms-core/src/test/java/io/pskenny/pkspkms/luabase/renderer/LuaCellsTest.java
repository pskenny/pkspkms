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

package io.pskenny.pkspkms.luabase.renderer;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.LuaBaseInterpreter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cells never render Lua's "table: 0x…": list-typed frontmatter (aliases,
 * multitext) and nested maps join into readable cell text (user report:
 * `table: 3e0e1046` vault-wide on aliases columns).
 */
public class LuaCellsTest {

    private static final LuaBaseInterpreter LUA = new LuaBaseInterpreter();
    private static final TableRenderer TABLE = new TableRenderer();
    private static final ListRenderer LIST = new ListRenderer();

    private static io.pskenny.pkspkms.io.PksFile file(String path, Map<String, Object> props) {
        return new io.pskenny.pkspkms.io.PksFile(path, props);
    }

    @Test
    void multiValuedCellJoinsTheList() {
        String out = TABLE.render(
                List.of("getPropertyValue(file, \"aliases\", \"\"), \"Aliases\""),
                List.of(file("a.md", Map.of("aliases", List.of("Real Name", "Nick")))),
                LUA);
        assertTrue(out.contains("| Real Name, Nick |"), out);
    }

    @Test
    void mapValuedCellJoinsPairs() {
        String out = TABLE.render(
                List.of("getPropertyValue(file, \"meta\", \"\"), \"Meta\""),
                List.of(file("a.md", Map.of("meta", Map.of("soil", "pH 6")))),
                LUA);
        assertTrue(out.contains("soil"), out);
        assertFalse(out.contains("table: "), out);
    }

    @Test
    void scalarAndNilCellsUnchanged() {
        String out = TABLE.render(
                List.of(
                        "getPropertyValue(file, \"weight\", \"\"), \"W\"",
                        "getPropertyValue(file, \"missing\", \"\"), \"M\"",
                        "getPropertyValue(file, \"never-present\"), \"Nil\"",
                        "getPropertyValue(file, \"nickname\", \"\"), \"N\""),
                List.of(file("a.md", Map.of("weight", 10, "nickname", "grey"))),
                LUA);
        assertTrue(out.contains("| 10 |  | nil | grey |"), "scalar goldens hold: " + out);
        assertFalse(out.contains("table: "), out);
    }

    @Test
    void listRendererJoinsToo() {
        String out = LIST.render(
                List.of("getPropertyValue(file, \"status\", \"\"), \"\""),
                List.of(file("a.md", Map.of("status", List.of("open", "someday")))),
                LUA);
        assertTrue(out.contains("open, someday"), out);
        assertFalse(out.contains("table: "), out);
    }
}
