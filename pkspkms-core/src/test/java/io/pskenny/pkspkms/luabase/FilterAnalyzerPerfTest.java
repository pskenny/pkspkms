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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Timing comparison for partial vs. full per-row coercion. Not part of the
// default suite (timing assertions would be flaky); run manually with:
//   mvn test -pl pkspkms-core -Dtest=FilterAnalyzerPerfTest -Dperf=true
// The correctness assertions (identical match sets) always hold; the logged
// timings are the deliverable.
class FilterAnalyzerPerfTest {

    private static final int CORPUS_SIZE = 10_000;
    private static final int PROPERTIES = 20;
    private static final String PROPERTY_NAMES = "abcdefghij0123456789";

    private String typicalFilterYaml() {
        return """
            views:
              - type: table
                filters:
                  and:
                    - 'hasPropertyValue(file, "tags", "x")'
                    - 'hasPropertyContaining(file, "filePath", "note")'
                order:
                  - 'getPropertyValue(file, "filePath", ""), "Path"'
            """;
    }

    private String unrecognizedFilterYaml() {
        return """
            views:
              - type: table
                filters:
                  and:
                    - 'file["tags"] ~= nil'
                order:
                  - 'getPropertyValue(file, "filePath", ""), "Path"'
            """;
    }

    private List<PksFile> syntheticCorpus() {
        Random random = new Random(42);
        List<PksFile> corpus = new ArrayList<>(CORPUS_SIZE);
        for (int i = 0; i < CORPUS_SIZE; i++) {
            Map<String, Object> props = new HashMap<>();
            for (int p = 0; p < PROPERTIES; p++) {
                props.put("prop" + PROPERTY_NAMES.charAt(p), "value-" + random.nextInt(1000));
            }
            // ~75% of rows match the typical filter, so match sets are non-trivial
            List<String> tags = random.nextInt(4) == 0
                    ? List.of("other")
                    : List.of("x", "tag-" + random.nextInt(10));
            props.put("tags", tags);
            props.put("filePath", "folder/note-" + i + ".md");
            props.put("price", random.nextInt(100));
            corpus.add(new PksFile("note-" + i + ".md", props));
        }
        return corpus;
    }

    @Test
    @EnabledIfSystemProperty(named = "perf", matches = "true")
    void partialCoercionTiming() {
        List<PksFile> corpus = syntheticCorpus();
        YamlParser parser = new YamlParser();

        for (String label : List.of("partial (recognized)", "full (unrecognized)")) {
            String yaml = label.startsWith("partial") ? typicalFilterYaml() : unrecognizedFilterYaml();
            Map<String, Object> spec = parser.parse(yaml);
            // warm-up
            new LuaBaseProcessor().processOverCorpus(spec, corpus);
            int matches = 0;
            long bestNanos = Long.MAX_VALUE;
            for (int run = 0; run < 3; run++) {
                long start = System.nanoTime();
                String result = new LuaBaseProcessor().processOverCorpus(spec, corpus);
                long elapsed = System.nanoTime() - start;
                bestNanos = Math.min(bestNanos, elapsed);
                matches = countMatches(result);
            }
            System.out.printf("PERF %-24s corpus=%d rows  best=%dms  rows/sec=%,d  tableRows=%d%n",
                    label, CORPUS_SIZE, bestNanos / 1_000_000,
                    CORPUS_SIZE * 1_000_000_000L / bestNanos, matches);
        }

        // Filter phase in isolation: the full render includes the order-column
        // evaluation, which still coerces per matched row
        Map<String, Object> typicalSpec = parser.parse(typicalFilterYaml());
        String luaFilter = new LuaBaseProcessor()
                .filterYamlToExpression(((List<Map<String, Object>>) typicalSpec.get("views")).get(0).get("filters"));
        FilterAnalyzer.Result analysis = FilterAnalyzer.analyze(luaFilter);
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        for (String label : List.of("filter-only partial", "filter-only full")) {
            Set<String> referenced = label.endsWith("partial") ? analysis.referencedProperties() : null;
            for (PksFile file : corpus) { // warm-up
                interpreter.evaluateExpression(luaFilter, file.getMutableProperties(), referenced);
            }
            long bestNanos = Long.MAX_VALUE;
            int matched = 0;
            for (int run = 0; run < 3; run++) {
                long start = System.nanoTime();
                int count = 0;
                for (PksFile file : corpus) {
                    if (interpreter.evaluateExpression(luaFilter, file.getMutableProperties(), referenced)) {
                        count++;
                    }
                }
                long elapsed = System.nanoTime() - start;
                bestNanos = Math.min(bestNanos, elapsed);
                matched = count;
            }
            System.out.printf("PERF %-24s corpus=%d rows  best=%dms  rows/sec=%,d  matched=%d%n",
                    label, CORPUS_SIZE, bestNanos / 1_000_000,
                    CORPUS_SIZE * 1_000_000_000L / bestNanos, matched);
        }
    }

    // The differential guard, end to end: for every corpus row the analyzed
    // (trimmed) table and the full-coercion table must agree.
    @Test
    @EnabledIfSystemProperty(named = "perf", matches = "true")
    void partialAndFullProduceIdenticalMatchSets() {
        List<PksFile> corpus = syntheticCorpus();
        Map<String, Object> spec = new YamlParser().parse(typicalFilterYaml());
        LuaBaseProcessor processor = new LuaBaseProcessor();
        Map<String, Object> view = ((List<Map<String, Object>>) spec.get("views")).get(0);
        String luaFilter = processor.filterYamlToExpression(view.get("filters"));
        FilterAnalyzer.Result analysis = FilterAnalyzer.analyze(luaFilter);
        assertEquals(true, analysis.recognized());

        Set<String> partialMatches = new HashSet<>();
        Set<String> fullMatches = new HashSet<>();
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        for (PksFile file : corpus) {
            if (interpreter.evaluateExpression(luaFilter, file.getMutableProperties(), analysis.referencedProperties())) {
                partialMatches.add(file.getFilePath());
            }
            if (interpreter.evaluateExpression(luaFilter, file.getMutableProperties())) {
                fullMatches.add(file.getFilePath());
            }
        }
        assertEquals(fullMatches, partialMatches, "trimmed and full-coercion match sets must be identical");
        assertFalse(fullMatches.isEmpty(), "sanity: filter matched something");
        assertTrue(fullMatches.size() < CORPUS_SIZE, "sanity: filter excluded rows");
    }

    private int countMatches(String rendered) {
        return Math.max(0, rendered.split("\n", -1).length - 2);
    }
}
