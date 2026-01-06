package io.github.pskenny.luabase;

import io.github.pskenny.io.PksFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
More filters that don't work:
- bookmark.contains("Things") - contains function not implemented
- bookmark.containsAny("Things", "Music") - containsAny with multiple values not implemented
- type == ["some_type"] - property equals arrays not implemented
- file.inFolder("images/blah") - inFolder not implemented

- what to do with cards?
 */
public class LuaBaseProcessorTest {
    @Test
    public  void testProcess_withTableView_returnTable() {
        final String testLuaBaseYaml = """
            views:
              - type: table
                name: "My table"
                order:
                  - 'getPropertyValue(file, "property"), "property"'
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("file.md", new PksFile("file.md", new HashMap<>() {{
            put("property", "value");
        }}));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();
        String table = luaBaseProcessor.process(ybp.parse(testLuaBaseYaml), files);
        String expected = """
            | property |
            |---|
            | value |
            """;
        assertEquals(expected, table);
    }

    @Test
    public  void testProcess_withTableView_andFilePathFilter_returnOneFile() {
        final String testLuaBaseYaml = """
            views:
              - type: table
                name: "My table"
                filters:
                  and:
                    - hasPropertyValue(file, "filePath", "a_project_done.md")
                order:
                  - 'getPropertyValue(file, "filePath"), "Path"'
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("another_book_note.md", new PksFile("another_book_note.md", new HashMap<>() {{
            put("price", 3.00);
        }}));
        files.put("a_project_done.md", new PksFile("a_project_done.md", new HashMap<>() {{
            put("price", 10);
        }}));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();

        String table = luaBaseProcessor.process(ybp.parse(testLuaBaseYaml), files);
        String expected = """
            | Path |
            |---|
            | a_project_done.md |
            """;
        assertEquals(expected, table);
    }

    @Test
    public  void testProcess_withTableView_andOrder_returnCorrectOrderedColumns_withCorrectColumnNames() {
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
                    - hasPropertyValue(file, "filePath", "a_project_done.md")
                order:
                  - '"[[" .. getPropertyValue(file, "filePath") .. "]]", "Path"'
                  - 'formatted_price(file), "Price"'
                  - 'ppu(file), "PPU"'
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("another_book_note.md", new PksFile("another_book_note.md", new HashMap<>() {{
            put("price", 3.00);
        }}));
        files.put("a_project_done.md", new PksFile("a_project_done.md", new HashMap<>() {{
            put("price", 10);
        }}));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();
        String table = luaBaseProcessor.process(ybp.parse(testLuaBaseYaml), files);
        String expected = """
            | Path | Price | PPU |
            |---|---|---|
            | [[a_project_done.md]] | $10.0 | 50 |
            """;

        assertEquals(expected, table);
    }

    @Test
    public  void whenProcess_withTableView_andFormulas_returnFunctionCalledValues() {
        final String testLuaBaseYaml = """
            formulas:
              ppu: 'return getPropertyValue(file, "price", 0) * 5'
            views:
              - type: table
                name: "My table"
                order:
                  - 'getPropertyValue(file, "filePath"), "Path"'
                  - 'getPropertyValue(file, "price"), "Price"'
                  - 'ppu(file), "PPU"'
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("a_project_done.md", new PksFile("a_project_done.md", new HashMap<>() {{
            put("price", 10);
        }}));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();
        String table = luaBaseProcessor.process(ybp.parse(testLuaBaseYaml), files);
        String expected = """
            | Path | Price | PPU |
            |---|---|---|
            | a_project_done.md | 10 | 50 |
            """;

        assertEquals(expected, table);
    }

    @Test
    public  void testProcess_withTable_andOrderDefinedFunction() {
        final String testLuaBaseYaml = """
            views:
              - type: table
                name: "My table"
                order:
                  - 'getPropertyValue(file, "filePath"), "Path"'
                  - 'table.concat( (function() local t = {}; local tags_array = (getPropertyValue(file, \"tags\") or {}):toArray(); for i=1, #tags_array do local v = tags_array[i]; table.insert(t, \"[\" .. v .. \"](/tags/\" .. v .. \")\") end; return t end)(), \", \"), "tags"'
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("my_book_note.md", new PksFile("my_book_note.md", new HashMap<>() {{
            put("tags", new ArrayList<>(Arrays.asList("book", "textbook")));
        }}));
        files.put("another_book_note.md", new PksFile("another_book_note.md", new HashMap<>() {{
            put("tags", new ArrayList<>(Arrays.asList("fiction")));
        }}));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();
        String actual = luaBaseProcessor.process(ybp.parse(testLuaBaseYaml), files);
        String expected = """
            | Path | tags |
            |---|---|
            | another_book_note.md | [fiction](/tags/fiction) |
            | my_book_note.md | [book](/tags/book), [textbook](/tags/textbook) |
            """;

        assertEquals(expected, actual);
    }

