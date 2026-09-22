package io.pskenny.pkspkms.io.processor.markdown;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.scanner.ScannerException;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
            valid: value
            key-with-no-value
            another: pair
            ---
            Content below.
            """;

        assertThrows(ScannerException.class, () -> {
            reader.getFrontMatterProperties(content);
        });
    }

    @Test
    void testInvalidYamlSyntaxSingleLineMultiVal() {
        final String content = """
            ---
            valid: value
            tags:
              - markdown
              - tutorial
              - web
            ---
            Content below.
            """;

        Map<String, Object> result = reader.getFrontMatterProperties(content);
        assertEquals(((ArrayList) result.get("tags")).size(),3 );
    }

    @Test
    void testTabIndentedFrontmatter() {
        // Tab indentation must be expanded, not rejected (diary notes use it)
        final String content = "---\n\ttags:\n\t\t- TabTag\n---\nBody.";

        Map<String, Object> result = reader.getFrontMatterProperties(content);

        assertEquals("TabTag", ((java.util.ArrayList<?>) result.get("tags")).get(0));
    }

}