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
