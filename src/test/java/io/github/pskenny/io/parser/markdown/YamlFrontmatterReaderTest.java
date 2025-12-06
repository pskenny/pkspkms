package io.github.pskenny.io.parser.markdown;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YamlFrontmatterReaderTest {

    private final YamlFrontmatterReader reader = new YamlFrontmatterReader();

    @Test
    void testValidFrontmatterExtractionAndParsing() {
        final String content = """
            ---
            title: My Great Article
            author: Jane Doe
            tags: [java, efficiency, yaml]
            ---
            # Article Content
            This is the body.
            """;

        Map<String, Object> result = reader.getFrontMatterProperties(content);

        assertEquals(3, result.size());
        assertEquals("My Great Article", result.get("title"));
        assertEquals("Jane Doe", result.get("author"));
        assertTrue(result.containsKey("tags"));
    }

    @Test
    void testEmptyFrontmatterBlock() {
        final String content = """
            ---
            ---
            Content below.
            """;

        Map<String, Object> result = reader.getFrontMatterProperties(content);

        assertTrue(result.isEmpty());
    }

    @Test
    void testMissingFrontmatter() {
        final String content = """
            # Article Title
            This is content without frontmatter.
            ---
            key: value
            """;

        Map<String, Object> result = reader.getFrontMatterProperties(content);

        assertTrue(result.isEmpty());
    }

    @Test
    void testFrontmatterNotAtStart() {
        final String content = """
            A single line of text before frontmatter.
            ---
            key: value
            ---
            Body.
            """;

        Map<String, Object> result = reader.getFrontMatterProperties(content);

        assertTrue(result.isEmpty());
    }

    @Test
    void testEmptyInputString() {
        final String content = "";
        Map<String, Object> result = reader.getFrontMatterProperties(content);

        assertTrue(result.isEmpty());
    }

    @Test
    void testInvalidYamlSyntax() {
        final String content = """
            ---
            key-with-no-value
            another: pair
            ---
            Content below.
            """;

        Map<String, Object> result = reader.getFrontMatterProperties(content);

        assertTrue(result.isEmpty());
    }

    @Test
    void testFileBasedFrontmatterReading() throws IOException {
        Path tempFilePath = null;
        try {
            final String fileContent = """
                ---
                project: Java Efficiency
                version: 1.0
                ---
                Actual source code documentation.
                """;

            tempFilePath = Files.createTempFile("test_yaml_file", ".md");
            Files.writeString(tempFilePath, fileContent, StandardCharsets.UTF_8);

            File testFile = tempFilePath.toFile();
            Map<String, Object> result = reader.getFrontMatterProperties(testFile);

            assertEquals(2, result.size());
            assertEquals("Java Efficiency", result.get("project"));

        } finally {
            if (tempFilePath != null) {
                // In a proper JUnit test, Files.deleteIfExists(tempFilePath); would be used here.
            }
        }
    }
}