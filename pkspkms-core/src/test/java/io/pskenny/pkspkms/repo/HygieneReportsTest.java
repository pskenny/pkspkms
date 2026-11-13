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

package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.PksFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free battery for the hygiene-report assembly (HygieneReports) — the
 * three-section payload semantics without a vault. The McpDispatcher
 * broken_links battery remains the through-SQLite integration seal; this
 * suite pins the clustering/caps/anchor rules directly.
 */
public class HygieneReportsTest {

    private static final HygieneReports.Resolver NULLS = t -> null;

    private static PksFile file(String path, Map<String, Object> props) {
        PksFile f = new PksFile(path, new HashMap<>());
        props.forEach((k, v) -> f.getMutableProperties().put(k, v));
        return f;
    }

    /** Mirrors the repository's batched props path: exact-path lookups. */
    private static HygieneReports.PropsFn propsOf(Map<String, Map<String, Object>> byPath) {
        return paths -> {
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            for (String p : paths) {
                if (byPath.containsKey(p)) {
                    out.put(p, byPath.get(p));
                }
            }
            return out;
        };
    }

    private static Map<String, Object> span(String target, String kind, int line, int offset) {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("target", target);
        span.put("kind", kind);
        span.put("line", line);
        span.put("offset", offset);
        return span;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buckets(Map<String, Object> payload, String key) {
        return (List<Map<String, Object>>) payload.get(key);
    }

    @Test
    @DisplayName("wiki clusters: spelling merge, source list, span dedupe by source+offset")
    void wikiSpellingMerge() {
        List<PksFile> rows = new ArrayList<>();
        rows.add(file("dangly.md", Map.of(
                "danglingLinks", List.of("Ghost", "ghost"),
                "wikilinkSpans", List.of(
                        span("Ghost", "wikilink", 1, 5),
                        span("ghost", "wikilink", 1, 14)))));
        rows.add(file("dangly2.md", Map.of(
                "danglingLinks", List.of("Ghost"),
                "wikilinkSpans", List.of(span("Ghost", "wikilink", 2, 3)))));

        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("dangly.md", rows.get(0).getMutableProperties());
        props.put("dangly2.md", rows.get(1).getMutableProperties());
        Map<String, Object> payload = HygieneReports.compute(rows, "", NULLS, propsOf(props));
        List<Map<String, Object>> targets = buckets(payload, "targets");
        assertEquals(1, targets.size(), targets.toString());
        Map<String, Object> bucket = targets.get(0);
        assertEquals("Ghost", bucket.get("target"), "max-refcount spelling names the bucket");
        assertEquals(3L, bucket.get("refcount"));
        assertEquals(List.of("dangly.md", "dangly2.md"), bucket.get("sources"));
        // same span never repeats per spelling: Ghost@5, ghost@14 (dangly once each
        // per spelling BUT deduped by source+offset — each spelling match hits its
        // own distinct span), Ghost@3
        assertEquals(3, ((List<?>) bucket.get("instances")).size());
    }

    @Test
    @DisplayName("md dead: fragment stripped, scheme skipped, resolvable dest alive")
    void mdDestinations() {
        List<PksFile> rows = new ArrayList<>();
        rows.add(file("x.md", Map.of(
                "linkSpans", List.of(
                        span("https://web.com", "link", 1, 0),
                        span("missing.md", "link", 2, 2),
                        span("phantom.svg", "image", 3, 4),
                        span("hub.md#frag", "link", 4, 6)))));
        rows.add(file("y.md", Map.of(
                "linkSpans", List.of(span("missing.md", "link", 1, 0)))));

        HygieneReports.Resolver resolver = t -> "hub.md".equals(t) ? "hub.md" : null;
        Map<String, Object> payload = HygieneReports.compute(rows, "", resolver, propsOf(Map.of()));
        List<Map<String, Object>> md = buckets(payload, "md_targets");
        assertEquals(2, md.size(), md.toString());
        assertEquals("missing.md", md.get(0).get("target"));
        assertEquals(2L, md.get(0).get("refcount"), "cross-source refcount");
        assertEquals("phantom.svg", md.get(1).get("target"));
        assertEquals(1L, md.get(1).get("refcount"));
        assertEquals(List.of("x.md"), md.get(1).get("sources"));
    }

    @Test
    @DisplayName("anchor rot: verbatim slug match skips; danglers skip; hints ride")
    void anchorRotRules() {
        List<PksFile> rows = new ArrayList<>();
        rows.add(file("t.md", new LinkedHashMap<>()));
        Map<String, Object> t = rows.get(0).getMutableProperties();
        // anchored wiki spans: a WRONG anchor (rots) + a VERBATIM-matching one
        // (never rots) + an unanchored target owned by the wiki section
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("hub", "wikilink", 2, 10));
        Map<String, Object> wrong = new LinkedHashMap<>(span("hub", "wikilink", 3, 20));
        wrong.put("anchor", "Wrong One");
        Map<String, Object> right = new LinkedHashMap<>(span("hub", "wikilink", 4, 40));
        right.put("anchor", "Right One");
        spans.add(wrong);
        spans.add(right);
        t.put("wikilinkSpans", spans);
        t.put("danglingLinks", List.of("Ghost"));

        HygieneReports.Resolver resolver = t2 -> "hub".equals(t2) ? "hub.md" : null;
        HygieneReports.PropsFn props = propsOf(Map.of(
                "hub.md", Map.of("outline", List.of(
                        Map.of("text", "Right One", "level", 1)))));
        Map<String, Object> payload = HygieneReports.compute(rows, "", resolver, props);

        List<Map<String, Object>> rot = buckets(payload, "anchor_rot");
        assertEquals(1, rot.size(), rot.toString());
        assertEquals("Wrong One", rot.get(0).get("anchor"), "only the unmatched anchor rots");
        assertEquals(1L, rot.get(0).get("refcount"));
        assertTrue(((List<?>) rot.get(0).get("headings")).contains("right one"),
                "target outline rides as repair hints");
        // the wiki section owns the dangler; the matched anchor is absent
        List<Map<String, Object>> targets = buckets(payload, "targets");
        assertEquals(1, targets.size());
        assertEquals("Ghost", targets.get(0).get("target"));
    }

