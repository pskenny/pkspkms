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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownLinkReaderTest {

    private final MarkdownLinkReader reader = new MarkdownLinkReader();

    @Test
    void plainLinksSurvive() {
        List<String> links = reader.getMarkdownLinksProperties("[x](note.md)");
        assertEquals(List.of("note.md"), links);
    }

    @Test
    void angleBracketDestinationWithSpaces() {
        List<String> links = reader.getMarkdownLinksProperties("[x](<My Image.png>)");
        assertEquals(List.of("My Image.png"), links);
    }

    @Test
    void angleBracketDestinationWithParens() {
        List<String> links = reader.getMarkdownLinksProperties("[x](<Meeting (2024).md>)");
        assertEquals(List.of("Meeting (2024).md"), links);
    }

    @Test
    void unbalancedAngleSkipped() {
        List<String> links = reader.getMarkdownLinksProperties("[x](<note.md");
        assertTrue(links.isEmpty(), List.of("no dest extracted").toString());
    }

    @Test
    void htmlMarkerInsideDestSkipped() {
        // malformed: unbalanced `<` lands in the plain path's raw capture
        List<String> links = reader.getMarkdownLinksProperties("[x](<a href)");
        assertTrue(links.isEmpty());
    }

    @Test
    void balancedHtmlWordAngleIsValidLink() {
        // markdown-valid: [x](<a href>) names the destination "a href"
        List<String> links = reader.getMarkdownLinksProperties("[x](<a href>)");
        assertEquals(List.of("a href"), links);
    }

    @Test
    void httpStillSkippedEvenInAngle() {
        List<String> links = reader.getMarkdownLinksProperties("[x](<https://a.b>) [y](https://c.d)");
        assertTrue(links.isEmpty());
    }

    @Test
    void duplicateDestinationsDeduped() {
        List<String> links = reader.getMarkdownLinksProperties("[a](x.md)\n[b](x.md)");
        assertEquals(1, links.size());
    }
}
