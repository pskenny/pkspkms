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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockMaskerTest {

    @Test
    void masksFencedContentLengthPreserving() {
        String content = "before\n```\n[[Fenced]]\n```\nafter";
        String masked = BlockMasker.maskFencedBlocks(content);
        assertEquals(content.length(), masked.length(), "same length preserved");
        assertFalse(masked.contains("[[Fenced]]"), "fenced wikilink blanked");
        assertTrue(masked.contains("before"), "outside fence untouched");
        assertTrue(masked.contains("after"), "outside fence untouched");
    }

    @Test
    void fenceRunsStayVisible() {
        String content = "```\nhidden\n```";
        String masked = BlockMasker.maskFencedBlocks(content);
        assertTrue(masked.startsWith("```"), "opening run unmasked");
        assertTrue(masked.endsWith("```"), "closing run unmasked");
        assertFalse(masked.contains("hidden"));
    }

    @Test
    void infoStringAfterOpenerStaysVisible() {
        String content = "```lua\nx = 1\n```";
        String masked = BlockMasker.maskFencedBlocks(content);
        assertTrue(masked.contains("lua"), "info string must stay (real ```base blocks rely on it)");
        assertFalse(masked.contains("x = 1"), "fence interior masked");
        assertTrue(masked.contains("```"));
    }

    @Test
    void closesOnlyOnSameFenceCharAndLength() {
        String content = "````\n~~~\n[[x]]\n````";
        String masked = BlockMasker.maskFencedBlocks(content);
        assertFalse(masked.contains("[[x]]"), "tilde inside backtick fence stays masked");
        assertEquals(content.length(), masked.length());
    }

    @Test
    void unclosedFenceMasksToEof() {
        String content = "before\n```\n[[Never]]";
        String masked = BlockMasker.maskFencedBlocks(content);
        assertFalse(masked.contains("[[Never]]"));
        assertTrue(masked.contains("before"));
    }

    @Test
    void indentedFenceInsideList() {
        String content = "- item\n  ```\n  [[y]]\n  ```";
        String masked = BlockMasker.maskFencedBlocks(content);
        assertFalse(masked.contains("[[y]]"));
    }

    @Test
    void fourSpaceIndentIsNotAFence() {
        // commonmark: 4-space indent = indented code block, not a fence
        String content = "    ```\n    ignored-anyway\n    ```";
        String masked = BlockMasker.maskFencedBlocks(content);
        assertEquals(content, masked, "no fence state entered; content untouched");
    }

    @Test
    void inlineCodeNotMasked() {
        String content = "run `[[Inline]]` now";
        assertEquals(content, BlockMasker.maskFencedBlocks(content));
    }

    @Test
    void realBaseBlockNotMasked() {
        String content = "```base\nviews:\n  - type: table\n```";
        String masked = BlockMasker.maskFencedBlocks(content);
        assertEquals(content, masked, "base blocks are exempt: YAML stays detectable");
        assertTrue(masked.contains("type: table"));
    }

    @Test
    void luabaseBlockExemptToo() {
        String content = "```luabase\nviews:\n  - type: list\n```";
        assertEquals(content, BlockMasker.maskFencedBlocks(content));
    }

    @Test
    void baseDemoInsideOuterFenceStillMasked() {
        String content = "```md\nHere is an example:\n```base\nviews:\n  - type: table\n```\n```";
        String masked = BlockMasker.maskFencedBlocks(content);
        assertFalse(masked.contains("type: table"), "B15: fenced demo stays blanked");
    }
}
