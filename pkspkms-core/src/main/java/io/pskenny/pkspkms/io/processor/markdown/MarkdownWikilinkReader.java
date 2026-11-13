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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownWikilinkReader {
    public static final Pattern PATTERN_WIKILINK =
            Pattern.compile("\\[\\[(.*?)]]");

    public List<String> getWikilinks(String content) {
        Set<String> embeds = new LinkedHashSet<>();
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher matcher = PATTERN_WIKILINK.matcher(content);
        while (matcher.find()) {
            // D1: blank inners ([[ ]]) and pure self-anchors ([[#h]]) are
            // not targets in either engine (parity with the flex walk)
            String inner = matcher.group(1).strip();
            if (inner.isEmpty() || inner.startsWith("#")) {
                continue;
            }
            embeds.add(matcher.group(1));
        }
        return new ArrayList<>(embeds);
    }
}
