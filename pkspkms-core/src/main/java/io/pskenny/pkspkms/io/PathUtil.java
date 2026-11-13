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

import java.util.Locale;

public final class PathUtil {
    private static final int MAX_FILE_NAME = 80;

    private PathUtil() {}

    public static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash == -1 ? path : path.substring(slash + 1);
    }

    // File-name-safe text for synthesized vault entries: separators, wildcards
    // and control characters removed; whitespace collapsed; length capped
    public static String safeFileName(String raw) {
        if (raw == null) {
            return "untitled";
        }
        String cleaned = raw
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "")
                .strip()
                .replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return "untitled";
        }
        return cleaned.length() > MAX_FILE_NAME
                ? cleaned.substring(0, MAX_FILE_NAME)
                : cleaned;
    }

    // Lowercased for case-insensitive matchers (Obsidian-style ignores)
    public static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}