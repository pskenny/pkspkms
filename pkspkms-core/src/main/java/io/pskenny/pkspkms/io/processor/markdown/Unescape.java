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
 * Un-escapes markdown character escapes that commonmark's renderer emits when
 * re-serializing a section taken from an embedded note. A backslash is
 * removed only when it directly precedes one of commonmark's escapable ASCII
 * punctuation characters — everything else (C:\Users, \n, custom escapes)
 * survives verbatim. Replaces the old blanket backslash strip that corrupted
 * embed content (BUGS.md B8).
 */
public final class Unescape {

    private Unescape() {}

    public static String markdownPunctuation(String content) {
        if (content == null || content.indexOf('\\') == -1) {
            return content;
        }
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length() && isEscapable(content.charAt(i + 1))) {
                i++;
                sb.append(content.charAt(i)); // keep the escaped character, drop the backslash
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isEscapable(char c) {
        return c == '!' || c == '"' || c == '#' || c == '$' || c == '%' || c == '&'
                || c == '\'' || c == '(' || c == ')' || c == '*' || c == '+' || c == ','
                || c == '-' || c == '.' || c == '/' || c == ':' || c == ';' || c == '<'
                || c == '=' || c == '>' || c == '?' || c == '@' || c == '[' || c == ']'
                || c == '^' || c == '_' || c == '`' || c == '{' || c == '|' || c == '}'
                || c == '~' || c == '\\';
    }
}
