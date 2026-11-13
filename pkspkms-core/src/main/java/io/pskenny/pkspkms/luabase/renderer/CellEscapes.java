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

package io.pskenny.pkspkms.luabase.renderer;

// Markdown-cell hygiene (BUGS.md B17): a computed value containing `|` or a
// newline corrupts the emitted table — escaped or flattened before rendering.
// Nulls render empty instead of the string "null".
//
// Cell-surface cap (BUGS.md M2): a malicious/huge computed value — e.g.
// `string.rep("A", 1e8)` — must not inflate the rendered view without bound;
// oversized cells truncate with a visible marker.
final class CellEscapes {

    /** Per-cell character ceiling for rendered views. */
    static final int MAX_CELL_CHARS = 64_000;

    private CellEscapes() {}

    static String clamp(String text) {
        if (text.length() <= MAX_CELL_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CELL_CHARS) + "… [cell truncated at " + MAX_CELL_CHARS + " chars]";
    }

    static String mdCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = clamp(String.valueOf(value));
        // Backslash first, then the pipe — escaping only the pipe leaves the
        // preceding `\` unpaired and the cell still splits (BUGS.md D1)
        text = text.replace("\\", "\\\\");
        text = text.replace("|", "\\|");
        text = text.replace("\r", "");
        text = text.replace("\n", " ");
        return text;
    }
}
