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

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.YamlParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Tier 4: embed evaluation must have ONE path. The legacy `process(Map spec,
// Map files)` viewer path used to diverge from the corpus path on invalid
// filters (full unfiltered table vs headers-only) and on coercion strategy
// (full vs FilterAnalyzer-pruned). After the collapse both behaviors are
// pinned by the corpus path alone.
class EmbedPathUnificationTest {

    private final LuaBaseProcessor processor = new LuaBaseProcessor();
    private final YamlParser yaml = new YamlParser();

    private Map<String, PksFile> files() {
        Map<String, PksFile> files = new LinkedHashMap<>();
        PksFile a = new PksFile("a.md", new HashMap<>(Map.of("tags", "x")));
        PksFile b = new PksFile("b.xml", new HashMap<>());
        files.put("a.md", a);
        files.put("b.xml", b);
        return files;
    }

    private static final String SPEC = """
            views:
              - type: table
                filters:
                  and:
                    - 'this is ) not ( valid'
                order:
                  - 'getPropertyValue(file, "filePath"), "Path"'
            """;

    private static final String HEADERS_ONLY = """
            | Path |
            |---|
            """;

    @Test
    void invalidFilterRendersHeadersOnlyOnCorpusPath() {
        assertEquals(HEADERS_ONLY.strip(), processor.processOverCorpus(yaml.parse(SPEC),
                java.util.List.copyOf(files().values())).strip());
    }

    @Test
    void mapShapedCorpusRendersTheSameTable() {
        // The old map-shaped viewer entry is gone (Tier 4 collapse); the
        // map-file corpus conversion (List.copyOf(values)) renders identically.
        String out = processor.processOverCorpus(yaml.parse("""
                views:
                  - type: table
                    filters:
                      and:
                        - hasPropertyValue(file, "tags", "x")
                    order:
                      - 'getPropertyValue(file, "filePath"), "Path"'
                """), java.util.List.copyOf(files().values())).strip();
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("| a.md |"), out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("| b.xml |"), out);
    }
}