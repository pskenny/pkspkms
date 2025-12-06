package io.github.pskenny.io.parser.markdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
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
        } catch (IOException ex) {
            logger.error("Error reading file " + inputFile.getAbsolutePath() + ": " + ex.getMessage());
            return Collections.emptyMap();
        }
    }

    public Map<String, Object> getFrontMatterProperties(final String fileContent) {
        if (fileContent == null || fileContent.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            Matcher matcher = FRONTMATTER_PATTERN.matcher(fileContent);

            if (matcher.find() && matcher.start() == 0) {
                String yamlBlock = matcher.group(1).trim();
                if (yamlBlock.isEmpty()) {
                    return Collections.emptyMap();
                }

                return new Yaml().load(yamlBlock);
            }
        } catch (Exception ex) {
            logger.error("Error parsing YAML front matter: " + ex.getMessage());
        }

        return Collections.emptyMap();
    }
}