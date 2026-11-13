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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v6 FTS body semantics: `plainBody` renders the raw markdown slice to plain
 * text (TextCollectingVisitor) — heading/emphasis markers and link
 * destinations drop; code content, table cells, wikilink inner text and task
 * trailing text stay searchable. Pinned from ACTUAL engine output — this fence
 * is the `text:` contract, not an aspiration.
 */
public class MarkdownProcessorPlainBodyTest {

    @Test
    @DisplayName("markers drop: headings, emphasis, blockquotes, link destinations")
    void markersDrop() {
        String raw = "# Heading One\n\nSome **bold** and *em* text.\n\nLink [label text](dest.md) here.\n\n> quoted line";
        String out = MarkdownProcessor.plainBody(raw);
        assertTrue(out.contains("Heading One"), out);
        assertFalse(out.contains("#"), out);
        assertTrue(out.contains("Some bold and em text."), out);
        assertFalse(out.contains("**"), out);
        assertTrue(out.contains("label text"), out);
        assertFalse(out.contains("(dest.md)"), out);
        assertTrue(out.contains("quoted line"), out);
        assertFalse(out.contains("> quoted"), out);
    }

    @Test
    @DisplayName("code keeps content, drops fence syntax; tables stay verbatim")
    void codeAndTablesStay() {
        String raw = "```rust\nlet x = 42;\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |";
        String out = MarkdownProcessor.plainBody(raw);
        assertTrue(out.contains("let x = 42;"), out);
        assertFalse(out.contains("```"), out);
        assertTrue(out.contains("| a | b |"), out);
        assertTrue(out.contains("| 1 | 2 |"), out);
    }

    @Test
    @DisplayName("tasks keep trailing text; checkbox glyphs drop; wikilink inner stays")
    void tasksAndWikilinks() {
        String raw = "- [ ] task one\n- [x] done two\n\n[[Wiki Target|display]] here";
        String out = MarkdownProcessor.plainBody(raw);
        assertTrue(out.contains("task one"), out);
        assertTrue(out.contains("done two"), out);
        assertFalse(out.contains("- [ ]"), out);
        assertTrue(out.contains("Wiki Target|display"), out);
        // the frontmatter never lands in the body either way
    }

    @Test
    @DisplayName("frontmatter strips; over-cap bodies stay unindexed (null)")
    void frontmatterAndCap() {
        String raw = "---\ntags: x\n---\n# Real Body\ntext under the heading";
        String out = MarkdownProcessor.plainBody(MarkdownProcessor.extractBody(raw));
        assertTrue(out.contains("Real Body"), out);
        assertFalse(out.contains("tags"), out);

        // 1 MB over-cap → null (unindexed), raw and plain agree on the lever
        StringBuilder huge = new StringBuilder("x".repeat(MarkdownProcessor.MAX_BODY_CHARS + 11));
        assertNull(MarkdownProcessor.plainBody(MarkdownProcessor.extractBody(huge.toString())));
    }
}
