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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

// BUGS.md B17: `|`/newline cells corrupted the emitted table and a comma in
// the header label broke column parsing.
class TableRendererTest {

    private final TableRenderer renderer = new TableRenderer();
    private final LuaBaseInterpreter lua = new LuaBaseInterpreter();

    @Test
    void headerWithCommaStaysIntact() {
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("note", "x");
        }}));

        String table = renderer.render(
                java.util.List.of("getPropertyValue(file, \"note\"), \"Note, full\""),
                files.values(), lua);

        assertTrue(table.contains("| Note, full |"), table);
        assertTrue(table.contains("| x |"), table);
    }

    @Test
    void pipeInCellValueEscaped() {
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("note", "left|right");
        }}));

        String table = renderer.render(
                java.util.List.of("getPropertyValue(file, \"note\"), \"Note\""),
                files.values(), lua);

        assertTrue(table.contains("| left\\|right |"), table);
    }

    @Test
    void newlineInCellFlattened() {
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("note", "line1\nline2");
        }}));

        String table = renderer.render(
                java.util.List.of("getPropertyValue(file, \"note\"), \"Note\""),
                files.values(), lua);

        assertTrue(table.contains("| line1 line2 |"), table);
    }

    @Test
    void nilCellKeepsTheLuaNilText() {
        // Pinned behavior (LuaBaseProcessorTest golden): a Lua nil evaluates to
        // the string "nil" in the cell — mdCell(null) is only the Java null
        // defense and is not reached through the Lua path here.
        Map<String, PksFile> files = new java.util.LinkedHashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>()));

        String table = renderer.render(
                java.util.List.of("getPropertyValue(file, \"missing\"), \"Note\""),
                files.values(), lua);

        assertEquals("""
                | Note |
                |---|
                | nil |
                """, table);
    }
}
