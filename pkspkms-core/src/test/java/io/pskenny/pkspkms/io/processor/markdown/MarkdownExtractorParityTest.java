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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G1 parity gate: the commonmark extractor reproduces the legacy regex stack's
 * contracts byte-for-byte on a representative corpus; the whitelisted
 * bug-fix classes are asserted positively (reference links appear, inline
 * code ghosts vanish, titles de-leak, task semantics upgrade). Oracle =
 * LegacyMarkdownExtractor (the shipped regex stack).
 */
public class MarkdownExtractorParityTest {

    private final LegacyMarkdownExtractor oracle = new LegacyMarkdownExtractor();
    private final FlexmarkMarkdownExtractor flex = new FlexmarkMarkdownExtractor();

    // fixture corpus: parity-relevant shapes only — intentional-behavior
    // differences live in their own tests below
    private static final List<String> CORPUS = List.of(
            // plain links + images + http skip + first-seen dedup
            """
                    ![test2](test2.svg)
                    [test1](test1.md)
                    [dup to same](test1.md)
                    [web](https://example.com)
                    """,
            // empty-body with relative link
            "[p](para.md)",
            // wikilinks: raw inner with alias/heading/case, embeds
            """
                    [[hub]]
                    [[hub|alias last]]
                    [[hub#head|with alias]]
                    ![[embed.png]]
                    """,
            // tasks: open/done case variants, indented lists, star/plus markers
            """
                    - [ ] real task
                    - [x] done task
                    - [X] done capital
                    * [ ] star task
                      + [ ] indented plus task
                    no heading
                    """,
            // bases + luabase YAML (exempt interiors: link scanners run inside)
            """
                    ```base
                    filters:
                      and: [Tags.x]
                    ```
                    ```luabase
                    properties = {}
                    ```""",
            // fenced code opaque both engines (demo link/task inside)
            """
                    intro
                    ```markdown
                    [demo](demo.md)
                    [[demo wiki]]
                    - [ ] demo task
                    ```
                    tail
                    """,
            // heading outline (spec-grammar-only key, shapes don't affect legacy keys)
            """
                    # First
                    ## Second
                    ###   Third with trailing spaces
                    body
                    """,
            // empty content
            "",
            // CRLF
            "- [ ] crlf task\r\n[crlf link](crlf.md)\r\n",
            // unclosed fence: masked legacy + opaque commonmark
            "[hidden](still-hidden.md)\n```open\n[namespaced](namespaced.md)");

    @Test
    @DisplayName("byte-parity: regex stack vs commonmark over the corpus")
    void parityAcrossCorpus() {
        for (int i = 0; i < CORPUS.size(); i++) {
            String content = CORPUS.get(i);
            MarkdownExtractor.Extraction expected = oracle.extract(content);
            MarkdownExtractor.Extraction actual = flex.extract(content);
            assertEquals(expected.links(), actual.links(), "links @ corpus[" + i + "]");
            assertEquals(expected.wikilinks(), actual.wikilinks(), "wikilinks @ corpus[" + i + "]");
            assertEquals(expected.tasksOpen(), actual.tasksOpen(), "tasksOpen @ corpus[" + i + "]");
            assertEquals(expected.tasksDone(), actual.tasksDone(), "tasksDone @ corpus[" + i + "]");
            assertEquals(expected.bases(), actual.bases(), "bases @ corpus[" + i + "]");
            assertEquals(expected.luabases(), actual.luabases(), "luabases @ corpus[" + i + "]");
        }
    }

    // --- whitelisted bug-fix classes: spec-grammar-only behavior, asserted positively ---

    private static final String CODE_SPAN = """
            keep `[[ghost in code]]` and `- [ ] ghost task in code` inline
            text link [fine](fine.md) does extract
            """;

    @Test
    @DisplayName("inline code spans can no longer fake links or tasks")
    void inlineCodeExcluded() {
        var out = flex.extract(CODE_SPAN);
        assertTrue(out.wikilinks().isEmpty(), out.wikilinks().toString());
        assertTrue(out.tasksOpen().isEmpty() && out.tasksDone().isEmpty());
        assertEquals(List.of("fine.md"), out.links());
    }

    // --- accepted flexmark deviations: no wrong data, an entry just doesn't extract ---

    @Test
    @DisplayName("angle destinations extract nothing (accepted deviation)")
    void angleDestinationsNotExtracted() {
        var out = flex.extract("[b](<right angle.png>)");
        assertTrue(out.links().isEmpty(), out.links().toString());
    }

    private static final String REFERENCE_STYLE = """
            see [Java 17][ref] and [bare][]

            [ref]: notes/java17.md
            [bare]: notes/bare.md
            """;

    @Test
    @DisplayName("reference-style links extract nothing (accepted deviation)")
    void referenceLinksNotExtracted() {
        var out = flex.extract(REFERENCE_STYLE);
        assertTrue(out.links().isEmpty(), out.links().toString());
    }

    @Test
    @DisplayName("quoted destinations extract nothing (accepted deviation)")
    void quotedDestinationsNotExtracted() {
        var out = flex.extract("[b](\"right angle.png\")");
        assertTrue(out.links().isEmpty(), out.links().toString());
    }

