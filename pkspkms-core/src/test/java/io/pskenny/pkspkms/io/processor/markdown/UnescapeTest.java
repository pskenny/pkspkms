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

class UnescapeTest {

    @Test
    void windowsPathsSurvive() {
        assertEquals("C:\\Users\\pk\\notes", Unescape.markdownPunctuation("C:\\Users\\pk\\notes"));
    }

    @Test
    void nonEscapableEscapesSurvive() {
        assertEquals("\\n\\s\\y", Unescape.markdownPunctuation("\\n\\s\\y"));
    }

    @Test
    void markdownEscapesRemoved() {
        assertEquals("*not italic*", Unescape.markdownPunctuation("\\*not italic\\*"));
        assertEquals("a_b", Unescape.markdownPunctuation("a\\_b"));
        assertEquals("[link]", Unescape.markdownPunctuation("\\[link\\]"));
    }

    @Test
    void escapedBackslashCollapses() {
        assertEquals("\\", Unescape.markdownPunctuation("\\\\"));
    }

    @Test
    void noBackslashesUntouched() {
        assertEquals("plain text", Unescape.markdownPunctuation("plain text"));
    }

    @Test
    void nullSafe() {
        assertEquals(null, Unescape.markdownPunctuation(null));
    }
}
