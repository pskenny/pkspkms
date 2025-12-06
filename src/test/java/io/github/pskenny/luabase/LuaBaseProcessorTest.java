package io.github.pskenny.luabase;

import io.github.pskenny.io.PksFile;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/*
More filters that don't work:
- bookmark.contains("Things") - contains function not implemented
- bookmark.containsAny("Things", "Music") - containsAny with multiple values not implemented
- type == ["some_type"] - property equals arrays not implemented
- file.inFolder("images/blah") - inFolder not implemented

- what to do with cards?
 */
public class LuaBaseProcessorTest  extends TestCase {

    public LuaBaseProcessorTest(String testName )
    {
        super( testName );
    }

    public static Test suite()
    {
        return new TestSuite( LuaBaseProcessorTest.class );
    }

    public  void testProcess() {
        final String testLuaBaseYaml = """
        formulas:
          formatted_price: 'return string.format("$%.2f", getPropertyValue(file, "price", 0))'
          ppu: 'return getPropertyValue(file, "price", 0) * 5'
        views:
          - type: table
            name: "My table"
            limit: 10
            filters:
              and:
                - 'not hasPropertyValue(file, "tag", "book")'
            order:
              - '"[[" .. getPropertyValue(file, "filePath") .. "]]", "Path"'
              - 'formatted_price(file), "Price"'
              - 'ppu(file), "PPU"'
              - 'toFixed(ppu(file), 0), "toFixed"'
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("/notes/my_book_note.md", new PksFile("/notes/my_book_note.md", new HashMap<>() {{
            put("filePath", "/notes/my_book_note.md");
            // Change from Arrays.asList to a new ArrayList
            put("tag", new ArrayList<>(Arrays.asList("book", "textbook")));
            put("status", "done");
            put("price", 10.5095);
            put("age", 2);
        }}));
        files.put("/notes/another_book_note.md", new PksFile("/notes/another_book_note.md", new HashMap<>() {{
            put("filePath", "/notes/another_book_note.md");
            put("tag", new ArrayList<>(Arrays.asList("book", "fiction")));
            put("status", "ne");
            put("price", 3.00);
            put("age", 4);
        }}));
        files.put("/notes/a_project_done.md", new PksFile("/notes/a_project_done.md", new HashMap<>() {{
            put("filePath", "/notes/a_project_done.md");
            put("tag", new ArrayList<>(Arrays.asList("project", "work")));
            put("status", "de");
            put("price", 10);
            put("age", 1);
        }}));
        files.put("/notes/Required Reading/a_required_reading_note.md", new PksFile("/notes/Required Reading/a_required_reading_note.md", new HashMap<>() {{
            put("filePath", "/notes/Required Reading/a_required_reading_note.md");
            put("tag", new ArrayList<>(Arrays.asList("book", "required")));
            put("status", "in-progress");
            put("price", 6.00);
            put("age", 6);
        }}));

        YamlBaseParser ybp = new YamlBaseParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor(ybp.parse(testLuaBaseYaml));
        String table = luaBaseProcessor.process(files);
        String expected = """
| Path | Price | PPU | toFixed |
|---|---|---|---|
| [[/notes/a_project_done.md]] | $10.0 | 50 | 50.0 |
                """;

        assertEquals(table, expected);
    }

    public  void testTagsTransform() {
        final String testLuaBaseYaml = """
        formulas:
          formatted_price: 'return string.format("$%.2f", getPropertyValue(file, "price", 0))'
          ppu: 'return getPropertyValue(file, "price", 0) * 5'
        views:
          - type: table
            name: "My table"
            limit: 10
            filters:
              and:
                - 'hasPropertyValue(file, "tags", "book")'
            order:
              - '"[[" .. getPropertyValue(file, "filePath") .. "]]", "Path"'
              - 'table.concat( (function() local t = {}; local tags_array = (getPropertyValue(file, \"tags\") or {}):toArray(); for i=1, #tags_array do local v = tags_array[i]; table.insert(t, \"[\" .. v .. \"](/tags/\" .. v .. \")\") end; return t end)(), \", \"), "tags"'
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("/notes/my_book_note.md", new PksFile("/notes/my_book_note.md", new HashMap<>() {{
            put("filePath", "/notes/my_book_note.md");
            put("tags", new ArrayList<>(Arrays.asList("book", "textbook")));
        }}));
        files.put("/notes/another_book_note.md", new PksFile("/notes/another_book_note.md", new HashMap<>() {{
            put("filePath", "/notes/another_book_note.md");
            put("tags", new ArrayList<>(Arrays.asList("book", "fiction")));
        }}));
        files.put("/notes/a_project_done.md", new PksFile("/notes/a_project_done.md", new HashMap<>() {{
            put("filePath", "/notes/a_project_done.md");
            put("tags", new ArrayList<>(Arrays.asList("project", "work")));
        }}));
        files.put("/notes/Required Reading/a_required_reading_note.md", new PksFile("/notes/Required Reading/a_required_reading_note.md", new HashMap<>() {{
            put("filePath", "/notes/Required Reading/a_required_reading_note.md");
            put("tags", new ArrayList<>(Arrays.asList("book", "required")));
        }}));

        YamlBaseParser ybp = new YamlBaseParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor(ybp.parse(testLuaBaseYaml));
        String actual = luaBaseProcessor.process(files);
        String expected = """
| Path | tags |
|---|---|
| [[/notes/my_book_note.md]] | [book](/tags/book), [textbook](/tags/textbook) |
| [[/notes/another_book_note.md]] | [book](/tags/book), [fiction](/tags/fiction) |
| [[/notes/Required Reading/a_required_reading_note.md]] | [book](/tags/book), [required](/tags/required) |
                """;

        assertEquals(expected, actual);
    }

