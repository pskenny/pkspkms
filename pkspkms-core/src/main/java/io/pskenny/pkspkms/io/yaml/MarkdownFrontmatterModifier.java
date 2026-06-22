package io.pskenny.pkspkms.io.yaml;

public class MarkdownFrontmatterModifier {

    private final YamlPatchEngine patchEngine = new YamlPatchEngine();

    public String patchMarkdownFile(String fileContent, String yamlPatch) {
        // Frontmatter usually looks like: ---\ntitle: Hello\n---
        if (!fileContent.startsWith("---\n") && !fileContent.startsWith("---\r\n")) {
            // No existing frontmatter? Prepend the patch as the new frontmatter
            return "---\n" + yamlPatch + "\n---\n" + fileContent;
        }

        // Find the closing frontmatter delimiter
        int firstDelimiterEnd = fileContent.indexOf("\n", 3);
        int secondDelimiterStart = fileContent.indexOf("---", firstDelimiterEnd);

        if (secondDelimiterStart == -1) {
            throw new IllegalArgumentException("Malformed markdown frontmatter blocks.");
        }

        // Extract the raw YAML block
        String originalYaml = fileContent.substring(firstDelimiterEnd + 1, secondDelimiterStart).trim();
        String markdownBody = fileContent.substring(secondDelimiterStart + 3);

        // Run it through our SnakeYAML utility
        String updatedYaml = patchEngine.applyPatch(originalYaml, yamlPatch);

        // Reconstruct the file cleanly
        return "---\n" + updatedYaml + "---\n" + markdownBody;
    }
}