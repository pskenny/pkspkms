package io.github.pskenny.io.parser;

import io.github.pskenny.io.PksFile;
import io.github.pskenny.io.parser.markdown.MarkdownLinkReader;
import io.github.pskenny.io.parser.markdown.YamlFrontmatterReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class MarkdownParser {
    private static final Logger logger = LoggerFactory.getLogger(MarkdownParser.class);

    private MarkdownLinkReader markdownLinkReader;
    private YamlFrontmatterReader yamlFrontmatterReader;

    public MarkdownParser() {
        markdownLinkReader = new MarkdownLinkReader();
        yamlFrontmatterReader = new YamlFrontmatterReader();
    }

    public PksFile initialParse(Path path, String directory) {
        try {
            var content = Files.readString(path, Charset.defaultCharset());
            // standard markdown only: frontmatter, md links. No transformations.
            var frontmatter = yamlFrontmatterReader.getFrontMatterProperties(content);
            PksFile pksFile = new PksFile(path.toFile(), directory, frontmatter);
            // read the Markdown links in the file, add if any
            var links = markdownLinkReader.getMarkdownLinksProperties(content);
            if (links != null && !links.isEmpty()) {
                var linksMap = Map.of("links", links);
                pksFile.getProperties().putAll(linksMap);
            }
            return pksFile;
        } catch (IOException e) {
            logger.error("Couldn't read file: {}", path);
        }
        return null;
    }
}