    @Test
    @DisplayName("titles no longer leak into link targets")
    void titlesDropped() {
        var out = flex.extract("[a](note.md \"title drops\")");
        assertEquals(List.of("note.md"), out.links(), out.links().toString());
    }

    @Test
    @DisplayName("indented code is opaque — no more link scans inside it")
    void indentedCodeExcluded() {
        var out = flex.extract("""
                body line

                    [x](indented-code.md)

                tail
                """);
        assertTrue(out.links().isEmpty(), out.links().toString());
        // sanity: the flexible engine still walks the rest of the doc
        var mixed = flex.extract("head link [f](fine.md)\n\n    [g](indented.md)\n");
        assertEquals(List.of("fine.md"), mixed.links());
    }

    @Test
    @DisplayName("quoted tasks extract (blockquote list items are real items)")
    void quotedTaskExtracts() {
        var out = flex.extract("> - [ ] quoted task");
        assertEquals(List.of("quoted task"), out.tasksOpen());
    }

    @Test
    @DisplayName("ordered tasks extract (task-list semantics)")
    void orderedTaskExtracts() {
        var out = flex.extract("1. [ ] ordered task");
        assertEquals(List.of("ordered task"), out.tasksOpen());
    }

    @Test
    @DisplayName("link spans are parse-time facts with stable offsets")
    void spansAreParseTime() {
        String content = "intro line\n[[hub]] and [[HubAlias]]";
        var out = flex.extract(content);
        assertEquals(2, out.spans().size());
        Map<String, Object> first = out.spans().get(0);
        assertEquals("hub", first.get("target"));
        int firstOffset = Integer.parseInt(String.valueOf(first.get("offset")));
        int secondOffset = Integer.parseInt(String.valueOf(out.spans().get(1).get("offset")));
        assertTrue(firstOffset > 0);
        assertTrue(secondOffset > firstOffset);
    }

    @Test
    @DisplayName("v5 span facts: anchor + display split off, key omitted when absent")
    void spanFactsCarryAnchorAndDisplay() {
        String content = "intro\n[[Hub Page#Heading One|label]]\nplain [[hub]] here";
        var out = flex.extract(content);
        assertEquals(2, out.spans().size());
        Map<String, Object> rich = out.spans().get(0);
        assertEquals("Hub Page", rich.get("target"));
        assertEquals("Heading One", rich.get("anchor"));
        assertEquals("label", rich.get("display"));
        Map<String, Object> plain = out.spans().get(1);
        assertEquals("hub", plain.get("target"));
        assertFalse(plain.containsKey("anchor"), "anchor key omitted when absent");
        assertFalse(plain.containsKey("display"));
    }

    @Test
    @DisplayName("v6: task spans carry {text, open, line, offset}; embed spans the view fences")
    void taskAndEmbedSpans() {
        String content = "# Title\n\n- [ ] do thing\n- [x] done thing\n\n```base\nfilters:\n```";
        var out = flex.extract(content);
        assertEquals(2, out.taskSpans().size(), out.taskSpans().toString());
        Map<String, Object> openSpan = out.taskSpans().get(0);
        assertEquals("do thing", openSpan.get("text"));
        assertEquals(Boolean.TRUE, openSpan.get("open"));
        assertEquals(3, Integer.parseInt(String.valueOf(openSpan.get("line"))));
        Map<String, Object> doneSpan = out.taskSpans().get(1);
        assertEquals(Boolean.FALSE, doneSpan.get("open"));
        assertTrue(Integer.parseInt(String.valueOf(doneSpan.get("offset")))
                > Integer.parseInt(String.valueOf(openSpan.get("offset"))));
        // embeds: the exempt fence spans on at the right line (body-relative),
        // kind rides the info string
        assertEquals(1, out.embedSpans().size(), out.embedSpans().toString());
        Map<String, Object> embedSpan = out.embedSpans().get(0);
        assertEquals("base", embedSpan.get("kind"));
        assertEquals(6, Integer.parseInt(String.valueOf(embedSpan.get("line"))));
        // tasks list contract unchanged (same counts, same texts, first-seen)
        assertEquals(List.of("do thing"), out.tasksOpen());
        assertEquals(List.of("done thing"), out.tasksDone());
    }

    @Test
    @DisplayName("outline lists leveled heading entries in document order, markers trimmed")
    void outlineCollected() {
        var out = flex.extract(CORPUS.get(6));
        assertEquals(List.of(
                Map.of("text", "First", "level", 1),
                Map.of("text", "Second", "level", 2),
                Map.of("text", "Third with trailing spaces", "level", 3)), out.outline());
    }

    @Test
    @DisplayName("parallel parse smoke: shared Parser instance, identical extractions")
    void parallelParseSmoke() {
        String content = CORPUS.get(0) + CORPUS.get(3);
        var expected = flex.extract(content);
        final int threads = 8;
        var latch = new java.util.concurrent.CountDownLatch(threads);
        var errors = new java.util.ArrayList<Throwable>();
        Runnable check = () -> {
            try {
                var again = flex.extract(content);
                if (!again.links().equals(expected.links())
                        || !again.wikilinks().equals(expected.wikilinks())
                        || !again.spans().equals(expected.spans())) {
                    errors.add(new AssertionError("parallel drift"));
                }
            } finally {
                latch.countDown();
            }
        };
        for (int i = 0; i < threads; i++) {
            new Thread(check).start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }
}
