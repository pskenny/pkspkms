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

package io.pskenny.pkspkms.io;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogUtilTest {

    @Test
    void stripsCarriageReturn() {
        assertEquals("a b", LogUtil.oneLine("a\rb"));
    }

    @Test
    void stripsLineFeed() {
        assertEquals("a b", LogUtil.oneLine("a\nb"));
    }

    @Test
    void stripsCrlfAndZeroByte() {
        assertEquals("a b c", LogUtil.oneLine("a\r\nb\0c"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", LogUtil.oneLine(null));
    }
}
