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

package io.pskenny.pkspkms.io.processor.markdown;

import java.util.List;
import java.util.Map;

/**
 * One pass over a note's markdown → every structured extraction the loader
 * persists. Flexmark is the spec-grammar implementation (CommonMark AST +
 * official wikilink/task-list extensions); the legacy regex stack survives
 * as the parity oracle in tests.
 *
 * Contracts (loader-facing, byte-identical across implementations):
 * - {@code links}: markdown link/image URL targets, document order,
 *   first-seen dedup; http(s) targets skipped
 * - {@code wikilinks}: raw {@code [[…]]} inner text incl. alias/heading
 *   parts, document order, first-seen dedup
 * - {@code tasks}/{@code tasksDone}: trimmed checkbox texts, dedup, 200 cap
 * - {@code bases}/{@code luabases}: fenced block YAML contents, trimmed
 * - {@code outline}: per-heading {@code [{text, level}]} in document order
 *   (markers trimmed, level from the ATX depth); empty when the note has no
 *   headings
 * - {@code spans}: per-wikilink {target, offset, kind} — parse-time facts
 *   for backlink provenance (no query-time re-scan)
 */
public interface MarkdownExtractor {

    Extraction extract(String content);

    record Extraction(List<String> links,
                      List<String> wikilinks,
                      List<String> tasksOpen,
                      List<String> tasksDone,
                      List<String> bases,
                      List<String> luabases,
                      List<Map<String, Object>> outline,
                      List<Map<String, Object>> spans,
                      List<Map<String, Object>> linkSpans,
                      List<Map<String, Object>> taskSpans,
                      List<Map<String, Object>> embedSpans) {}
}
