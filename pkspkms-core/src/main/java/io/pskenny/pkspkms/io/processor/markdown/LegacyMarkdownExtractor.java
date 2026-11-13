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
