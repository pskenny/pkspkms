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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Tier 8 measurement (printed, not asserted as a hard gate): Java-native
// FilterEval vs the Lua interpreter over a synthetic corpus × battery.
// Acceptance per plan: >1.3x keeps the native path; anything less is a
// documented write-off.
@Tag("tier8")
class Tier8Perf {

    private static final int CORPUS = 2_000;
    private static final int WARMUP = 3;
    private static final int RUNS = 5;

    private static final List<String> BATTERY = List.of(
            "hasProperty(file, \"tags\")",
            "hasPropertyValue(file, \"status\", \"open\")",
            "hasPropertyContaining(file, \"filePath\", \"Books\")",
            "hasPropertyValueIn(file, \"tags\", {\"x\", \"sci-fi\"})",
            "hasPropertyContainingAll(file, \"tags\", {\"x\", \"sci-fi\"})",
            "hasPropertyValueGreaterThan(file, \"price\", 4)",
            "fileFieldStartsWith(file, \"path\", \"Books/SciFi\")",
            "fileInFolder(file, \"Books/SciFi\")",
            "hasEmptyProperty(file, \"note\")",
            "getPropertyValue(file, \"price\", 0) >= 4.5 and not hasProperty(file, \"missing\")");

    private final FilterAnalyzer analyzer = new FilterAnalyzer(); // parity with prod usage shape

    private Map<String, Object> row(int i) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("filePath", "Books/SciFi/gamma-" + i + ".md");
        props.put("status", i % 2 == 0 ? "open" : "closed");
        props.put("tags", List.of("x", "sci-fi"));
        props.put("price", i % 3 == 0 ? 4.5 : "20");
        props.put("note", "");
        return props;
    }

    private List<Map<String, Object>> corpus() {
        var rows = new ArrayList<Map<String, Object>>(CORPUS);
        for (int i = 0; i < CORPUS; i++) {
            rows.add(row(i));
        }
        return rows;
    }

    private List<FilterExpr> plans() {
        var plans = new ArrayList<FilterExpr>();
        for (String expression : BATTERY) {
            var analysis = FilterAnalyzer.analyze(expression);
            if (!analysis.recognized()) {
                throw new IllegalStateException("battery must be recognized: " + expression);
            }
            plans.add(analysis.ast());
        }
        return plans;
    }

    @Test
    void measureNativeVsLua() {
        List<Map<String, Object>> rows = corpus();
        List<FilterExpr> plans = plans();
        LuaBaseInterpreter lua = new LuaBaseInterpreter();

        Runnable luaPass = () -> {
            for (Map<String, Object> row : rows) {
                for (String expression : BATTERY) {
                    try {
                        lua.evaluateExpression(expression, row);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        };
        Runnable javaPass = () -> {
            for (Map<String, Object> row : rows) {
                for (FilterExpr plan : plans) {
                    FilterEval.eval(plan, row);
                }
            }
        };

        double luaMs = bench(luaPass);
        double javaMs = bench(javaPass);
        double ratio = javaMs == 0 ? Double.POSITIVE_INFINITY : luaMs / javaMs;

        System.out.printf("native=%d rows x %d exprs — lua=%.1fms java=%.1fms ratio=%.2fx%n",
                CORPUS, BATTERY.size(), luaMs, javaMs, ratio);

        // sanity only: the native path must be reasonable, not arbitrarily slow
        assertTrue(javaMs < Math.max(2_000.0, luaMs * 5), "java path implausibly slow: " + javaMs);
    }

    private double bench(Runnable pass) {
        for (int i = 0; i < WARMUP; i++) {
            pass.run();
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            pass.run();
            long elapsed = System.nanoTime() - start;
            if (elapsed < best) {
                best = elapsed;
            }
        }
        return best / 1_000_000.0;
    }
}
