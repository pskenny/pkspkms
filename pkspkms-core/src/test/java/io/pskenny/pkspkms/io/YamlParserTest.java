/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of PKSPKMS.
 * Copyright (C) 2026 Paul Kenny
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.pskenny.pkspkms.io;

import io.pskenny.pkspkms.io.YamlParser;
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
