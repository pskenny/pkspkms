package io.pskenny.pkspkms.io.parser;

import io.pskenny.pkspkms.io.PksFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

public class Parsers {
    private static final Logger logger = LoggerFactory.getLogger(Parsers.class);

    private final MarkdownParser markdownParser;
    private final GenericParser genericParser = new GenericParser();

    public Parsers(String dbUrl) {
        this.markdownParser = new MarkdownParser(dbUrl);
    }

    public PksFile initialReadOnlyParse(Path path, String directory) {
        try {
            if (path.toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
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
