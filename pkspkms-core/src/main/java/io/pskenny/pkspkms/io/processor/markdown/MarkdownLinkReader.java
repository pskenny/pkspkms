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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownLinkReader {

    // Angle-bracket destinations come first: [x](<My Image.png>) — the inner
    // text may contain parentheses, wildcards or spaces, so the plain pattern's
    // first-`)`-wins capture cannot handle them. Angle ranges are recorded and
    // the plain pattern skips them.
    private static final Pattern ANGLE_LINK_PATTERN =
            Pattern.compile("\\[(.*?)\\]\\(<([^>]*)>\\)");
    private static final Pattern PLAIN_LINK_PATTERN =
            Pattern.compile("\\[(.*?)\\]\\((.*?)\\)");

    private List<String> extractLinks(String content) {
        Set<String> uniqueUrls = new HashSet<>();
        List<String> markdownLinks = new ArrayList<>();

        List<int[]> consumed = new ArrayList<>();

        Matcher angle = ANGLE_LINK_PATTERN.matcher(content);
        while (angle.find()) {
            addLink(angle.group(2), uniqueUrls, markdownLinks);
            consumed.add(new int[]{angle.start(), angle.end()});
        }

        Matcher plain = PLAIN_LINK_PATTERN.matcher(content);
        while (plain.find()) {
            int start = plain.start(), end = plain.end();
            if (overlaps(consumed, start, end)) {
                continue;
            }
            String url = plain.group(2);
            // Post-fallback check: a dest slot that starts with an unbalanced
            // `<` (angle regex didn't own it) is a malformed fragment, not a path
            if (url.startsWith("<")) {
                continue;
            }
            addLink(url, uniqueUrls, markdownLinks);
        }

        return markdownLinks;
    }

    private static void addLink(String url, Set<String> uniqueUrls, List<String> out) {
        // don't count urls for the web
        if (url.toLowerCase(Locale.ROOT).startsWith("http://")
                || url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return;
        }
        if (url.isEmpty()) {
            return;
        }
        if (uniqueUrls.add(url)) {
            out.add(url);
        }
    }

    private static boolean overlaps(List<int[]> ranges, int start, int end) {
        for (int[] range : ranges) {
            if (range[0] < end && range[1] > start) {
                return true;
            }
        }
        return false;
    }

    public List<String> getMarkdownLinksProperties(final String content) {
        return extractLinks(content);
    }
}
