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

/**
 * Strips a leading UTF-8 BOM from decoded content so frontmatter detection
 * starts at position 0 (BUGS.md B16 — BOM-prefixed files silently lost all
 * frontmatter). Content hashing keeps the original bytes; only parsing sees
 * the stripped string.
 */
public final class BomStripper {

    private BomStripper() {}

    public static String strip(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        return content.charAt(0) == '\uFEFF'
                ? content.substring(1)
                : content;
    }
}
