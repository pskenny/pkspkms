package io.pskenny.pkspkms.io.yaml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YamlPatchEngineTest {

    private YamlPatchEngine patchEngine;
    private Yaml yamlParser;

    @BeforeEach
    void setUp() {
        this.patchEngine = new YamlPatchEngine();
        this.yamlParser = new Yaml();
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_OverwritePrimitivesAndDeepMergeMapsAndDeleteNullKeys() {
        // 1. Arrange: A target with mixed data types and nested maps
        String target = """
                title: Old Title
                author:
                  name: Alice
                  role: Developer
                tags: [java, android]
                """;

        // 2. Arrange: A patch that updates a string, updates a nested map value, and deletes a key
        String patch = """
                title: New Title
                author:
                  role: Architect
                tags: null
                """;

        // 3. Act
        String resultYaml = patchEngine.applyPatch(target, patch);
        Map<String, Object> resultMap = yamlParser.load(resultYaml);

        // 4. Assert
        // Rule 1: Primitive overwritten
        assertEquals("New Title", resultMap.get("title"));

        // Rule 2: Deep merge preserved 'name' but updated 'role'
        Map<String, Object> author = (Map<String, Object>) resultMap.get("author");
        assertEquals("Alice", author.get("name"));
        assertEquals("Architect", author.get("role"));

        // Rule 3: Null value explicitly removed the key
        assertFalse(resultMap.containsKey("tags"));
    }

    @Test
    void should_HandleEmptyInputsGracefully() {
        String patch = "title: New Title";

        // Target is null/empty -> Should essentially just apply the patch
        String result = patchEngine.applyPatch("", patch);
        Map<String, Object> resultMap = yamlParser.load(result);

        assertEquals("New Title", resultMap.get("title"));

        // Patch is null/empty -> Should return original target unchanged
        String untouched = patchEngine.applyPatch("status: active", "");
        assertTrue(untouched.contains("status: active"));
    }
}