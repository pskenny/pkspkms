package io.pskenny.pkspkms.luabase;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                  - 'table.concat( (function() local t = {}; local tags_array = (getPropertyValue(file, \"tags\") or {}); for i=1, #tags_array do local v = tags_array[i]; table.insert(t, "[" .. v .. "](/tags/" .. v .. ")") end; return t end)(), \", \"), "tags"'
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
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("price", 10.5095);
        }}));
        files.put("b.md", new PksFile("b.md", new HashMap<>() {{
            put("price", 3.00);
        }}));
        files.put("d.md", new PksFile("d.md", new HashMap<>() {{
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
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("price", 10.5095);
        }}));
        files.put("b.md", new PksFile("b.md", new HashMap<>() {{
            put("price", 3.00);
        }}));
        files.put("c.md", new PksFile("c.md", new HashMap<>() {{
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

    @Test
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
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("price", 10.5095);
        }}));
        files.put("b.md", new PksFile("b.md", new HashMap()));
        files.put("c.md", new PksFile("c.md", new HashMap<>() {{
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
        assertEquals(expectedWithNil, actualWithNil);

        String actualWithEmptyString = luaBaseProcessor.process(ybp.parse(testLuaBaseYamlWithEmptyStringProperty), files);
        String expectedWithEmptyString = """
            | Price |
            |---|
            | 6 |
            | 10.5095 |
            |  |
            """;
        assertEquals(expectedWithEmptyString, actualWithEmptyString);
    }

    @Test
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
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("price", 10.5095);
        }}));
        files.put("b.md", new PksFile("b.md", new HashMap()));
        files.put("c.md", new PksFile("c.md", new HashMap<>() {{
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
        assertEquals(expected, actual);
    }

    @Test
    void viewSpecFrom_missingViews_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new LuaBaseProcessor().process(Map.of(), Map.of()));
    }

    @Test
    void filterYamlToExpression_unexpectedType_throws() {
        LuaBaseProcessor p = new LuaBaseProcessor();
        assertThrows(IllegalArgumentException.class,
                () -> p.filterYamlToExpression(42));
    }

    @Test
    void process_cardsView_rendersAsTable() {
        Map<String, Object> spec = new YamlParser().parse("""
            views:
              - type: cards
            """);
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>()));

        String result = new LuaBaseProcessor().process(spec, files);

        String expected = """
            | Path |
            |---|
            | a.md |
            """;
        assertEquals(expected, result);
    }

    @Test
    void process_mapView_rendersAsTable() {
        Map<String, Object> spec = new YamlParser().parse("""
            views:
              - type: map
            """);
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>()));

        String result = new LuaBaseProcessor().process(spec, files);

        String expected = """
            | Path |
            |---|
            | a.md |
            """;
        assertEquals(expected, result);
    }

    @Test
    void process_unknownViewType_throws() {
        Map<String, Object> spec = new YamlParser().parse("""
            views:
              - type: graffle
            """);
        assertThrows(IllegalArgumentException.class,
                () -> new LuaBaseProcessor().process(spec, Map.of()));
    }

    @Test
    void process_sort_nullValuesSortLast() {
        final String yaml = """
            views:
              - type: table
                order:
                  - 'getPropertyValue(file, "price", ""), "Price"'
                sort:
                  - property: price
                    direction: ASC
            """;
        Map<String, PksFile> files = new LinkedHashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>(Map.of("price", 10))));
        files.put("b.md", new PksFile("b.md", new HashMap<>()));
        files.put("c.md", new PksFile("c.md", new HashMap<>(Map.of("price", 3))));

        String result = new LuaBaseProcessor().process(new YamlParser().parse(yaml), files);
        String expected = """
            | Price |
            |---|
            | 3 |
            | 10 |
            |  |
            """;
        assertEquals(expected, result);
    }

    @Test
    void processOverCorpus_appliesSort() {
        List<PksFile> corpus = List.of(
                new PksFile("b.md", new HashMap<>(Map.of("price", 10))),
                new PksFile("a.md", new HashMap<>(Map.of("price", 3)))
        );

        final String yaml = """
            views:
              - type: table
                order:
                  - 'getPropertyValue(file, "price"), "Price"'
                sort:
                  - property: price
                    direction: ASC
            """;

        String result = new LuaBaseProcessor().processOverCorpus(new YamlParser().parse(yaml), corpus);
        assertTrue(result.indexOf("| 3 |") < result.indexOf("| 10 |"));
    }

    @Test
    void process_sort_mixedNumberTypes_numericOrder() {
        final String yaml = """
            views:
              - type: table
                order:
                  - 'getPropertyValue(file, "price"), "Price"'
                sort:
                  - property: price
                    direction: ASC
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("int.md", new PksFile("int.md", new HashMap<String, Object>() {{
            put("price", 10);
        }}));
        files.put("double.md", new PksFile("double.md", new HashMap<String, Object>() {{
            put("price", 3.5);
        }}));

        String result = new LuaBaseProcessor().process(new YamlParser().parse(yaml), files);

        assertTrue(result.indexOf("| 3.5 |") < result.indexOf("| 10 |"), "3.5 must sort before 10");
    }

    @Test
    void process_sort_mixedDateTypes_noCrash() {
        final String yaml = """
            views:
              - type: table
                order:
                  - 'getPropertyValue(file, "creationDate"), "Created"'
                sort:
                  - property: creationDate
                    direction: ASC
            """;
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new YamlParser().parse("creationDate: 2025-08-21")));
        files.put("b.md", new PksFile("b.md", new HashMap<String, Object>() {{
            put("creationDate", "2025-08-22");
        }}));

        String result = new LuaBaseProcessor().process(new YamlParser().parse(yaml), files);

        assertTrue(result.indexOf("| 2025-08-21 |") < result.indexOf("| 2025-08-22 |"),
                "Mixed date types must render and sort deterministically");
    }

    // --- B2 regression: sandboxed coercion must keep list-valued properties working ---

    @Test
    public void tagsSnippetRendersHashtagsOverListProperty() {
        // The generated tags column (LuaBaseProcessor tags template) previously
        // relied on luajava-provided `:toArray()` — denied by the sandbox (B2)
        Map<String, PksFile> files = new HashMap<>();
        files.put("file.md", new PksFile("file.md", new HashMap<>() {{
            put("tags", List.of("Meta", "Hostile"));
        }}));

        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        org.luaj.vm2.LuaValue result = interpreter.evaluateLuaExpression(
                "table.concat( (function() local t = {}; local tags_array = getPropertyValue(file, \"tags\", nil); "
                        + "if tags_array then for i=1, #tags_array do local v = tags_array[i]; "
                        + "table.insert(t, \"#\" .. tostring(v)) end end; return t end)(), \" \")",
                files.get("file.md").getMutableProperties());
        assertEquals("#Meta #Hostile", result.tojstring(), "tags list must render without luajava");
    }

    @Test
    public void listMembershipWorksInSandbox() {
        Map<String, Object> file = new HashMap<>() {{
            put("tags", List.of("x", "y"));
        }};
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        org.junit.jupiter.api.Assertions.assertTrue(
                interpreter.evaluateLuaExpression("hasPropertyValue(file, \"tags\", \"y\")", file).toboolean(),
                "list membership must work without luajava method calls");
        org.junit.jupiter.api.Assertions.assertFalse(
                interpreter.evaluateLuaExpression("hasPropertyValue(file, \"tags\", \"z\")", file).toboolean());
    }

    // --- characterization: corpus rendering must keep the SQL-path outputs ---
    // (written against the current lua_eval path; the in-memory single-pass
    // refactor must reproduce these byte-for-byte)

    private final io.pskenny.pkspkms.luabase.LuaBaseProcessor charProcessor = new io.pskenny.pkspkms.luabase.LuaBaseProcessor();

    @Test
    public void embedRender_characterization_andFilterWithSort() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("embed-char");
        io.pskenny.pkspkms.test.FileUtil.createFile(dir, "alpha.md", Map.of("tags", List.of("x"), "price", 20), "");
        io.pskenny.pkspkms.test.FileUtil.createFile(dir, "beta.md", Map.of("tags", List.of("x"), "price", 45), "");
        io.pskenny.pkspkms.test.FileUtil.createFile(dir, "gamma.md", Map.of(), "");

        try (SQLitePksFileRepository repository = characterRepo(dir)) {
            repository.loadDirectoryIntoRepository();

            final String yaml = """
                views:
                  - type: table
                    filters:
                      and:
                        - 'hasPropertyValue(file, "tags", "x")'
                    order:
                      - 'getPropertyValue(file, "filePath"), "Path"'
                    sort:
                      - property: filePath
                        direction: asc
                """;
            String rendered = charProcessor.processOverCorpus(new YamlParser().parse(yaml), repository.loadCorpus());
            String expected = """
                | Path |
                |---|
                | alpha.md |
                | beta.md |
                """;
            assertEquals(expected, rendered, "and-filter + sort characterization");
        }
    }

    @Test
    public void embedRender_characterization_orFilterMatchesFieldAbsentFile() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("embed-char");
        io.pskenny.pkspkms.test.FileUtil.createFile(dir, "alpha.md", Map.of("tags", List.of("x")), "");
        io.pskenny.pkspkms.test.FileUtil.createFile(dir, "gamma.md", Map.of(), "");

        try (SQLitePksFileRepository repository = characterRepo(dir)) {
            repository.loadDirectoryIntoRepository();

            final String yaml = """
                views:
                  - type: table
                    filters:
                      or:
                        - 'getPropertyValue(file, "filePath", "") == "gamma.md"'
                    order:
                      - 'getPropertyValue(file, "filePath"), "Path"'
                """;
            String rendered = charProcessor.processOverCorpus(new YamlParser().parse(yaml), repository.loadCorpus());
            String expected = """
                | Path |
                |---|
                | gamma.md |
                """;
            assertEquals(expected, rendered, "field-absent files match via their other properties");
        }
    }

    @Test
    public void embedRender_characterization_invalidFilterRendersHeadersOnly() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("embed-char");
        io.pskenny.pkspkms.test.FileUtil.createFile(dir, "alpha.md", Map.of(), "");

        try (SQLitePksFileRepository repository = characterRepo(dir)) {
            repository.loadDirectoryIntoRepository();

            final String yaml = """
                views:
                  - type: table
                    filters:
                      and:
                        - 'this is ) not ( valid'
                    order:
                      - 'getPropertyValue(file, "filePath"), "Path"'
                """;
            String rendered = charProcessor.processOverCorpus(new YamlParser().parse(yaml), repository.loadCorpus());
            String expected = """
                | Path |
                |---|
                """;
            assertEquals(expected, rendered, "invalid filter renders headers only (old skip behavior)");
        }
    }

    private SQLitePksFileRepository characterRepo(Path dir) throws Exception {
        // Inline lua_eval matching SQLiteLuaConnector's callback contract — the
        // connector itself is desktop-scoped and unavailable to core tests
        java.util.function.Consumer<java.sql.Connection> registrar = conn -> {
            try {
                org.sqlite.Function.create(conn, "lua_eval", new org.sqlite.Function() {
                    @Override
                    protected void xFunc() throws java.sql.SQLException {
                        try {
                            String expression = value_text(0);
                            Map<String, Object> props = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readValue(value_text(1), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                            result(new LuaBaseInterpreter().evaluateExpression(expression, props) ? 1 : 0);
                        } catch (Throwable t) {
                            result(0);
                        }
                    }
                });
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        };
        return new io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository(
                "jdbc:sqlite:test_embed_char.db", registrar, new io.pskenny.pkspkms.io.fs.JavaFileSystem(dir.toFile()));
    }
}
