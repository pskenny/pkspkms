package io.pskenny.pkspkms.luabase;

import io.pskenny.pkspkms.luabase.YamlParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        // date type, creationDate
        // TODO: change, this hides the original text later.
        //  It should conform better with
        //  https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date#date_time_string_format
        Object creationDate = map.get("creationDate");
        assertEquals(YamlParser.JsDateWrapper.class, creationDate.getClass());
    }
}
