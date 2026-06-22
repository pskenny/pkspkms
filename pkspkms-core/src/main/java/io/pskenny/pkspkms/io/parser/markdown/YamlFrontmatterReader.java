package io.pskenny.pkspkms.io.parser.markdown;

import io.pskenny.pkspkms.luabase.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YamlFrontmatterReader {
    private static final Logger logger = LoggerFactory.getLogger(YamlFrontmatterReader.class);
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*$(.*?)^---\\s*$",
            Pattern.DOTALL | Pattern.MULTILINE
    );

    public Map<String, Object> getFrontMatterProperties(final File inputFile) {
        if (!inputFile.getAbsolutePath().endsWith(".md")) {
            return Collections.emptyMap();
        }

        try {
            String fileContent = Files.readString(inputFile.toPath(), StandardCharsets.UTF_8);
            return getFrontMatterProperties(fileContent);
        } catch (Exception ex) {
            logger.error("Error reading file {}: {}", inputFile.getAbsolutePath(), ex.getMessage());
            return Collections.emptyMap();
        }
    }

    public Map<String, Object> getFrontMatterProperties(final String fileContent) {
        if (fileContent == null || fileContent.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Matcher matcher = FRONTMATTER_PATTERN.matcher(fileContent);

        if (matcher.find() && matcher.start() == 0) {
            String yamlBlock = matcher.group(1).trim();
            if (yamlBlock.isEmpty()) {
                return new LinkedHashMap<>();
            }

            return new YamlParser().parse(yamlBlock);
        }

        return new LinkedHashMap<>();
    }
}