    @Test
    public  void testProcess_withTable_andSinglePropertySortAsc_returnSorted() {
        final String testLuaBaseYaml = """
            views:
              - type: table
                name: "My table"
                order:
                  - 'getPropertyValue(file, "price"), "Price"'
                sort:
                  - property: price
                    direction: ASC
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<String, Object>() {{
            put("price", 10.5095);
        }}));
        files.put("b.md", new PksFile("b.md", new HashMap<String, Object>() {{
            put("price", 3.00);
        }}));
        files.put("d.md", new PksFile("d.md", new HashMap<String, Object>() {{
            put("price", 6.00);
        }}));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();
        String actual = luaBaseProcessor.process(ybp.parse(testLuaBaseYaml), files);
        String expected = """
            | Price |
            |---|
            | 3 |
            | 6 |
            | 10.5095 |
            """;
        assertEquals(expected, actual);
    }

    @Test
    public  void testProcess_withTable_andSinglePropertySortDesc_returnSorted() {
        final String testLuaBaseYaml = """
            views:
              - type: table
                name: "My table"
                order:
                  - 'getPropertyValue(file, "price"), "Price"'
                sort:
                  - property: price
                    direction: DESC
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<String, Object>() {{
            put("price", 10.5095);
        }}));
        files.put("b.md", new PksFile("b.md", new HashMap<String, Object>() {{
            put("price", 3.00);
        }}));
        files.put("c.md", new PksFile("c.md", new HashMap<String, Object>() {{
            put("price", 6.00);
        }}));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();
        String actual = luaBaseProcessor.process(ybp.parse(testLuaBaseYaml), files);
        String expected = """
            | Price |
            |---|
            | 10.5095 |
            | 6 |
            | 3 |
            """;
        assertEquals(expected, actual);
    }



    @Test
    public  void testProcess_withTable_andOrderDate_returnsSameDateText() {
        String yaml = """
creationDate: 2025-08-21
                """;
        final String testLuaBaseYaml = """
            views:
              - type: table
                name: "My table"
                order:
                  - 'getPropertyValue(file, "creationDate"), "creationDate"'
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new YamlParser().parse(yaml)));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();
        String actual = luaBaseProcessor.process(ybp.parse(testLuaBaseYaml), files);
        String expected = """
            | creationDate |
            |---|
            | 2025-08-21 |
            """;
        assertEquals(expected, actual);
    }

//    @Test
    public  void testProcess_withTable_andSinglePropertySortAsc_andNullValue_returnSortedNullValueLast() {
        final String testLuaBaseYamlWithNilProperty = """
            views:
              - type: table
                name: "My table"
                order:
                  - 'getPropertyValue(file, "price"), "Price"'
                sort:
                  - property: price
                    direction: ASC
            """;
        final String testLuaBaseYamlWithEmptyStringProperty = """
            views:
              - type: table
                name: "My table"
                order:
                  - 'getPropertyValue(file, "price", ""), "Price"'
                sort:
                  - property: price
                    direction: ASC
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<String, Object>() {{
            put("price", 10.5095);
        }}));
        files.put("b.md", new PksFile("b.md", new HashMap()));
        files.put("c.md", new PksFile("c.md", new HashMap<String, Object>() {{
            put("price", 6.00);
        }}));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();

        String actualWithNil = luaBaseProcessor.process(ybp.parse(testLuaBaseYamlWithNilProperty), files);
        String expectedWithNil = """
            | Price |
            |---|
            | 6 |
            | 10.5095 |
            | nil |
            """;
        // KNOWN ISSUE
//        assertEquals(expectedWithNil, actualWithNil);

        String actualWithEmptyString = luaBaseProcessor.process(ybp.parse(testLuaBaseYamlWithEmptyStringProperty), files);
        String expectedWithEmptyString = """
            | Price |
            |---|
            | 6 |
            | 10.5095 |
            |  |
            """;
        // KNOWN ISSUE
//        assertEquals(expectedWithEmptyString, actualWithEmptyString);
    }

//    @Test
    public  void testProcess_withTable_andSinglePropertySortDesc_andNullValue_returnSortedNullValueLast() {
        final String testLuaBaseYaml = """
            views:
              - type: table
                name: "My table"
                order:
                  - 'getPropertyValue(file, "price", ""), "Price"'
                sort:
                  - property: price
                    direction: DESC
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<String, Object>() {{
            put("price", 10.5095);
        }}));
        files.put("b.md", new PksFile("b.md", new HashMap()));
        files.put("c.md", new PksFile("c.md", new HashMap<String, Object>() {{
            put("price", 6.00);
        }}));

        YamlParser ybp = new YamlParser();
        LuaBaseProcessor luaBaseProcessor = new LuaBaseProcessor();

        String actual = luaBaseProcessor.process(ybp.parse(testLuaBaseYaml), files);
        String expected = """
            | Price |
            |---|
            | 10.5095 |
            | 6 |
            |  |
            """;
        // KNOWN ISSUE
//        assertEquals(expected, actual);
    }
}
