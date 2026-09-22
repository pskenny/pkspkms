package io.pskenny.pkspkms.luabase;

import io.pskenny.pkspkms.io.PksFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static io.pskenny.pkspkms.test.FileUtil.readFile;
import static io.pskenny.pkspkms.test.FileUtil.readTestData;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Official Obsidian Bases functions documentation: https://help.obsidian.md/bases/functions
public class NaiveBaseToLuaBaseConverterTest {

    NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter = new NaiveBaseToLuaBaseConverter();

    @Test
    void testObsidianToPkspkmsPropertyConversions() {
        String input = readTestData("base/obsidian-to-luabase-properties.base");
        String actual = naiveBaseToLuaBaseConverter.convert(input).trim();
        String expected = readFile("test/data/luabase/obsidian-to-luabase-properties.luabase");
        assertEquals(expected, actual);
    }

    @Test
    public void testObsidianFileTagsPropertyConvertsToTagLinks() {
        String input = """
views:
  - type: table
    name: Table
    filters:
      and:
        - file.tags.containsAny("Orange")
    order:
      - file.tags
                """.trim();
        String expected = """
views:
  - type: table
    filters:
      and:
        - 'hasPropertyValue(file, "tags", "Orange")'
    order:
      - 'table.concat( (function() local t = {}; local tags_array = getPropertyValue(file, "tags", nil); if tags_array then for i=1, #tags_array do local v = tags_array[i]; table.insert(t, "#" .. tostring(v)) end end; return t end)(), " "), "tags"'
            """.trim();

        String actual = naiveBaseToLuaBaseConverter.convert(input).trim();
        assertEquals(expected, actual);
    }

    @Test
    public void testFilterMatching() {
        String input = """
views:
  - type: table
    name: Table
    filters:
      and:
        - access.containsAny("Public")
    order:
      - file.name
      - published
      - modifiedDate
      - creationDate
    sort:
      - property: published
        direction: DESC
    columnSize:
      file.name: 433
                """.trim();
        String expected = """
views:
  - type: table
    filters:
      and:
        - 'hasPropertyValue(file, "access", "Public")'
    order:
      - '"[[" .. getPropertyValue(file, "filePath", "") .. "]]", "filePath"'
      - 'getPropertyValue(file, "published", ""), "published"'
      - 'getPropertyValue(file, "modifiedDate", ""), "modifiedDate"'
      - 'getPropertyValue(file, "creationDate", ""), "creationDate"'
    sort:
      - property: published
        direction: DESC
                """.trim();

        String actual = naiveBaseToLuaBaseConverter.convert(input).trim();
        assertEquals(expected, actual);
    }

    @Test
    public void testConvert() {
        String input = """
views:
  - type: table
    name: Table
    filters:
      and:
        - file.tags.containsAny("List")
        - access == "Public"
    order:
      - file.name
                """;
        String expected = """
views:
  - type: table
    filters:
      and:
        - 'hasPropertyValue(file, "tags", "List")'
        - 'hasPropertyValue(file, "access", "Public")'
    order:
      - '"[[" .. getPropertyValue(file, "filePath", "") .. "]]", "filePath"'
                """.trim();
        String actual = naiveBaseToLuaBaseConverter.convert(input).trim();
        assertEquals(expected, actual);
    }

