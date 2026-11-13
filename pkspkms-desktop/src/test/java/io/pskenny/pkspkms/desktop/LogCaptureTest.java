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

package io.pskenny.pkspkms.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LogCaptureTest {

    @Test
    @DisplayName("LogCapture captures the last printed line")
    void testCapturesLastLine() {
        LogCapture capture = new LogCapture();
        try {
            System.err.println("First line");
            System.err.println("Second line");
            assertEquals("Second line", capture.getLastLine());
        } finally {
            capture.restore();
        }
    }

    @Test
    @DisplayName("LogCapture truncates very long lines")
    void testTruncatesLongLines() {
        LogCapture capture = new LogCapture();
        try {
            String longLine = "A".repeat(100);
            System.err.println(longLine);
            String result = capture.getLastLine();
            assertTrue(result.endsWith("..."), "Long line should be truncated");
            assertEquals(60, result.length(), "Truncated length should be 60");
        } finally {
            capture.restore();
        }
    }
}
