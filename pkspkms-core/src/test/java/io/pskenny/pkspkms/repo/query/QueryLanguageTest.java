package io.pskenny.pkspkms.repo.query;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueryLanguageTest {

    private final QueryParser parser = new QueryParser();
    private final QueryCompiler compiler = new QueryCompiler();

    // --- parser: clause typing (flat Lucene semantics) ---

    @Test
    void bareTermsAreShould() {
        Query q = parser.parse("a b c");
        assertEquals(3, q.clauses().size());
        assertTrue(q.clauses().stream().allMatch(c -> c.occur() == Query.Occur.SHOULD));
    }

    @Test
    void andPromotesBothAdjacentClauses() {
        Query q = parser.parse("a AND b");
        assertEquals(Query.Occur.MUST, q.clauses().get(0).occur());
        assertEquals(Query.Occur.MUST, q.clauses().get(1).occur());
    }

    @Test
    void mixedOperatorsClassicQuirk() {
        // a AND b OR c: a MUST, b MUST, c SHOULD
        Query q = parser.parse("a AND b OR c");
        assertEquals(Query.Occur.MUST, q.clauses().get(0).occur());
        assertEquals(Query.Occur.MUST, q.clauses().get(1).occur());
        assertEquals(Query.Occur.SHOULD, q.clauses().get(2).occur());
    }

    @Test
    void orDoesNotDemoteMust() {
        Query q = parser.parse("+a OR b");
        assertEquals(Query.Occur.MUST, q.clauses().get(0).occur());
        assertEquals(Query.Occur.SHOULD, q.clauses().get(1).occur());
    }

    @Test
    void minusAndNotAreMustNot() {
        assertEquals(Query.Occur.MUST_NOT, parser.parse("-a").clauses().get(0).occur());
        assertEquals(Query.Occur.MUST_NOT, parser.parse("NOT a").clauses().get(0).occur());
        assertEquals(Query.Occur.MUST_NOT, parser.parse("!a").clauses().get(0).occur());
    }

    @Test
    void plusIsMust() {
        assertEquals(Query.Occur.MUST, parser.parse("+a").clauses().get(0).occur());
    }

    @Test
    void ampersandAndPipeAliases() {
        assertEquals(Query.Occur.MUST, parser.parse("a && b").clauses().get(0).occur());
        assertEquals(Query.Occur.SHOULD, parser.parse("a || b").clauses().get(0).occur());
    }

    // --- parser: node shapes ---

    @Test
    void fieldTerm() {
        QueryNode node = parser.parse("tags:PKSPKMS").clauses().get(0).node();
        assertEquals(new QueryNode.FieldEq("tags", "PKSPKMS"), node);
    }

    @Test
    void fieldPhrase() {
        QueryNode node = parser.parse("title:\"rock and roll\"").clauses().get(0).node();
        assertEquals(new QueryNode.FieldEq("title", "rock and roll"), node);
    }

    @Test
    void fieldWildcardAndPrefix() {
        assertEquals(new QueryNode.FieldWildcard("filePath", "*.md"),
                parser.parse("filePath:*.md").clauses().get(0).node());
        assertEquals(new QueryNode.FieldWildcard("filePath", "Notes*"),
                parser.parse("filePath:Notes*").clauses().get(0).node());
    }

    @Test
    void fieldExists() {
        assertEquals(new QueryNode.FieldExists("tags"),
                parser.parse("tags:*").clauses().get(0).node());
    }

    @Test
    void ranges() {
        assertEquals(new QueryNode.FieldRange("price", "1", "5", true, true),
                parser.parse("price:[1 TO 5]").clauses().get(0).node());
        assertEquals(new QueryNode.FieldRange("price", "1", "5", false, false),
                parser.parse("price:{1 TO 5}").clauses().get(0).node());
        assertEquals(new QueryNode.FieldRange("date", "2020", null, true, true),
                parser.parse("date:[2020 TO *]").clauses().get(0).node());
    }

    @Test
    void fieldGroup() {
        assertEquals(new QueryNode.Or(List.of(
                        new QueryNode.FieldEq("status", "todo"),
                        new QueryNode.FieldEq("status", "done"))),
                parser.parse("status:(todo OR done)").clauses().get(0).node());
        assertEquals(new QueryNode.And(List.of(
                        new QueryNode.FieldEq("status", "todo"),
                        new QueryNode.FieldEq("status", "done"))),
                parser.parse("status:(todo AND done)").clauses().get(0).node());
    }

    @Test
    void bareTermsSearchAnyField() {
        assertEquals(new QueryNode.AnyField(new QueryNode.FieldEq("_all", "hello")),
                parser.parse("hello").clauses().get(0).node());
        assertEquals(new QueryNode.AnyField(new QueryNode.FieldWildcard("_all", "hel*")),
                parser.parse("hel*").clauses().get(0).node());
        assertEquals(new QueryNode.AllMatch(), parser.parse("*").clauses().get(0).node());
    }

    @Test
    void bareBoolGroup() {
        QueryNode.Bool bool = (QueryNode.Bool) parser.parse("(a b)").clauses().get(0).node();
        assertEquals(2, bool.clauses().size());
    }

    @Test
    void escapesAndPathWords() {
        assertEquals(new QueryNode.FieldEq("my:key", "x"),
                parser.parse("my\\:key:x").clauses().get(0).node());
        assertEquals(new QueryNode.FieldEq("filePath", "Notes/x.md"),
                parser.parse("filePath:Notes/x.md").clauses().get(0).node());
        assertEquals(new QueryNode.FieldEq("title", "50%"),
                parser.parse("title:50%").clauses().get(0).node());
    }

    @Test
    void emptyQueryMatchesAll() {
        assertEquals("1=1", compiler.compile(parser.parse("  ")).sql());
    }

    // --- parser: errors ---

    @Test
    void unmatchedPhraseThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("title:\"unterminated"));
    }

    @Test
    void unclosedGroupThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("(a b"));
    }

    @Test
    void rangeWithoutToThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("price:[1 5]"));
    }

    @Test
    void fuzzyThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("term~2"));
    }

    @Test
    void danglingOperatorThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse("a AND"));
    }

    @Test
    void colonWithoutFieldThrows() {
        assertThrows(QueryParseException.class, () -> parser.parse(":x"));
    }

    // --- compiler: SQL + params ---

    @Test
    void compilesFieldEqWithBoundParams() {
        CompiledQuery q = compiler.compile(parser.parse("tags:PKSPKMS"));
        assertEquals("(json_extract(properties, ?) = ? OR EXISTS (SELECT 1 FROM json_each(properties, ?) WHERE value = ?))", q.sql());
        assertEquals(List.of("$.\"tags\"", "PKSPKMS", "$.\"tags\"", "PKSPKMS"), q.params());
    }

    @Test
    void compilesKebabCaseKey() {
        CompiledQuery q = compiler.compile(parser.parse("my-key:x"));
        assertTrue(q.params().contains("$.\"my-key\""));
    }

    @Test
    void compilesWildcardWithEscaping() {
        CompiledQuery q = compiler.compile(parser.parse("f:100%_a*b"));
        assertTrue(q.sql().contains("LIKE ? ESCAPE '\\'"));
        assertTrue(q.params().contains("100\\%\\_a%b"));
    }

    @Test
    void compilesShouldsAsOr() {
        CompiledQuery q = compiler.compile(parser.parse("a b"));
        assertEquals("(EXISTS (SELECT 1 FROM json_each(properties) WHERE value LIKE ? ESCAPE '\\') OR EXISTS (SELECT 1 FROM json_each(properties) WHERE value LIKE ? ESCAPE '\\'))", q.sql());
        assertEquals(List.of("%a%", "%b%"), q.params());
    }

    @Test
    void compilesMustsAndDropsShoulds() {
        CompiledQuery q = compiler.compile(parser.parse("+a b"));
        assertFalse(q.sql().contains(" OR "), "SHOULDs are ignored when MUSTs exist");
    }

    @Test
    void compilesMustNotsAppended() {
        CompiledQuery q = compiler.compile(parser.parse("a -b"));
        assertTrue(q.sql().contains(" AND NOT COALESCE(("));
    }

    @Test
    void compilesPureNotAnchored() {
        CompiledQuery q = compiler.compile(parser.parse("NOT a"));
        assertTrue(q.sql().startsWith("1=1 AND NOT COALESCE(("));
    }

    @Test
    void compilesRange() {
        CompiledQuery q = compiler.compile(parser.parse("price:[1 TO 5]"));
        assertTrue(q.sql().contains("json_extract(properties, ?) >= ?"));
        assertTrue(q.sql().contains("json_extract(properties, ?) <= ?"));
        assertTrue(q.params().contains(1.0));
        assertTrue(q.params().contains(5.0));
    }

    @Test
    void compilesStarStarAsMatchAll() {
        assertEquals("1=1", compiler.compile(parser.parse("*:*")).sql());
    }

    @Test
    void compilesEmptyAsMatchAll() {
        assertEquals("1=1", compiler.compile(parser.parse("")).sql());
    }

    // --- semantics: executed against real SQLite ---

    private record Row(String path, String properties) {}

    private List<String> execute(CompiledQuery q, List<Row> rows) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE FILES (file_path TEXT, properties TEXT)");
            for (Row r : rows) {
                try (PreparedStatement ins = conn.prepareStatement("INSERT INTO FILES VALUES (?, ?)")) {
                    ins.setString(1, r.path());
                    ins.setString(2, r.properties());
                    ins.executeUpdate();
                }
            }
            String sql = "SELECT file_path FROM FILES WHERE " + q.sql();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < q.params().size(); i++) {
                    pstmt.setObject(i + 1, q.params().get(i));
                }
                List<String> paths = new ArrayList<>();
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        paths.add(rs.getString("file_path"));
                    }
                }
                return paths;
            }
        }
    }

    private final List<Row> fixture = List.of(
            new Row("a.md", "{\"filePath\": \"a.md\", \"tags\": [\"x\", \"y\"], \"price\": 20}"),
            new Row("b.md", "{\"filePath\": \"b.md\", \"tags\": \"x\"}"),
            new Row("c.md", "{\"filePath\": \"c.md\"}"),
            new Row("d.md", "{\"filePath\": \"d.md\", \"my-key\": \"v\"}"),
            new Row("e.md", "{\"filePath\": \"e.md\", \"title\": \"Hello World\"}"));

    @Test
    void semanticScalarOrArray() throws SQLException {
        List<String> paths = execute(compiler.compile(parser.parse("tags:x")), fixture);
        assertEquals(List.of("a.md", "b.md"), paths);
    }

    @Test
    void semanticNotExcludes() throws SQLException {
        List<String> paths = execute(compiler.compile(parser.parse("tags:x NOT tags:y")), fixture);
        assertEquals(List.of("b.md"), paths);
    }

    // B56 regression: NOT must match field-absent rows, not drop them.
    // SQL three-valued logic used to turn NOT into NULL when the property was missing.
    private final List<Row> notFixture = List.of(
            new Row("hit.md",      "{\"filePath\": \"hit.md\", \"type\": \"Bookmark\"}"),
            new Row("excluded.md", "{\"filePath\": \"excluded.md\", \"type\": \"Type_Page\"}"),
            new Row("absent.md",   "{\"filePath\": \"absent.md\"}"),
            new Row("nullval.md",  "{\"filePath\": \"nullval.md\", \"description\": null}"));

    @Test
    void semanticNotEqMatchesFieldAbsent() throws SQLException {
        List<String> paths = execute(compiler.compile(parser.parse("not type:Type_Page")), notFixture);
        assertEquals(List.of("hit.md", "absent.md", "nullval.md"), paths);
    }

    @Test
    void semanticNotWildcardMatchesFieldAbsent() throws SQLException {
        // *mark* matches "Bookmark" only: hits the positive path, absent rows must survive
        List<String> paths = execute(compiler.compile(parser.parse("not type:*mark*")), notFixture);
        assertEquals(List.of("excluded.md", "absent.md", "nullval.md"), paths);
    }

    @Test
    void semanticNotRangeMatchesFieldAbsent() throws SQLException {
        List<Row> rows = List.of(
                new Row("in.md",     "{\"filePath\": \"in.md\", \"price\": 20}"),
                new Row("out.md",    "{\"filePath\": \"out.md\", \"price\": 200}"),
                new Row("absent.md", "{\"filePath\": \"absent.md\"}"));
        List<String> paths = execute(compiler.compile(parser.parse("not price:[1 TO 100]")), rows);
        assertEquals(List.of("out.md", "absent.md"), paths);
    }

    @Test
    void semanticNotExistsStillCorrect() throws SQLException {
        // NOT field:* was already null-correct; pin that the fix keeps it so
        List<String> paths = execute(compiler.compile(parser.parse("not type:*")), notFixture);
        assertEquals(List.of("absent.md", "nullval.md"), paths);
    }

    @Test
    void semanticNotTypedRangeMatchesFieldAbsent() throws SQLException {
        // Declared number type casts the probe to REAL, which is also NULL for absent fields
        List<Row> rows = List.of(
                new Row("in.md",     "{\"filePath\": \"in.md\", \"price\": 20}"),
                new Row("absent.md", "{\"filePath\": \"absent.md\"}"));
        List<String> paths = execute(
                compiler.compile(parser.parse("not price:[1 TO 30]"), priceNumber), rows);
        assertEquals(List.of("absent.md"), paths);
    }

    @Test
    void semanticKebabCase() throws SQLException {
        List<String> paths = execute(compiler.compile(parser.parse("my-key:v")), fixture);
        assertEquals(List.of("d.md"), paths);
    }

    @Test
    void semanticBareTermSearchesAllFields() throws SQLException {
        List<String> paths = execute(compiler.compile(parser.parse("Hello")), fixture);
        assertEquals(List.of("e.md"), paths);
    }

    @Test
    void semanticNumericField() throws SQLException {
        List<String> paths = execute(compiler.compile(parser.parse("price:20")), fixture);
        assertEquals(List.of("a.md"), paths);
    }

    @Test
    void semanticWildcardOverField() throws SQLException {
        List<String> paths = execute(compiler.compile(parser.parse("filePath:*.md")), fixture);
        assertEquals(5, paths.size());
    }

    // --- B53: declared types from .obsidian/types.json ---

    private final PropertyTypes priceNumber = PropertyTypes.of(Map.of("price", "number"));

    private final List<Row> rangeFixture = List.of(
            new Row("r1.md", "{\"filePath\": \"r1.md\", \"price\": 20}"),
            new Row("r2.md", "{\"filePath\": \"r2.md\", \"price\": 45}"),
            new Row("r3.md", "{\"filePath\": \"r3.md\", \"price\": \"25\"}"),
            new Row("r4.md", "{\"filePath\": \"r4.md\"}"));

    @Test
    void undeclaredNumericRangeKeepsLegacyAffinity() throws SQLException {
        // Documented contract: string-stored numbers do NOT match undeclared ranges
        List<String> paths = execute(compiler.compile(parser.parse("price:[1 TO 30]")), rangeFixture);
        assertEquals(List.of("r1.md"), paths);
    }

    @Test
    void declaredNumberRangeMatchesStringStoredValues() throws SQLException {
        List<String> paths = execute(
                compiler.compile(parser.parse("price:[1 TO 30]"), priceNumber), rangeFixture);
        assertEquals(List.of("r1.md", "r3.md"), paths);
    }

    @Test
    void declaredNumberInclusiveBoundary() throws SQLException {
        List<String> paths = execute(
                compiler.compile(parser.parse("price:[20 TO 20]"), priceNumber), rangeFixture);
        assertEquals(List.of("r1.md"), paths);
    }

    @Test
    void declaredNumberExclusiveBounds() throws SQLException {
        List<String> paths = execute(
                compiler.compile(parser.parse("price:{20 TO 45}"), priceNumber), rangeFixture);
        assertEquals(List.of("r3.md"), paths);
    }

    @Test
    void declaredNumberOpenUpperBound() throws SQLException {
        List<String> paths = execute(
                compiler.compile(parser.parse("price:[40 TO *]"), priceNumber), rangeFixture);
        assertEquals(List.of("r2.md"), paths);
    }

    @Test
    void declaredDateRangeOnTextDates() throws SQLException {
        PropertyTypes types = PropertyTypes.of(Map.of("creationDate", "date"));
        List<Row> dated = List.of(
                new Row("in.md", "{\"filePath\": \"in.md\", \"creationDate\": \"2025-06-15\"}"),
                new Row("out.md", "{\"filePath\": \"out.md\", \"creationDate\": \"2024-12-31\"}"));
        List<String> paths = execute(
                compiler.compile(parser.parse("creationDate:[2025-01-01 TO 2025-12-31]"), types), dated);
        assertEquals(List.of("in.md"), paths);
    }

    @Test
    void typedFieldEqCoerces() throws SQLException {
        List<String> paths = execute(
                compiler.compile(parser.parse("price:20"), priceNumber), rangeFixture);
        assertEquals(List.of("r1.md"), paths);
    }

    @Test
    void compilesRangeExactParamOrder() {
        CompiledQuery q = compiler.compile(parser.parse("price:[1 TO 50]"));
        assertEquals(List.of("$.\"price\"", 1.0, "$.\"price\"", 1.0, "$.\"price\"", 50.0, "$.\"price\"", 50.0),
                q.params());
    }
}