    @Test
    public void testComplicatedEndToEndTable() {
        String input = """
views:
  - type: table
    name: Table
    filters:
      and:
        - access == "Public"
    order:
      - filePath
      - access
                """;
        String expected = """
views:
  - type: table
    filters:
      and:
        - 'hasPropertyValue(file, "access", "Public")'
    order:
      - 'getPropertyValue(file, "filePath", ""), "filePath"'
      - 'getPropertyValue(file, "access", ""), "access"'
                """.trim();

        String actual = naiveBaseToLuaBaseConverter.convert(input).trim();
//        assertEquals(expected, actual);

        Map<String, PksFile> files = new HashMap<>();
        files.put("/notes/my_book_note.md", new PksFile("/notes/my_book_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/my_book_note.md");
            put("access", "Private");
        }}));
        files.put("/notes/another_book_note.md", new PksFile("/notes/another_book_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/another_book_note.md");
            put("access", "Public");
        }}));
        files.put("/notes/a_project_done.md", new PksFile("/notes/a_project_done.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/a_project_done.md");
            put("access", "Public");
        }}));

        Map<String, Object> spec = new YamlParser().parse(actual);
        LuaBaseProcessor processor = new LuaBaseProcessor();
        var actualTable = processor.process(spec, files);
        String expectedTable = """
| filePath | access |
|---|---|
| [[/notes/another_book_note.md]] | Public |
| [[/notes/a_project_done.md]] | Public |
""";
        assertEquals(expectedTable, actualTable);
    }

    @Test
    public void testObsidianBaseToLuaBaseToTable() {
        String input = """
views:
  - type: table
    filters:
      and:
        - status.containsAny("Active")
        """;
        String expected = """
views:
  - type: table
    filters:
      and:
        - 'hasPropertyValue(file, "status", "Active")'
        """;

        String actual = naiveBaseToLuaBaseConverter.convert(input);
        assertEquals(expected, actual);

        Map<String, PksFile> files = new HashMap<>();
        files.put("/notes/my_book_note.md", new PksFile("/notes/my_book_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/my_book_note.md");
            put("tag", new ArrayList<>(Arrays.asList("book", "textbook")));
            put("status", "Inactive");
        }}));
        files.put("/notes/another_book_note.md", new PksFile("/notes/another_book_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/another_book_note.md");
            put("tag", new ArrayList<>(Arrays.asList("book", "fiction")));
            put("status", "Not_Started");
        }}));
        files.put("/notes/a_project_done.md", new PksFile("/notes/a_project_done.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/a_project_done.md");
            put("tag", new ArrayList<>(Arrays.asList("project", "work")));
            put("status", "Active");
        }}));

        Map<String, Object> spec = new YamlParser().parse(actual);
        LuaBaseProcessor processor = new LuaBaseProcessor();
        var actualTable = processor.process(spec, files);
        String expectedTable = """
| Path |
|---|
| /notes/a_project_done.md |
""";
        assertEquals(expectedTable, actualTable);
    }

    @Test
    public void testMultiValueContainsAnyEndToEnd() {
        String input = """
views:
  - type: table
    filters:
      and:
        - bookmark.containsAny("Things", "Music")
        """;
        String expected = """
views:
  - type: table
    filters:
      and:
        - 'hasPropertyValueIn(file, "bookmark", {"Things", "Music"})'
        """;

        String actual = naiveBaseToLuaBaseConverter.convert(input);
        assertEquals(expected, actual);

        Map<String, PksFile> files = new HashMap<>();
        files.put("/notes/thing.md", new PksFile("/notes/thing.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/thing.md");
            put("bookmark", "Things");
        }}));
        files.put("/notes/music.md", new PksFile("/notes/music.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/music.md");
            put("bookmark", "Music");
        }}));
        files.put("/notes/other.md", new PksFile("/notes/other.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/other.md");
            put("bookmark", "Other");
        }}));

        Map<String, Object> spec = new YamlParser().parse(actual);
        LuaBaseProcessor processor = new LuaBaseProcessor();
        var actualTable = processor.process(spec, files);
        String expectedTable = """
| Path |
|---|
| /notes/thing.md |
| /notes/music.md |
""";
        assertEquals(expectedTable, actualTable);
    }

    @Test
    void testExpressionConversions() {
        NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter = new NaiveBaseToLuaBaseConverter();
        // Use a LinkedHashMap or List of Entries because Map.of() is capped at 10 elements and unsorted
        java.util.List<Map.Entry<String, String>> conversions = java.util.List.of(
                Map.entry("", ""),
                Map.entry("file.name", "filePath"),
                Map.entry("file.tags", "tags"),
                Map.entry("file.contains(\"something\")", "'hasPropertyContaining(file, \"file\", \"something\")'"),
                Map.entry("file.containsAny(\"something\")", "'hasPropertyValue(file, \"file\", \"something\")'"),
                Map.entry("bookmark.contains(\"Things\")", "'hasPropertyContaining(file, \"bookmark\", \"Things\")'"),
                Map.entry("bookmark.containsAny(\"Things\", \"Music\")", "'hasPropertyValueIn(file, \"bookmark\", {\"Things\", \"Music\"})'"),
                Map.entry("!bookmark.contains(\"Things\")", "' not hasPropertyContaining(file, \"bookmark\", \"Things\")'"),
                Map.entry("!bookmark.containsAny(\"Things\", \"Music\")", "' not hasPropertyValueIn(file, \"bookmark\", {\"Things\", \"Music\"})'"),
                Map.entry("file.tags.containsAny(\"A\", \"B\", \"C\")", "'hasPropertyValueIn(file, \"tags\", {\"A\", \"B\", \"C\"})'"),
                Map.entry("file.tags.contains(\"Orange\")", "'hasPropertyContaining(file, \"tags\", \"Orange\")'")
        );
        for (Map.Entry<String, String> entry : conversions) {
            assertEquals(entry.getValue(), naiveBaseToLuaBaseConverter.tryAndConvertExpression(entry.getKey()),
                    "Failed conversion from " + entry.getKey() + " to " + entry.getValue());
        }
    }

    @Test
    void testContainsExpressionEdgeCases() {
        NaiveBaseToLuaBaseConverter converter = new NaiveBaseToLuaBaseConverter();
        java.util.List<Map.Entry<String, String>> edgeCases = java.util.List.of(
                // Whitespaces inside parentheses
                Map.entry("file.tags.contains(   \"Orange\"   )", "'hasPropertyContaining(file, \"tags\", \"Orange\")'"),
                Map.entry("file.tags.containsAny(  \"A\"  ,  \"B\"  )", "'hasPropertyValueIn(file, \"tags\", {\"A\", \"B\"})'"),

                // Special symbols inside search terms
                Map.entry("file.tags.contains(\"Orange (bright), yellow: 'citrus'!\")", "'hasPropertyContaining(file, \"tags\", \"Orange (bright), yellow: 'citrus'!\")'"),

                // Deeply nested keys
                Map.entry("metadata.nested_field.prop.contains(\"value\")", "'hasPropertyContaining(file, \"metadata.nested_field.prop\", \"value\")'"),

                // Numeric fallbacks (should gracefully return verbatim instead of crashing)
                Map.entry("file.year.contains(2025)", "file.year.contains(2025)"),
                Map.entry("file.status.contains(true)", "file.status.contains(true)")
        );
        for (Map.Entry<String, String> entry : edgeCases) {
            assertEquals(entry.getValue(), converter.tryAndConvertExpression(entry.getKey()),
                    "Failed edge-case conversion from: " + entry.getKey());
        }
    }

    @Test
    void testNumericComparisonConversions() {
        java.util.List<Map.Entry<String, String>> conversions = java.util.List.of(
                Map.entry("file.size > 100", "'hasPropertyValueGreaterThan(file, \"size\", 100)'"),
                Map.entry("rating >= 3.5", "'hasPropertyValueGreaterThanOrEqual(file, \"rating\", 3.5)'"),
                Map.entry("year < 2000", "'hasPropertyValueLessThan(file, \"year\", 2000)'"),
                Map.entry("count <= 5", "'hasPropertyValueLessThanOrEqual(file, \"count\", 5)'"),
                Map.entry("!(file.size > 100)", "' not hasPropertyValueGreaterThan(file, \"size\", 100)'"),
                Map.entry("!file.size > 100", "' not hasPropertyValueGreaterThan(file, \"size\", 100)'")
        );
        for (Map.Entry<String, String> entry : conversions) {
            assertEquals(entry.getValue(), naiveBaseToLuaBaseConverter.tryAndConvertExpression(entry.getKey()),
                    "Failed numeric comparison conversion from: " + entry.getKey());
        }
    }

    @Test
    void testNumericComparisonFilterEndToEnd() {
        String input = """
                views:
                  - type: table
                    name: Table
                    filters:
                      and:
                        - file.size > 100
                    order:
                      - file.name
                      - file.size
                """;

        Map<String, PksFile> files = new HashMap<>();
        files.put("big.md", new PksFile("big.md", new HashMap<String, Object>() {{
            put("filePath", "big.md");
            put("size", 250);
        }}));
        files.put("small.md", new PksFile("small.md", new HashMap<String, Object>() {{
            put("filePath", "small.md");
            put("size", 50);
        }}));
        // Non-numeric value: must be excluded, not crash
        files.put("badtype.md", new PksFile("badtype.md", new HashMap<String, Object>() {{
            put("filePath", "badtype.md");
            put("size", "not-a-number");
        }}));
        // Missing property: must be excluded, not crash
        files.put("noprop.md", new PksFile("noprop.md", new HashMap<String, Object>() {{
            put("filePath", "noprop.md");
        }}));

        String actual = naiveBaseToLuaBaseConverter.convert(input);
        Map<String, Object> spec = new YamlParser().parse(actual);
        String table = new LuaBaseProcessor().process(spec, files);

        String expectedTable = """
                | filePath | size |
                |---|---|
                | [[big.md]] | 250 |
                """;
        assertEquals(expectedTable, table);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTopLevelFiltersInheritedByView() {
        // Obsidian .base files allow filters at top level, shared across views
        String input = """
                filters:
                  and:
                    - status.contains("Idea")
                views:
                  - type: cards
                """;

        Map<String, Object> spec = naiveBaseToLuaBaseConverter.convertToMap(input);
        Map<String, Object> view = (Map<String, Object>) ((java.util.List<?>) spec.get("views")).get(0);

        Map<String, Object> filters = (Map<String, Object>) view.get("filters");
        assertNotNull(filters, "View without filters must inherit top-level filters");
        assertEquals(java.util.List.of("hasPropertyContaining(file, \"status\", \"Idea\")"), filters.get("and"));
    }

    @Test
    void testTopLevelFiltersEndToEnd() {
        String input = """
                filters:
                  and:
                    - status.contains("Idea")
                views:
                  - type: cards
                """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("idea.md", new PksFile("idea.md", new HashMap<String, Object>() {{
            put("status", "Idea");
        }}));
        files.put("done.md", new PksFile("done.md", new HashMap<String, Object>() {{
            put("status", "Done");
        }}));

        Map<String, Object> spec = naiveBaseToLuaBaseConverter.convertToMap(input);
        String table = new LuaBaseProcessor().process(spec, files);

        assertTrue(table.contains("idea.md"));
        assertFalse(table.contains("done.md"));
    }

    @Test
    void testInFolderConversionAndEval() {
        assertEquals("'fileInFolder(file, \"Pictures/2018\")'",
                naiveBaseToLuaBaseConverter.tryAndConvertExpression("file.inFolder(\"Pictures/2018\")"));

        String input = """
                views:
                  - type: cards
                    filters:
                      and:
                        - file.inFolder("Pictures/2018")
                """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("in.jpg", new PksFile("Pictures/2018/in.jpg", new HashMap<>()));
        files.put("out.jpg", new PksFile("Pictures/2019/out.jpg", new HashMap<>()));

        Map<String, Object> spec = naiveBaseToLuaBaseConverter.convertToMap(input);
        String table = new LuaBaseProcessor().process(spec, files);

        assertTrue(table.contains("Pictures/2018/in.jpg"));
        assertFalse(table.contains("Pictures/2019"));
    }

    @Test
    void testStartsWithConversionsAndEval() {
        java.util.List<Map.Entry<String, String>> conversions = java.util.List.of(
                Map.entry("file.basename.startsWith(\"2025-12\")", "'fileFieldStartsWith(file, \"basename\", \"2025-12\")'"),
                Map.entry("file.name.startsWith(\"2025-12\")", "'fileFieldStartsWith(file, \"name\", \"2025-12\")'"),
                Map.entry("file.path.startsWith(\"Pictures\")", "'fileFieldStartsWith(file, \"path\", \"Pictures\")'"),
                Map.entry("!(file.basename.startsWith(\"2025\"))", "' not fileFieldStartsWith(file, \"basename\", \"2025\")'")
        );
        for (Map.Entry<String, String> entry : conversions) {
            assertEquals(entry.getValue(), naiveBaseToLuaBaseConverter.tryAndConvertExpression(entry.getKey()),
                    "Failed startsWith conversion: " + entry.getKey());
        }

        String input = """
                views:
                  - type: table
                    filters:
                      and:
                        - file.basename.startsWith("2025")
                """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("diary2025.md", new PksFile("Notes/Diary/2025-01-01.md", new HashMap<>()));
        files.put("diary2024.md", new PksFile("Notes/Diary/2024-12-31.md", new HashMap<>()));

        Map<String, Object> spec = naiveBaseToLuaBaseConverter.convertToMap(input);
        String table = new LuaBaseProcessor().process(spec, files);

        assertTrue(table.contains("2025-01-01.md"));
        assertFalse(table.contains("2024-12-31"));
    }

    @Test
    void testIsEmptyConversionAndEval() {
        assertEquals("'hasEmptyProperty(file, \"digitalGarden\")'",
                naiveBaseToLuaBaseConverter.tryAndConvertExpression("digitalGarden.isEmpty()"));
        assertEquals("' not hasEmptyProperty(file, \"url\")'",
                naiveBaseToLuaBaseConverter.tryAndConvertExpression("!url.isEmpty()"));

        String input = """
                views:
                  - type: table
                    filters:
                      and:
                        - digitalGarden.isEmpty()
                """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("empty.md", new PksFile("empty.md", new HashMap<String, Object>() {{
            put("digitalGarden", "");
        }}));
        files.put("set.md", new PksFile("set.md", new HashMap<String, Object>() {{
            put("digitalGarden", "true");
        }}));

        Map<String, Object> spec = naiveBaseToLuaBaseConverter.convertToMap(input);
        String table = new LuaBaseProcessor().process(spec, files);

        assertTrue(table.contains("empty.md"));
        assertFalse(table.contains("set.md"));
    }

    @Test
    void testContainsAllConversionAndEval() {
        assertEquals("'hasPropertyContainingAll(file, \"recipe\", {\"Low_Cleanup\", \"Quick\"})'",
                naiveBaseToLuaBaseConverter.tryAndConvertExpression("recipe.containsAll(\"Low_Cleanup\", \"Quick\")"));

        String input = """
                views:
                  - type: table
                    filters:
                      and:
                        - recipe.containsAll("Low_Cleanup", "Quick")
                """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("all.md", new PksFile("all.md", new HashMap<String, Object>() {{
            put("recipe", new ArrayList<>(java.util.Arrays.asList("Low_Cleanup", "Quick", "Veg")));
        }}));
        files.put("partial.md", new PksFile("partial.md", new HashMap<String, Object>() {{
            put("recipe", new ArrayList<>(java.util.Arrays.asList("Quick")));
        }}));

        Map<String, Object> spec = naiveBaseToLuaBaseConverter.convertToMap(input);
        String table = new LuaBaseProcessor().process(spec, files);

        assertTrue(table.contains("all.md"));
        assertFalse(table.contains("partial.md"));
    }

    @Test
    void testArrayEqualityConversionAndEval() {
        // Single-element array is plain equality; multi-element maps to hasPropertyValueIn
        assertEquals("'hasPropertyValue(file, \"type\", \"Bookmark\")'",
                naiveBaseToLuaBaseConverter.tryAndConvertExpression("type == [\"Bookmark\"]"));
        assertEquals("'hasPropertyValueIn(file, \"type\", {\"Bookmark\", \"Recipe\"})'",
                naiveBaseToLuaBaseConverter.tryAndConvertExpression("type == [\"Bookmark\", \"Recipe\"]"));
        assertEquals("' not hasPropertyValue(file, \"type\", \"Bookmark\")'",
                naiveBaseToLuaBaseConverter.tryAndConvertExpression("!(type == [\"Bookmark\"])"));

        String input = """
                views:
                  - type: table
                    filters:
                      and:
                        - type == ["Bookmark"]
                """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("bm.md", new PksFile("bm.md", new HashMap<String, Object>() {{
            put("type", "Bookmark");
        }}));
        files.put("note.md", new PksFile("note.md", new HashMap<String, Object>() {{
            put("type", "Note");
        }}));

        Map<String, Object> spec = naiveBaseToLuaBaseConverter.convertToMap(input);
        String table = new LuaBaseProcessor().process(spec, files);

        assertTrue(table.contains("bm.md"));
        assertFalse(table.contains("note.md"));
    }

    @Test
    void testTabIndentedBaseParses() {
        // Obsidian tolerates tabs; the converter must too (B48 normalization on the converter path)
        String input = "filters:\n"
                + "      or:\n"
                + "        - file.name.contains(\"Hoppo\")\n"
                + "\t    - file.name.contains(\"Seamus Boland\")\n"
                + "views:\n"
                + "  - type: cards\n"
                + "    name: View\n"
                + "    sort:\n"
                + "      - property: file.name\n"
                + "        direction: DESC\n"
                + "    image: file.file\n"
                + "  - type: table\n"
                + "    name: Table\n";

        Map<String, Object> spec = naiveBaseToLuaBaseConverter.convertToMap(input);
        assertNotNull(spec.get("views"));
    }

    @Test
    void testHoppoBlockEndToEnd() {
        // The verbatim failing base from the field report (tabs included)
        String input = "filters:\n"
                + "      or:\n"
                + "        - file.name.contains(\"Hoppo\")\n"
                + "\t    - file.name.contains(\"Seamus Boland\")\n"
                + "views:\n"
                + "  - type: cards\n"
                + "    name: View\n"
                + "    sort:\n"
                + "      - property: file.name\n"
                + "        direction: DESC\n"
                + "    image: file.file\n"
                + "  - type: table\n"
                + "    name: Table\n";

        Map<String, PksFile> files = new HashMap<>();
        files.put("meeting.md", new PksFile("People/Hoppo meeting.md", new HashMap<String, Object>() {{
            put("filePath", "People/Hoppo meeting.md");
        }}));
        files.put("other.md", new PksFile("People/Seamus Boland.md", new HashMap<String, Object>() {{
            put("filePath", "People/Seamus Boland.md");
        }}));

        Map<String, Object> spec = naiveBaseToLuaBaseConverter.convertToMap(input);
        String table = new LuaBaseProcessor().process(spec, files);

        assertTrue(table.contains("Hoppo"), "Hoppo note must match name substring");
        assertTrue(table.contains("Seamus Boland"), "Seamus Boland note must match");
    }
}
