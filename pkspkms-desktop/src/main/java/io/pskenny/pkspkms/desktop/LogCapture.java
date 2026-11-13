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

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight tee of {@code System.err} that keeps the most recent non-empty line.
 * Used by the system tray to display the last log event.
 */
public final class LogCapture {
    private final AtomicReference<String> lastLine = new AtomicReference<>("Initialising...");
    private final PrintStream originalErr;

    public LogCapture() {
        this.originalErr = System.err;
        System.setErr(new TeePrintStream(originalErr, lastLine));
    }

    public String getLastLine() {
        return lastLine.get();
    }

    public void restore() {
        System.setErr(originalErr);
    }

    private static final class TeePrintStream extends PrintStream {
        private final AtomicReference<String> lastLine;

        TeePrintStream(OutputStream out, AtomicReference<String> lastLine) {
            super(out, true);
            this.lastLine = lastLine;
        }

        @Override
        public void println(String x) {
            super.println(x);
            if (x != null && !x.isBlank()) {
                // Truncate very long lines for tray display
                String trimmed = x.length() > 60 ? x.substring(0, 57) + "..." : x;
                lastLine.set(trimmed);
            }
        }

        @Override
        public void println(Object x) {
            super.println(x);
            if (x != null) {
                String s = x.toString();
                if (!s.isBlank()) {
                    String trimmed = s.length() > 60 ? s.substring(0, 57) + "..." : s;
                    lastLine.set(trimmed);
                }
            }
        }
    }
}
