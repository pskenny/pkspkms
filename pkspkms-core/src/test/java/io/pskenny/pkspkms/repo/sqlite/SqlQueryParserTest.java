package io.pskenny.pkspkms.repo.sqlite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SqlQueryParserTest {

    private SqlQueryParser parser;

    @BeforeEach
    void setUp() {
        parser = new SqlQueryParser();
    }

    @Test
    void testEmptyQuery() {
        var result = parser.parseToFullJoinQuery("");
        assertEquals("SELECT * FROM FILES WHERE 1=1", result);
    }

    @Test
    void testWhitespaceOnlyQuery() {
        var result = parser.parseToFullJoinQuery("   ");
        assertEquals("SELECT * FROM FILES WHERE 1=1", result);
    }

    @Test
    void testSimpleEquality() {
        var result = parser.parse("tag=Meta");

        assertEquals("(json_extract(properties, '$.tag') = 'Meta' OR EXISTS (SELECT 1 FROM json_each(properties, '$.tag') WHERE value = 'Meta'))", result.sql());
    }

    @Test
    void testNumericComparison() {
        var result = parser.parse("price>20.5");
        assertEquals("(json_extract(properties, '$.price') > 20.5 OR EXISTS (SELECT 1 FROM json_each(properties, '$.price') WHERE value > 20.5))", result.sql());
    }

    @Test
    void testBooleanAnd() {
        var result = parser.parse("type=Bookmark AND status=Done");
        assertEquals("(json_extract(properties, '$.type') = 'Bookmark' OR EXISTS (SELECT 1 FROM json_each(properties, '$.type') WHERE value = 'Bookmark')) AND (json_extract(properties, '$.status') = 'Done' OR EXISTS (SELECT 1 FROM json_each(properties, '$.status') WHERE value = 'Done'))", result.sql());
    }

    @Test
    void testBooleanOrWithPrecedence() {
        // AND should bind tighter than OR naturally in our recursive descent parser
        var result = parser.parse("a=1 OR b=2 AND c=3");

        assertEquals("(json_extract(properties, '$.a') = 1 OR EXISTS (SELECT 1 FROM json_each(properties, '$.a') WHERE value = 1)) OR (json_extract(properties, '$.b') = 2 OR EXISTS (SELECT 1 FROM json_each(properties, '$.b') WHERE value = 2)) AND (json_extract(properties, '$.c') = 3 OR EXISTS (SELECT 1 FROM json_each(properties, '$.c') WHERE value = 3))", result.sql());
        }

    @Test
    void testParentheses() {
        var result = parser.parse("(tag=Meta OR tag=Archive) AND type=Note");
        assertEquals("((json_extract(properties, '$.tag') = 'Meta' OR EXISTS (SELECT 1 FROM json_each(properties, '$.tag') WHERE value = 'Meta')) OR (json_extract(properties, '$.tag') = 'Archive' OR EXISTS (SELECT 1 FROM json_each(properties, '$.tag') WHERE value = 'Archive'))) AND (json_extract(properties, '$.type') = 'Note' OR EXISTS (SELECT 1 FROM json_each(properties, '$.type') WHERE value = 'Note'))", result.sql());
    }

    @Test
    void testNotOperator() {
        var result = parser.parse("NOT status=Archived");

        assertEquals("NOT (json_extract(properties, '$.status') = 'Archived' OR EXISTS (SELECT 1 FROM json_each(properties, '$.status') WHERE value = 'Archived'))", result.sql());
    }

    @Test
    void testComplexNestedQuery() {
        String query = "project = Internal AND (priority >= 4 OR (impact = High AND NOT status = Blocked))";
        var result = parser.parse(query);

        String expectedSql = "(json_extract(properties, '$.project') = 'Internal' OR EXISTS (SELECT 1 FROM json_each(properties, '$.project') WHERE value = 'Internal')) AND ((json_extract(properties, '$.priority') >= 4 OR EXISTS (SELECT 1 FROM json_each(properties, '$.priority') WHERE value >= 4)) OR ((json_extract(properties, '$.impact') = 'High' OR EXISTS (SELECT 1 FROM json_each(properties, '$.impact') WHERE value = 'High')) AND NOT (json_extract(properties, '$.status') = 'Blocked' OR EXISTS (SELECT 1 FROM json_each(properties, '$.status') WHERE value = 'Blocked'))))";
        assertEquals(expectedSql, result.sql());
    }
}