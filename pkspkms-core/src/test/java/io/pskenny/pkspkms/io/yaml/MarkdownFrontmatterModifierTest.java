package io.pskenny.pkspkms.io.yaml;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownFrontmatterModifierTest {

    private final MarkdownFrontmatterModifier modifier = new MarkdownFrontmatterModifier();

    @Test
    void should_PatchExistingFrontmatterWithoutTouchingBody() {
        String fileContent = """
                ---
                title: Hello World
                draft: true
                ---
                # My Heading
                This is the actual markdown content body.
                """;

        String patch = """
                draft: false
                author: Ghost
                """;

        String result = modifier.patchMarkdownFile(fileContent, patch);

        // Assert Frontmatter updates
        assertTrue(result.contains("draft: false"), "Should update existing key");
        assertTrue(result.contains("author: Ghost"), "Should inject new key");
        assertTrue(result.contains("title: Hello World"), "Should retain untouched keys");

        // Assert Body integrity
        assertTrue(result.contains("# My Heading"), "Markdown body must remain intact");
        assertTrue(result.endsWith("content body.\n"), "Trailing content check");
    }

    @Test
    void should_CreateNewFrontmatterIfMissing() {
        String fileContent = """
                # Pure Markdown
                No frontmatter block here.
                """;

        String patch = "injected: true";

        String result = modifier.patchMarkdownFile(fileContent, patch);

        // Assert it wrapped the patch in delimiters at the very top
        assertTrue(result.startsWith("---\n"), "Should generate starting delimiter");
        assertTrue(result.contains("injected: true"));
        assertTrue(result.contains("# Pure Markdown"), "Should append the original body");
    }
}