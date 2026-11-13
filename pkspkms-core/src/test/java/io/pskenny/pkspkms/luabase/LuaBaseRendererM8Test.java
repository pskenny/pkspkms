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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 remainder pins: per-column failure isolation in table rendering
 * (cell count always matches the header), and blank filter children/specs
 * condition nothing (they used to poison the join into headers-only).
 */
public class LuaBaseRendererM8Test {

    private static final LuaBaseProcessor PROCESSOR = new LuaBaseProcessor();

    private static io.pskenny.pkspkms.io.PksFile file(String path, Map<String, Object> props) {
        return new io.pskenny.pkspkms.io.PksFile(path, props);
    }

    @Test
    void middleColumnFailureKeepsHeaderAndRowAlignment() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", "table");
        view.put("order", List.of(
                "getPropertyValue(file, \"filePath\", \"\"), \"P\"",
                "missingHelper(file, \"x\"), \"Failing\"",
                "getPropertyValue(file, \"status\", \"-\"), \"S\""));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("views", List.of(view));

        String out = PROCESSOR.processOverCorpus(spec, List.of(
                file("a.md", Map.of("filePath", "a.md", "status", "open"))));

        String[] lines = out.split("\n");
        String header = lines[0];
        String row = lines[2]; // line 1 is the separator row

        int headerCells = header.split("\\|", -1).length;
        int rowCells = row.split("\\|", -1).length;
        assertEquals(headerCells, rowCells, "cells must align with header: header=" + header + " row=" + row);
        assertTrue(row.contains("a.md"), "first column still renders: " + out);
        assertTrue(row.contains("open"), "third column still renders: " + out);
        assertTrue(row.contains("error"), "failing column marked in place: " + out);
        assertFalse(out.contains("ERROR"), "no whole-embed error");
    }

    @Test
    void blankFilterChildConditionsNothing() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", "table");
        view.put("order", List.of("getPropertyValue(file, \"filePath\", \"\"), \"P\""));
        view.put("filters", new LinkedHashMap<>(Map.of("and", List.of(
                "",
                "hasProperty(file, \"status\")"))));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("views", List.of(view));

        String out = PROCESSOR.processOverCorpus(spec, List.of(
                file("a.md", Map.of("filePath", "a.md", "status", "open")),
                file("b.md", Map.of("filePath", "b.md"))));

        assertTrue(out.contains("| a.md |"), "matching row surfaces: " + out);
        assertFalse(out.contains("| b.md |"), "other rows stay filtered out: " + out);
        assertFalse(out.contains("ERROR"), "blank child must not break the filter: " + out);
    }

    @Test
    void notWithEmptyChildListPairStrafe() {
        // `not: []` stays a no-op (documented), and non-list `not` children behave
        Map<String, Object> spec = new LinkedHashMap<>();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", "table");
        view.put("order", List.of("getPropertyValue(file, \"filePath\", \"\"), \"P\""));
        view.put("filters", Map.of("not", List.of()));
        spec.put("views", List.of(view));

        String out = PROCESSOR.processOverCorpus(spec, List.of(
                file("a.md", Map.of("filePath", "a.md"))));
        assertTrue(out.contains("| a.md |"), "empty not list matches everything: " + out);
    }
}
