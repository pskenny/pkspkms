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

import org.junit.jupiter.api.Test;

import java.util.logging.LogRecord;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokenPipeLogFilterTest {

    private LogRecord recordWith(Throwable thrown) {
        LogRecord r = new LogRecord(Level.SEVERE, "Could not send response to the client");
        r.setThrown(thrown);
        return r;
    }

    @Test
    void brokenPipeSocketExceptionFiltered() {
        var cause = new java.net.SocketException("Broken pipe");
        assertTrue(BrokenPipeLogFilter.isBrokenPipe(recordWith(cause)));
    }

    @Test
    void wrappedBrokenPipeFiltered() {
        var cause = new RuntimeException("wrap", new java.io.IOException("Broken pipe"));
        assertTrue(BrokenPipeLogFilter.isBrokenPipe(recordWith(cause)));
    }

    @Test
    void connectionResetFiltered() {
        var cause = new java.net.SocketException("Connection reset by peer");
        assertTrue(BrokenPipeLogFilter.isBrokenPipe(recordWith(cause)));
    }

    @Test
    void realIoErrorKept() {
        var cause = new java.io.IOException("Disk quota exceeded");
        assertFalse(BrokenPipeLogFilter.isBrokenPipe(recordWith(cause)));
    }

    @Test
    void noThrowableKept() {
        assertFalse(BrokenPipeLogFilter.isBrokenPipe(new LogRecord(Level.SEVERE, "message only")));
    }

    @Test
    void nullMessageKept() {
        var cause = new RuntimeException() {
            @Override
            public String getMessage() {
                return null;
            }
        };
        LogRecord r = new LogRecord(Level.SEVERE, "client gone");
        r.setThrown(cause);
        assertFalse(BrokenPipeLogFilter.isBrokenPipe(r));
    }

    @Test
    void installDoesNotThrow() {
        BrokenPipeLogFilter.install();
        BrokenPipeLogFilter.install(); // idempotent
    }
}
