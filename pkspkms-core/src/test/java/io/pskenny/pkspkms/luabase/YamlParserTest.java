package io.pskenny.pkspkms.luabase;

import io.pskenny.pkspkms.luabase.YamlParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class YamlParserTest {
    @Test
    void test() {
        String text = """
                tags:
                  - Markdown
                  - Markup_Language
                creationDate: 2025-08-21
                modifiedDate: 2025-12-05
                url: https://daringfireball.net/projects/markdown/syntax
                access: Public/Draft
                description: Human readable markup language
                digitalGarden: Seed
            """;
        YamlParser yamlParser = new YamlParser();
        Map<String, Object> map = yamlParser.parse(text);
        assertEquals("Seed", map.get("digitalGarden"));

        // list type, tags
        Object tags = map.get("tags");
        assertEquals(ArrayList.class, tags.getClass());

        // date type, creationDate — timestamps keep their original text (B43)
        Object creationDate = map.get("creationDate");
        assertEquals("2025-08-21", creationDate);
    }

    @Test
    void testTabIndentedYamlParses() {
        // Obsidian tolerates tab indentation; SnakeYAML does not
        String text = "views:\n\t- type: table\n\t  name: Table";
        Map<String, Object> map = new YamlParser().parse(text);
        Object view = ((java.util.List<?>) map.get("views")).get(0);
        assertEquals("table", ((java.util.Map<?, ?>) view).get("type"));
        assertEquals("Table", ((java.util.Map<?, ?>) view).get("name"));
    }

    @Test
    void testMixedSpaceTabIndentationParses() {
        // Monthly gallery bases indent with spaces followed by tabs
        String yaml = "views:\n  - type: table\n    filters:\n      or:\n"
                + "        - and:\n        \t- creationDate > \"2025-11-30\"\n        \t- creationDate < \"2025-01-01\"\n"
                + "        - and:\n            - file.ctime > \"2025-11-30\"\n            - file.ctime < \"2025-01-01\"";
        Map<String, Object> map = new YamlParser().parse(yaml);
        Object view = ((java.util.List<?>) map.get("views")).get(0);
        assertNotNull(((java.util.Map<?, ?>) view).get("filters"));
    }

    @Test
    void testLenientTimestampFallback() {
        // Unparseable datetime must degrade to the raw string, not nuke the frontmatter
        String text = "tags:\n  - Diary\ndate: 2025-01-19 19:43";
        Map<String, Object> map = new YamlParser().parse(text);
        assertEquals("2025-01-19 19:43", map.get("date"));
        assertEquals(java.util.List.of("Diary"), map.get("tags"));
    }

    @Test
    void testDatetimeKeepsOriginalString() {
        // Datetimes must not degrade to epoch millis (B43)
        Map<String, Object> map = new YamlParser().parse("date: 2025-12-06T00:00:00");
        assertEquals("2025-12-06T00:00:00", map.get("date"));
    }
}
