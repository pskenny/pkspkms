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

import io.pskenny.pkspkms.io.processor.MarkdownProcessor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The parity oracle: the legacy regex stack (BlockMasker + three readers)
 * behind the same {@link MarkdownExtractor} interface. G1 compares this to
 * the spec-grammar implementation over real corpora; after the diff review, the
 * commonmark extractor is what production uses.
 */
public final class LegacyMarkdownExtractor implements MarkdownExtractor {

    private final MarkdownLinkReader linkReader = new MarkdownLinkReader();
    private final MarkdownWikilinkReader wikiReader = new MarkdownWikilinkReader();
    private final TaskReader taskReader = new TaskReader();

    @Override
    public Extraction extract(String content) {
        String masked = BlockMasker.maskFencedBlocks(content);

        List<String> links = linkReader.getMarkdownLinksProperties(masked);
        List<String> wikilinks = wikiReader.getWikilinks(masked);
        TaskReader.TaskLists tasks = taskReader.read(masked);

        List<String> bases = new ArrayList<>();
        List<String> luabases = new ArrayList<>();
        var baseMatcher = MarkdownProcessor.BASE_PATTERN.matcher(content);
        while (baseMatcher.find()) {
            bases.add(baseMatcher.group(1).trim());
        }
        var luaMatcher = MarkdownProcessor.LUABASE_PATTERN.matcher(content);
        while (luaMatcher.find()) {
            luabases.add(luaMatcher.group(1).trim());
        }

        // bases/luabase interiors were UNMASKED in the legacy pipeline, so
        // their content fed link extraction verbatim
        Set<String> allLinks = new LinkedHashSet<>(links);
        for (String yaml : bases) {
            allLinks.addAll(linkReader.getMarkdownLinksProperties(yaml));
        }
        Set<String> allWiki = new LinkedHashSet<>(wikilinks);
        for (String yaml : bases) {
            wikiReader.getWikilinks(yaml).forEach(allWiki::add);
        }
        for (String yaml : luabases) {
            allLinks.addAll(linkReader.getMarkdownLinksProperties(yaml));
            wikiReader.getWikilinks(yaml).forEach(allWiki::add);
        }

        return new Extraction(new ArrayList<>(allLinks), new ArrayList<>(allWiki),
                tasks.open(), tasks.done(), bases, luabases,
                List.of(), // oracle has no outline or span notion — spec-grammar-only output
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
