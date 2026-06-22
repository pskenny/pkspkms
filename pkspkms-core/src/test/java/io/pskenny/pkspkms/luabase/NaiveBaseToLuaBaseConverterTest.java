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
        Map<String, String> conversions = Map.of(
                "", "",
                "file.name", "filePath",
                "file.tags", "tags",
                "file.containsAny(\"something\")", "'hasPropertyValue(file, \"file\", \"something\")'",
                "bookmark.containsAny(\"Things\", \"Music\")", "'hasPropertyValueIn(file, \"bookmark\", {\"Things\", \"Music\"})'",
                "!bookmark.containsAny(\"Things\", \"Music\")", "' not hasPropertyValueIn(file, \"bookmark\", {\"Things\", \"Music\"})'",
                "file.tags.containsAny(\"A\", \"B\", \"C\")", "'hasPropertyValueIn(file, \"tags\", {\"A\", \"B\", \"C\"})'"
        );
        for (Map.Entry<String, String> entry : conversions.entrySet()) {
            assertEquals(entry.getValue(), naiveBaseToLuaBaseConverter.tryAndConvertExpression(entry.getKey()),
                    "Failed conversion from " + entry.getKey() + " to " + entry.getValue());
        }
    }
}
