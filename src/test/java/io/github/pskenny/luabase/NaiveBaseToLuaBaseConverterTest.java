package io.github.pskenny.luabase;

import io.github.pskenny.io.PksFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Official Obsidian Bases functions documentation: https://help.obsidian.md/bases/functions
public class NaiveBaseToLuaBaseConverterTest {

    NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter = new NaiveBaseToLuaBaseConverter();

    @Test
    public void testObsidianFilePropertyConversions() {
        String input = """
views:
  - type: table
    name: Table
    filters:
      and:
        - file.tags.containsAny("Orange")
    order:
      - file.name
      - file.backlinks
      - file.ctime
      - file.ext
      - file.folder
      - file.mtime
      - file.path
      - file.size
    sort:
      - property: file.name
        direction: DESC
    columnSize:
      file.name: 433
                """.trim();
        String expected = """
views:
  - type: table
    filters:
      and:
        - 'hasPropertyValue(file, "tags", "Orange")'
    order:
      - '"[[" .. getPropertyValue(file, "filePath", "") .. "]]", "filePath"'
      - 'getPropertyValue(file, "backlinks", ""), "backlinks"'
      - 'getPropertyValue(file, "creationDate", ""), "creationDate"'
      - 'getPropertyValue(file, "ext", ""), "ext"'
      - 'getPropertyValue(file, "folder", ""), "folder"'
      - 'getPropertyValue(file, "modificationDate", ""), "modificationDate"'
      - 'getPropertyValue(file, "path", ""), "path"'
      - 'getPropertyValue(file, "size", ""), "size"'
    sort:
      - property: filePath
        direction: DESC
                """.trim();

        String actual = naiveBaseToLuaBaseConverter.convert(input).trim();
        assertEquals(expected, actual);
    }

@Test
    public void testObsidianFileMethodContainsAny() {
        String input = """
views:
  - type: table
    name: Table
    filters:
      and:
        - file.tags.containsAny("Orange", "Apple")
    order:
      - file.name
      - file.tags
                """.trim();
        String expected = """
views:
  - type: table
    filters:
      and:
        - 'hasPropertyValue(file, "tags", "Orange")'
    order:
      - '"[[" .. getPropertyValue(file, "filePath", "") .. "]]", "filePath"'
      - 'table.concat( (function() local t = {}; local tags_array = ( getPropertyValue(file, "tags", "") or {}):toArray(); for i=1, #tags_array do local v = tags_array[i]; table.insert(t, "#" .. v) end; return t end)(), " "), "tags"'                """.trim();

        String actual = naiveBaseToLuaBaseConverter.convert(input).trim();
//        assertEquals(expected, actual);
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
      - 'table.concat( (function() local t = {}; local tags_array = ( getPropertyValue(file, "tags", "") or {}):toArray(); for i=1, #tags_array do local v = tags_array[i]; table.insert(t, "#" .. v) end; return t end)(), " "), "tags"'
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

        Map<String, Object> spec = new YamlBaseParser().parse(actual);
        LuaBaseProcessor processor = new LuaBaseProcessor(spec);
        var actualTable = processor.process(files);
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
            // Change from Arrays.asList to a new ArrayList
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

        Map<String, Object> spec = new YamlBaseParser().parse(actual);
        LuaBaseProcessor processor = new LuaBaseProcessor(spec);
        var actualTable = processor.process(files);
        String expectedTable = """
| Path |
|---|
| /notes/a_project_done.md |
""";
        assertEquals(actualTable, expectedTable);
    }
}
