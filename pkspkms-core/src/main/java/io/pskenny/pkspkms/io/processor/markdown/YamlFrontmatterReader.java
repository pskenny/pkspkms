package io.pskenny.pkspkms.io.processor.markdown;

import io.pskenny.pkspkms.io.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class YamlFrontmatterReader {
    private static final Logger logger = LoggerFactory.getLogger(YamlFrontmatterReader.class);

    public Map<String, Object> getFrontMatterProperties(final String fileContent) {
        if (fileContent == null || fileContent.isEmpty()) {
            return new LinkedHashMap<>();
        }
        // Shared Frontmatter oracle (BUGS.md H4): same grammar as the processor
        // body slice and Export's strip — one frontmatter definition, three readers.
        String yamlBlock = io.pskenny.pkspkms.io.Frontmatter.yamlBlock(fileContent);
        String yaml = yamlBlock.trim();
        if (yaml.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new YamlParser().parse(yaml);
    }
}