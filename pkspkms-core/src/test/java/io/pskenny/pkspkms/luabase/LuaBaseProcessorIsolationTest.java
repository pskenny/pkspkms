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
import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-note isolation + formula hardening pins (BUGS.md S3/M3).
 *
 * A single shared LuaBaseProcessor must produce independent render passes:
 * no note's formulas may linger and reshape another note's view. Formula
 * definitions that are invalid must degrade that column, never the embed.
 */
public class LuaBaseProcessorIsolationTest {

    private static final LuaBaseProcessor PROCESSOR = new LuaBaseProcessor();

    private static Map<String, Object> spec(Map<String, Object> view, Map<String, Object> formulas) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("views", List.of(view));
        if (formulas != null && !formulas.isEmpty()) {
            spec.put("formulas", formulas);
        }
        return spec;
    }

    private static Map<String, Object> tableWithOrder(List<String> orderColumns) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", "table");
        view.put("order", orderColumns);
        return spec(view, null);
    }

    // S3: note A defines a formula NAMED LIKE A TRUSTED HELPER ("getPropertyValue").
    // Note B (same processor instance, i.e. the would-be poisoned thread/env)
    // must still render with the real helper semantics.
    @Test
    void crossNoteFormulaPoisoningDoesNotLeak() {
        Map<String, Object> poisonView = new LinkedHashMap<>();
        poisonView.put("type", "table");
        poisonView.put("order", List.of("getPropertyValue(file, \"missing\", \"\"), \"P\""));
        Map<String, Object> poison = Map.of("getPropertyValue", "return \"poison\"");
        String noteA = PROCESSOR.processOverCorpus(spec(poisonView, poison),
                List.of(_file("a.md", Map.of("filePath", "a.md"))));

        // note B: no formulas; the helper must be the functions.lua one
        String noteB = PROCESSOR.processOverCorpus(
                tableWithOrder(List.of("getPropertyValue(file, \"missing\", \"default\"), \"P\"")),
                List.of(_file("b.md", Map.of("filePath", "b.md"))));
        assertTrue(noteB.contains("default"), "note B helper must survive note A's poisoning render: " + noteB);
        assertFalse(noteB.contains("poison"), "note B must not see note A's formula: " + noteB);
        assertTrue(noteA.contains("poison"), "note A's own render may use its formula: " + noteA);
    }

    // M3: a formula whose NAME is not a Lua identifier is skipped; the note
    // still renders (with remaining formulas / headers), instead of the old
    // behavior (whole embed "ERROR" from globals.load throwing).
    @Test
    void formulaWithUnsafeNameIsSkippedAndEmbedStillRenders() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", "table");
        view.put("order", List.of(
                "getPropertyValue(file, \"filePath\", \"\"), \"Path\""));
        Map<String, Object> formulas = new LinkedHashMap<>();
        formulas.put("x; dofile", "return 1");
        formulas.put("my formula", "return 2");
        String out = PROCESSOR.processOverCorpus(spec(view, formulas), List.of(_file("n.md", Map.of("filePath", "n.md"))));
        assertTrue(out.contains("| n.md |"), "valid columns still render: " + out);
        assertFalse(out.contains("ERROR"), "bad formula names must not error the embed");
    }

    // M3: a formula body with a syntax error is skipped per-formula.
    @Test
    void formulaWithBrokenBodyIsSkippedAndEmbedStillRenders() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", "table");
        view.put("order", List.of(
                "getPropertyValue(file, \"filePath\", \"\"), \"Path\"",
                "broken(file, \"x\"), \"B\""));
        Map<String, Object> formulas = new LinkedHashMap<>();
        formulas.put("broken", "this is ] not [[ valid lua");
        String out = PROCESSOR.processOverCorpus(spec(view, formulas), List.of(_file("n.md", Map.of("filePath", "n.md"))));
        assertTrue(out.contains("| n.md |"), "valid columns still render: " + out);
        assertFalse(out.contains("ERROR"), "broken formula must degrade, not error the embed");
    }

    // M3: an oversized formula body is capped (mirrors the filter cap).
    @Test
    void oversizedFormulaBodyIsSkipped() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", "table");
        view.put("order", List.of("getPropertyValue(file, \"filePath\", \"\"), \"Path\""));
        Map<String, Object> formulas = Map.of("huge", "return file.filePath" + " + ".repeat(5000) + " 0");
        String out = PROCESSOR.processOverCorpus(spec(view, formulas), List.of(_file("n.md", Map.of("filePath", "n.md"))));
        assertTrue(out.contains("| n.md |"), "embed still renders after oversized formula skip: " + out);
    }

    private static io.pskenny.pkspkms.io.PksFile _file(String path, Map<String, Object> props) {
        return new io.pskenny.pkspkms.io.PksFile(path, props);
    }
}
