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

// Log hygiene (BUGS.md B24): filesystem- and user-derived values reach log
// lines unscrubbed — a CR/LF in a note title or path can forge entries. Every
// log call that interpolates such values wraps them through oneLine.
public final class LogUtil {

    private LogUtil() {}

    public static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", " ")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\0", " ");
    }
}