    @Test
    @DisplayName("capBuckets: minRefs floor, sort, limit; totals are pre-limit, post-floor")
    void capBucketsArgs() {
        List<Map<String, Object>> wiki = List.of(
                Map.of("target", "aaa", "refcount", 4L, "sources", List.of("a.md"), "instances", List.of()),
                Map.of("target", "bbb", "refcount", 1L, "sources", List.of("b.md"), "instances", List.of()),
                Map.of("target", "ccc", "refcount", 4L, "sources", List.of("c.md"), "instances", List.of()));
        Map<String, Object> compute = new LinkedHashMap<>();
        compute.put("targets", wiki);
        compute.put("md_targets", List.of());
        compute.put("anchor_rot", List.of());

        Map<String, Object> payload = HygieneReports.capBuckets(compute, "refcount", 1, 2);
        List<Map<String, Object>> targets = buckets(payload, "targets");
        assertEquals(1, targets.size(), "limit caps");
        assertEquals("aaa", targets.get(0).get("target"), "4-vs-4 tie settles lexicographic");
        assertEquals(2L, payload.get("total_broken"), "totals are pre-limit, post-floor");

        Map<String, Object> byTarget = HygieneReports.capBuckets(compute, "target", 500, 0);
        assertEquals(3, buckets(byTarget, "targets").size(), "no floor without minRefs");
        assertEquals(3L, byTarget.get("total_broken"));
    }

    @Test
    @DisplayName("aliasScope filters rows before compute")
    void scopeFilter() {
        List<PksFile> rows = new ArrayList<>();
        rows.add(file("Notes/dangly.md", Map.of("danglingLinks", List.of("Ghost"))));
        rows.add(file("@gwern/v.md", Map.of("danglingLinks", List.of("Ghost2"))));

        Map<String, Object> scoped = HygieneReports.compute(rows, "@gwern/", NULLS, propsOf(Map.of()));
        List<Map<String, Object>> targets = buckets(scoped, "targets");
        assertEquals(1, targets.size());
        assertEquals("Ghost2", targets.get(0).get("target"));
    }
}
