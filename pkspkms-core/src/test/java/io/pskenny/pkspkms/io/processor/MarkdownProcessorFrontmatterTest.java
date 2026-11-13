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

package io.pskenny.pkspkms.io.processor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Frontmatter-boundary + over-cap span pins (BUGS.md H4/H3).
 *
 * The body slice must end at the CLOSING dash LINE — a `---` inside a YAML
 * value (dashy text or an indented block scalar) never closes the region,
 * and `----` is not a frontmatter opener at all (matches the YAML reader's
 * `^---\s*$` grammar). Over-cap notes (> 1 MB body) extract from the full
 * content, so span offsets must NOT be shifted a second time.
 */
public class MarkdownProcessorFrontmatterTest {

    @Test
    void dashyFrontmatterValueDoesNotCloseTheRegion() {
        String content = "---\ndescription: \"see A---B\"\ntags: [x]\n---\n# Body\nText\n";
        assertEquals("# Body\nText\n", MarkdownProcessor.extractBody(content),
                "the body starts after the closing dash line, not inside a value");
    }

    @Test
    void indentedDashesInsideBlockScalarDoNotCloseTheRegion() {
        String content = "---\ntext: |\n  para\n  ---\n  more\n---\nBody line\n";
        assertEquals("Body line\n", MarkdownProcessor.extractBody(content),
                "an indented --- inside a block scalar is YAML content, not a closer");
    }

    @Test
    void fourDashesAreNotFrontmatter() {
        String content = "----\n# not frontmatter\nBody\n";
        assertEquals(content, MarkdownProcessor.extractBody(content),
                "the whole document stays the body (---- does not open frontmatter)");
    }

    @Test
    void unterminatedFrontmatterStaysBody() {
        String content = "---\nfoo: bar\n";
        assertEquals(content, MarkdownProcessor.extractBody(content));
    }

    @Test
    void crlfFrontmatter() {
        String content = "---\r\ndesc: A\r\n---\r\nBody CRLF\r\n";
        assertEquals("Body CRLF\r\n", MarkdownProcessor.extractBody(content));
    }

    @Test
    void closerWithTrailingSpaces() {
        String content = "---\ndesc: x\n---   \nBody\n";
        assertEquals("Body\n", MarkdownProcessor.extractBody(content));
    }

    // H3: over-cap body -> body==null -> extraction ran on FULL content; the
    // span adjuster must not add content.length() to offsets (they are
    // already full-content coordinates).
    @Test
    void overCapSpansKeepContentCoordinates() {
        String filler = "x".repeat(MarkdownProcessor.MAX_BODY_CHARS + 500);
        String content = "---\ntags: [big]\n---\n" + filler + "\n[[late-link]]\n";
        var extraction = new io.pskenny.pkspkms.io.processor.markdown.FlexmarkMarkdownExtractor().extract(content);
        List<Map<String, Object>> raw = extraction.spans();
        assertEquals(1, raw.size(), raw.toString());
        int rawOffset = Integer.parseInt(String.valueOf(raw.get(0).get("offset")));

        List<Object> adjusted = MarkdownProcessor.adjustSpans(raw, content, null);
        int adjOffset = Integer.parseInt(String.valueOf(((Map<?, ?>) adjusted.get(0)).get("offset")));
        assertEquals(rawOffset, adjOffset, "full-content spans must ride unchanged when the body was capped");
        assertEquals(content.indexOf("[[late-link]]"), adjOffset,
                "offset points at the wikilink inside the real content");
    }
}
