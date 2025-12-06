package io.github.pskenny.io.parser;

import io.github.pskenny.io.PksFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class Parsers {
    private static final Logger logger = LoggerFactory.getLogger(Parsers.class);
    private MarkdownParser markdownParser = new MarkdownParser();
    private GenericParser genericParser = new GenericParser();

    public PksFile initialReadOnlyParse(Path path, String directory) {
        try {
            if (path.toString().toLowerCase().endsWith(".md")) {
                return markdownParser.initialParse(path, directory);
            } else {
                return genericParser.parse(path.toFile(), directory);
            }
        } catch(Exception e) {
            logger.error("Couldn't parse file at: {}", path.toString());
        }

        return null;
    }
}
