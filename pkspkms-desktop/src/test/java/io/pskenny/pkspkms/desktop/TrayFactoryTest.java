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

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TrayFactoryTest {

    @Test
    void supplierThrowingFailsHeadless() {
        TrayManager tray = TrayFactory.createOrFallback(() -> {
            throw new IllegalStateException("no tray on this platform");
        }, 2);
        assertNull(tray, "failing tray construction must continue headless");
    }

    @Test
    void slowConstructionTimesOut() {
        AtomicBoolean finished = new AtomicBoolean(false);
        TrayManager tray = TrayFactory.createOrFallback(() -> {
            try {
                Thread.sleep(5_000);   // simulates the dorkbox subprocess hang
            } catch (InterruptedException ignored) {
            }
            finished.set(true);
            return null;
        }, 1);

        assertNull(tray, "timed-out tray must not block startup");
        // No wall-clock assert: CI runners jitter and steal time, and a GC pause
        // in the test thread inflates elapsed past any fixed bound. The worker
        // can finish no earlier than launch + 5s, the timeout returns at ~1s,
        // so finished=false proves the timeout path without timing.
        assertFalse(finished.get(), "must return near the timeout, not after the worker finishes");
    }

    @Test
    void returnsWhateverSupplierBuilds() {
        // TrayManager construction in a tray-less environment throws (caught -> null);
        // the contract under test is plain delegation: supplier runs to
        // completion, result returned. State assert, not timing — CI clocks drift.
        AtomicBoolean finished = new AtomicBoolean(false);
        TrayManager tray = TrayFactory.createOrFallback(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            finished.set(true);
            return null;
        }, 5);

        assertNull(tray);
        assertTrue(finished.get(), "must return when the builder completes");
    }
}