    public  void testSingleSort() {
        final String testLuaBaseYaml = """
        formulas:
          formatted_price: 'return string.format("$%.2f", getPropertyValue(file, "price", 0))'
          ppu: 'return getPropertyValue(file, "price", 0) * 5'
        views:
          - type: table
            name: "My table"
            limit: 10
            filters:
              and:
                - 'hasPropertyValue(file, "tag", "book")'
            order:
              - '"[[" .. getPropertyValue(file, "filePath") .. "]]", "Path"'
              - 'formatted_price(file), "Price"'
              - 'ppu(file), "PPU"'
              - 'toFixed(ppu(file), 0), "toFixed"'
            sort:
              - property: price
                direction: ASC
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("/notes/my_book_note.md", new PksFile("/notes/my_book_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/my_book_note.md");
            put("tag", new ArrayList<>(Arrays.asList("book", "textbook")));
            put("status", "done");
            put("price", 10.5095);
            put("age", 2);
        }}));
        files.put("/notes/another_book_note.md", new PksFile("/notes/another_book_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/another_book_note.md");
            put("tag", new ArrayList<>(Arrays.asList("book", "fiction")));
            put("status", "ne");
            put("price", 3.00);
            put("age", 4);
        }}));
        files.put("/notes/a_project_done.md", new PksFile("/notes/a_project_done.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/a_project_done.md");
            put("tag", new ArrayList<>(Arrays.asList("project", "work")));
            put("status", "de");
            put("price", 10);
            put("age", 1);
        }}));
        files.put("/notes/Required Reading/a_required_reading_note.md", new PksFile("/notes/Required Reading/a_required_reading_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/Required Reading/a_required_reading_note.md");
            put("tag", new ArrayList<>(Arrays.asList("book", "required")));
            put("status", "in-progress");
            put("price", 6.00);
            put("age", 6);
        }}));

        YamlBaseParser ybp = new YamlBaseParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor(ybp.parse(testLuaBaseYaml));
        String actual = luaBaseProcessor.process(files);
        String expected = """
| Path | Price | PPU | toFixed |
|---|---|---|---|
| [[/notes/another_book_note.md]] | $3.0 | 15 | 15.0 |
| [[/notes/Required Reading/a_required_reading_note.md]] | $6.0 | 30 | 30.0 |
| [[/notes/my_book_note.md]] | $10.5095 | 52.5475 | 52.5475 |
                """;

        assertEquals(expected, actual);
    }
}
