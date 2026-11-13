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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hygiene-report assembly as a pure function (the {@link LinkPlanner}
 * pattern): vault rows + two accessors in, the three-section broken-links
 * payload out. No JDBC, no filesystem; the repository owns the sweep, the
 * result cache and the resolve ladder.
 *
 * Sections (behavior pinned by the McpDispatcher battery, computed here):
 * <ul>
 *   <li>{@code targets} — dangling wikilinks clustered by normalized spelling
 *       (near-miss merge, max-refcount bucket naming, per-occurrence span
 *       facts deduped by source+offset, ≤ 50/bucket, ≤ 10 sources);</li>
 *   <li>{@code md_targets} — markdown destinations resolved through the same
 *       path/name ladder (assets included), bucketed "as written" sans
 *       fragment; protocol targets skipped;</li>
 *   <li>{@code anchor_rot} — {@code [[note#anchor]]} with existing note and
 *       no heading slug (lowercase + collapsed whitespace), up to 8 target
 *       heading hints per bucket; dangling targets skip (the wiki section
 *       owns them).</li>
 * </ul>
 * All three sorted/capped only by {@link #capBuckets} — the repository may
 * cache {@link #compute} across calls and re-apply per-argument limits.
 */
public final class HygieneReports {

    /** Path/name resolve ladder (dependency-injected; null = unresolvable). */
    public interface Resolver {
        String resolve(String target);
    }

    /** Batched properties for exact paths (dependency-injected). */
    public interface PropsFn {
        Map<String, Map<String, Object>> byPaths(List<String> paths);
    }

    /** Deeper caps: instances ≤ 50/bucket, sources listed ≤ 10. */
    static final int MAX_INSTANCES = 50;
    static final int MAX_SOURCES = 10;

    private HygieneReports() {}

    public static Map<String, Object> compute(List<PksFile> rows, String aliasScope,
                                              Resolver resolver, PropsFn propsFn) {
        // Phase 1: raw dangling targets -> referrer sources; markdown
        // destinations and anchor-carrying wiki spans ride the same pass
        Map<String, Map<String, Boolean>> rawRefs = new LinkedHashMap<>();
        List<String> referrerPaths = new ArrayList<>();
        Map<String, Map<String, Boolean>> mdRefs = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> mdSpansByDest = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> anchoredSpansByTarget = new LinkedHashMap<>();
        for (PksFile row : rows) {
            String source = row.getFilePath();
            boolean markdownFile = source.toLowerCase(java.util.Locale.ROOT).endsWith(".md");
            if (!aliasScope.isEmpty() && !source.startsWith(aliasScope)) {
                continue;
            }
            for (Object raw : row.getAsList("danglingLinks")) {
                if (raw != null) {
                    Map<String, Boolean> sources = rawRefs.computeIfAbsent(
                            raw.toString(), k -> new LinkedHashMap<>());
                    if (!sources.containsKey(source)) {
                        sources.put(source, Boolean.TRUE);
                        referrerPaths.add(source);
                    }
                }
            }
            if (!markdownFile) {
                continue;
            }
            for (Object spanObj : row.getAsList("linkSpans")) {
                if (!(spanObj instanceof Map<?, ?> span)) {
                    continue;
                }
                String dest = String.valueOf(span.get("target"));
                int fragment = dest.indexOf('#');
                String clean = fragment >= 0 ? dest.substring(0, fragment) : dest;
                if (clean.isBlank()) {
                    continue;
                }
                String lower = clean.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("http://") || lower.startsWith("https://")
                        || lower.startsWith("mailto:") || lower.startsWith("pkspkms://")) {
                    continue; // external/protocol targets are out of scope
                }
                String bucketKey = clean; // kept as written, sans fragment
                Map<String, Boolean> sources = mdRefs.computeIfAbsent(
                        bucketKey, k -> new LinkedHashMap<>());
                Map<String, Object> spanFact = new LinkedHashMap<>();
                spanFact.put("source", source);
                spanFact.put("line", span.get("line"));
                spanFact.put("offset", span.get("offset"));
                spanFact.put("kind", span.get("kind"));
                mdSpansByDest.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(spanFact);
                sources.put(source, Boolean.TRUE);
            }
            for (Object spanObj : row.getAsList("wikilinkSpans")) {
                if (!(spanObj instanceof Map<?, ?> span) || !span.containsKey("anchor")) {
                    continue;
                }
                String target = String.valueOf(span.get("target")).strip();
                if (target.isBlank()) {
                    continue;
                }
                anchoredSpansByTarget.computeIfAbsent(
                        target, k -> new ArrayList<>()).add(spanRow(source, span));
            }
        }

        // Phase 1b: batched wiki-span provenance per referrer (parse-time
        // facts; inline/fenced code spans excluded by construction)
        Map<String, List<Map<String, Object>>> spansByPath = new LinkedHashMap<>();
        for (var entry : propsFn.byPaths(referrerPaths).entrySet()) {
            if (entry.getValue().get("wikilinkSpans") instanceof List<?> spansList) {
                List<Map<String, Object>> spans = new ArrayList<>();
                for (Object spanObj : spansList) {
                    if (spanObj instanceof Map<?, ?> span) {
                        spans.add(spanRow(null, span));
                    }
                }
                spansByPath.put(entry.getKey(), spans);
            }
        }

        // Phase 2: near-miss clustering — normalized (lowercase+trim) key,
        // bucket named by the max-refcount spelling; instances carry the
        // parse-time {source, line, offset} facts per occurrence
        Map<String, List<Map.Entry<String, Map<String, Boolean>>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Boolean>> raw : rawRefs.entrySet()) {
            groups.computeIfAbsent(norm(raw.getKey()), k -> new ArrayList<>()).add(raw);
        }

        Map<String, Map<String, List<Map<String, Object>>>> instancesByNorm = new LinkedHashMap<>();
        // dedupe key scoped PER NORM (not per raw): several raw spellings can
        // normalize to the same key, and the same span fact must not repeat
        // per spelling (the old per-raw set double-counted Ghost@o for both
        // spellings — found by the HygieneReportsTest battery)
        Map<String, java.util.Set<String>> spanSeenByNorm = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Boolean>> raw : rawRefs.entrySet()) {
            String norm = norm(raw.getKey());
            Map<String, List<Map<String, Object>>> perSource = instancesByNorm
                    .computeIfAbsent(norm, k -> new LinkedHashMap<>());
            java.util.Set<String> spanSeen = spanSeenByNorm
                    .computeIfAbsent(norm, k -> new java.util.LinkedHashSet<>());
            for (String source : raw.getValue().keySet()) {
                for (Map<String, Object> span : spansByPath.getOrDefault(source, List.of())) {
                    String spanTarget = String.valueOf(span.get("target")).strip().toLowerCase(java.util.Locale.ROOT);
                    if (!spanTarget.equals(norm)) {
                        continue; // referrer may have several dangling targets
                    }
                    String spanKey = source + "@" + String.valueOf(span.get("offset"));
                    if (!spanSeen.add(spanKey)) {
                        continue;
                    }
                    perSource.computeIfAbsent(source, k -> new ArrayList<>()).add(span);
                }
            }
        }

        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<String, Map<String, Boolean>>>> groupEntry : groups.entrySet()) {
            String norm = groupEntry.getKey();
            String bestRaw = null;
            int bestRefs = -1;
            long refcount = 0;
            java.util.TreeSet<String> sources = new java.util.TreeSet<>();
            for (Map.Entry<String, Map<String, Boolean>> raw : groupEntry.getValue()) {
                int refs = raw.getValue().size();
                refcount += refs;
                if (refs > bestRefs || (refs == bestRefs && bestRaw != null
                        && raw.getKey().compareTo(bestRaw) < 0)) {
                    bestRefs = refs;
                    bestRaw = raw.getKey();
                }
                sources.addAll(raw.getValue().keySet());
            }
            var bucket = new LinkedHashMap<String, Object>();
            bucket.put("target", bestRaw);
            bucket.put("refcount", refcount);
            java.util.List<String> capped = new java.util.ArrayList<>(sources);
            boolean truncated = capped.size() > MAX_SOURCES;
            if (truncated) {
                capped = capped.subList(0, MAX_SOURCES);
            }
            bucket.put("sources", capped);
            Map<String, List<Map<String, Object>>> spanSources =
                    instancesByNorm.getOrDefault(norm, Map.of());
            long spanTotal = spanSources.values().stream().mapToInt(List::size).sum();
            List<Map<String, Object>> instances = new ArrayList<>();
            for (List<Map<String, Object>> perSource : spanSources.values()) {
                for (Map<String, Object> span : perSource) {
                    if (instances.size() >= MAX_INSTANCES) {
                        break;
                    }
                    instances.add(span);
                }
                if (instances.size() >= MAX_INSTANCES) {
                    break;
                }
            }
            bucket.put("instances", instances);
            if (spanTotal > instances.size()) {
                bucket.put("instances truncated", spanTotal + " span facts, showing " + instances.size());
            }
            if (truncated) {
                bucket.put("sources truncated", sources.size() + " sources, showing " + MAX_SOURCES);
            }
            targets.add(bucket);
        }

        // --- dead-markdown destinations ---
        List<Map<String, Object>> mdTargets = mdDeadBuckets(mdRefs, mdSpansByDest, resolver);

        // --- anchor rot (target resolves, anchor slug misses) ---
        List<Map<String, Object>> anchorRot = anchorRotBuckets(
                anchoredSpansByTarget, rawRefs, resolver, propsFn);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targets", targets);
        payload.put("md_targets", mdTargets);
        payload.put("anchor_rot", anchorRot);
        return payload;
    }

    /**
     * Destinations resolve through the same path/name ladder as wikilinks
     * (assets included — the FILES name index is extension-blind); only the
     * unresolvable remainder is rot.
     */
    private static List<Map<String, Object>> mdDeadBuckets(
            Map<String, Map<String, Boolean>> mdRefs,
            Map<String, List<Map<String, Object>>> mdSpansByDest,
            Resolver resolver) {
        List<Map<String, Object>> dead = new ArrayList<>();
        for (Map.Entry<String, Map<String, Boolean>> dest : mdRefs.entrySet()) {
            String probe = dest.getKey().strip();
            if (resolver.resolve(probe) != null) {
                continue; // resolvable — not rot
            }
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("target", probe);
            bucket.put("refcount", (long) dest.getValue().size());
            java.util.TreeSet<String> sources = new java.util.TreeSet<>(dest.getValue().keySet());
            List<String> capped = new ArrayList<>(sources);
            if (capped.size() > MAX_SOURCES) {
                bucket.put("sources truncated", sources.size() + " sources, showing " + MAX_SOURCES);
                capped = capped.subList(0, MAX_SOURCES);
            }
            bucket.put("sources", capped);
            List<Map<String, Object>> instances = mdSpansByDest
                    .getOrDefault(probe, List.of());
            if (instances.size() > MAX_INSTANCES) {
                bucket.put("instances truncated", instances.size() + " span facts, showing " + MAX_INSTANCES);
                instances = new ArrayList<>(instances.subList(0, MAX_INSTANCES));
            }
            bucket.put("instances", instances);
            dead.add(bucket);
        }
        return dead;
    }

    /**
     * Anchor rot: target exists (danglers belong to the wiki section), no
     * heading slugifies to the anchor. Slug = lowercase + collapsed whitespace
     * (Obsidian matches headings near-verbatim); ≤ 8 target hints ride each bucket.
     */
    private static List<Map<String, Object>> anchorRotBuckets(
            Map<String, List<Map<String, Object>>> anchoredSpansByTarget,
            Map<String, Map<String, Boolean>> rawRefs,
            Resolver resolver, PropsFn propsFn) {
        List<Map<String, Object>> rot = new ArrayList<>();
        if (anchoredSpansByTarget.isEmpty()) {
            return rot;
        }
        java.util.Set<String> normDangling = new java.util.HashSet<>();
        for (String raw : rawRefs.keySet()) {
            normDangling.add(norm(raw));
        }
        Map<String, String> resolvedTargets = new LinkedHashMap<>();
        for (String target : anchoredSpansByTarget.keySet()) {
            if (normDangling.contains(norm(target))) {
                continue;
            }
            String resolved = resolver.resolve(target);
            if (resolved == null) {
                continue;
            }
            resolvedTargets.put(target, resolved);
        }
        Map<String, Map<String, Object>> targetProps = propsFn.byPaths(
                new ArrayList<>(new java.util.LinkedHashSet<>(resolvedTargets.values())));
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : anchoredSpansByTarget.entrySet()) {
            String target = entry.getKey();
            String resolved = resolvedTargets.get(target);
            if (resolved == null) {
                continue;
            }
            for (Map<String, Object> span : entry.getValue()) {
                String anchor = String.valueOf(span.get("anchor"));
                String bucketKey = target + "#" + anchor;
                Map<String, Object> bucket = buckets.computeIfAbsent(bucketKey, k -> {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("target", target);
                    b.put("anchor", anchor);
                    b.put("refcount", 0L);
                    b.put("sources", new java.util.LinkedHashSet<String>());
                    b.put("instances", new ArrayList<Map<String, Object>>());
                    return b;
                });
                bucket.put("refcount", (Long) bucket.get("refcount") + 1);
                @SuppressWarnings("unchecked")
                java.util.Set<String> sources = (java.util.Set<String>) bucket.get("sources");
                sources.add(String.valueOf(span.get("source")));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> instances = (List<Map<String, Object>>) bucket.get("instances");
                if (instances.size() < MAX_INSTANCES) {
                    instances.add(span);
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> entry : buckets.entrySet()) {
            Map<String, Object> bucket = entry.getValue();
            long refcount = (Long) bucket.remove("refcount");
            @SuppressWarnings("unchecked")
            java.util.Set<String> rawSources = (java.util.Set<String>) bucket.remove("sources");
            // the anchor actually matching a heading → not rot
            String target = String.valueOf(bucket.get("target"));
            Object outlineVal = targetProps
                    .getOrDefault(resolvedTargets.get(target), Map.of())
                    .get("outline");
            boolean matched = outlineVal instanceof List<?> items && items.stream().anyMatch(item ->
                    slug(String.valueOf(((java.util.Map<?, ?>) item).get("text")))
                            .equalsIgnoreCase(slug(String.valueOf(bucket.get("anchor")))));
            if (matched) {
                continue;
            }
            bucket.put("refcount", refcount);
            List<String> sources = new ArrayList<>(rawSources);
            if (sources.size() > MAX_SOURCES) {
                bucket.put("sources truncated", rawSources.size() + " sources, showing " + MAX_SOURCES);
                sources = sources.subList(0, MAX_SOURCES);
            }
            bucket.put("sources", sources);
            if (outlineVal instanceof List<?> items && !items.isEmpty()) {
                bucket.put("headings", items.stream()
                        .limit(8)
                        .map(item -> slug(String.valueOf(((java.util.Map<?, ?>) item).get("text"))))
                        .distinct()
                        .toList());
            }
            rot.add(bucket);
        }
        return rot;
    }

    /**
     * Per-argument pass over a cached (or fresh) compute: refcount floor,
     * sort, limit — applied per section. Totals count the FLOORED list
     * (post-floor, pre-limit — the broken_links contract the McpDispatcher
     * battery pins).
     */
    public static Map<String, Object> capBuckets(Map<String, Object> compute,
                                                 String sort, int limit, int minRefs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("targets", "total_broken");
        sections.put("md_targets", "total_md");
        sections.put("anchor_rot", "total_anchor");
        for (Map.Entry<String, String> section : sections.entrySet()) {
            List<Map<String, Object>> floored = new ArrayList<>();
            for (Object bucketObj : (List<?>) compute.get(section.getKey())) {
                Map<String, Object> b = bucket(bucketObj);
                if (((Long) b.get("refcount")) >= minRefs) {
                    floored.add(b);
                }
            }
            payload.put(section.getValue(), (long) floored.size());
            floored.sort(bucketComparator(sort));
            if (floored.size() > Math.max(0, limit)) {
                floored = new ArrayList<>(floored.subList(0, Math.max(0, limit)));
            }
            payload.put(section.getKey(), floored);
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bucket(Object bucket) {
        return (Map<String, Object>) bucket;
    }

    private static java.util.Comparator<Map<String, Object>> bucketComparator(String sort) {
        String mode = sort == null || sort.isEmpty() ? "refcount" : sort;
        return switch (mode) {
            case "target" -> java.util.Comparator.<Map<String, Object>, String>comparing(
                    HygieneReports::bucketTarget, String.CASE_INSENSITIVE_ORDER);
            case "source" -> java.util.Comparator.<Map<String, Object>, String>comparing(
                    HygieneReports::bucketFirstSource);
            case "refcount" -> java.util.Comparator
                    .<Map<String, Object>>comparingLong(bucket -> (Long) bucket.get("refcount"))
                    .reversed()
                    .thenComparing(HygieneReports::bucketTarget, String.CASE_INSENSITIVE_ORDER);
            default -> throw new IllegalArgumentException("Bad sort: " + sort + " (refcount|target|source)");
        };
    }

    private static String bucketTarget(Map<String, Object> bucket) {
        return String.valueOf(bucket.get("target"));
    }

    private static String bucketFirstSource(Map<String, Object> bucket) {
        Object sources = bucket.get("sources");
        return sources instanceof List<?> list && !list.isEmpty()
                ? String.valueOf(list.get(0)) : "";
    }

    private static Map<String, Object> spanRow(String source, Map<?, ?> span) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            copy.put("source", source);
        }
        for (Map.Entry<?, ?> spanEntry : span.entrySet()) {
            copy.put(String.valueOf(spanEntry.getKey()), spanEntry.getValue());
        }
        return copy;
    }

    private static String norm(String value) {
        return value == null ? null : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** lowercase + collapsed whitespace — the heading-anchor match rule. */
    private static String slug(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).strip().replaceAll("\\s+", " ");
    }
}
