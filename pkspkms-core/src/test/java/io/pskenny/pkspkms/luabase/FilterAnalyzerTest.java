package io.pskenny.pkspkms.luabase;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The analyzer is the correctness gate for partial coercion: anything it
// wrongly recognizes could change match results, so the recognized battery
// mirrors exactly what NaiveBaseToLuaBaseConverter emits, and a differential
// test asserts partial == full coercion over every case.
class FilterAnalyzerTest {

    private static FilterAnalyzer.Result analyze(String expression) {
        return FilterAnalyzer.analyze(expression);
    }

    // --- Recognized: single leaves -------------------------------------------------

    @Test
    void hasProperty() {
        var result = analyze("hasProperty(file, \"tags\")");
        assertTrue(result.recognized());
        assertEquals(Set.of("tags"), result.referencedProperties());
    }

    @Test
    void hasPropertyValue() {
        var result = analyze("hasPropertyValue(file, \"status\", \"open\")");
        assertTrue(result.recognized());
        assertEquals(Set.of("status"), result.referencedProperties());
    }

    @Test
    void hasPropertyContaining() {
        var result = analyze("hasPropertyContaining(file, \"filePath\", \"a_project\")");
        assertTrue(result.recognized());
        assertEquals(Set.of("filePath"), result.referencedProperties());
    }

    @Test
    void hasPropertyValueInTableConstructor() {
        var result = analyze("hasPropertyValueIn(file, \"tags\", {\"x\", \"y\"})");
        assertTrue(result.recognized());
        assertEquals(Set.of("tags"), result.referencedProperties());
    }

    @Test
    void hasPropertyContainingAllEmptyTable() {
        var result = analyze("hasPropertyContainingAll(file, \"tags\", {})");
        assertTrue(result.recognized());
        assertEquals(Set.of("tags"), result.referencedProperties());
    }

    @Test
    void numericComparisons() {
        for (String fn : List.of("hasPropertyValueGreaterThan", "hasPropertyValueGreaterThanOrEqual",
                "hasPropertyValueLessThan", "hasPropertyValueLessThanOrEqual")) {
            var result = analyze(fn + "(file, \"price\", 4.5)");
            assertTrue(result.recognized(), fn);
            assertEquals(Set.of("price"), result.referencedProperties(), fn);
        }
    }

    @Test
    void fileFieldStartsWithReadsFilePath() {
        for (String field : List.of("name", "basename", "ext", "path")) {
            var result = analyze("fileFieldStartsWith(file, \"" + field + "\", \"a/\")");
            assertTrue(result.recognized(), field);
            assertEquals(Set.of("filePath"), result.referencedProperties(), field);
        }
    }

    @Test
    void fileInFolderReadsFilePath() {
        var result = analyze("fileInFolder(file, \"Books/SciFi\")");
        assertTrue(result.recognized());
        assertEquals(Set.of("filePath"), result.referencedProperties());
    }

    @Test
    void hasEmptyProperty() {
        var result = analyze("hasEmptyProperty(file, \"note\")");
        assertTrue(result.recognized());
        assertEquals(Set.of("note"), result.referencedProperties());
    }

    @Test
    void hasPropertyValueStartsWith() {
        var result = analyze("hasPropertyValueStartsWith(file, \"title\", \"The\")");
        assertTrue(result.recognized());
        assertEquals(Set.of("title"), result.referencedProperties());
    }

    @Test
    void notPrefix() {
        var result = analyze("not hasPropertyValue(file, \"tags\", \"x\")");
        assertTrue(result.recognized());
        assertEquals(Set.of("tags"), result.referencedProperties());
    }

    // --- Recognized: combinations --------------------------------------------------

    @Test
    void andChain() {
        var result = analyze("(hasPropertyValue(file, \"a\", \"x\") and hasProperty(file, \"b\")) "
                + "and hasPropertyContaining(file, \"c\", \"y\")");
        assertTrue(result.recognized());
        assertEquals(Set.of("a", "b", "c"), result.referencedProperties());
    }

    @Test
    void orChainWithNot() {
        var result = analyze("not hasPropertyValue(file, \"a\", \"x\") or hasPropertyValueIn(file, \"b\", {\"p\"})");
        assertTrue(result.recognized());
        assertEquals(Set.of("a", "b"), result.referencedProperties());
    }

    @Test
    void booleanLiterals() {
        var result = analyze("true and (hasProperty(file, \"a\") or false)");
        assertTrue(result.recognized());
        assertEquals(Set.of("a"), result.referencedProperties());
    }

    @Test
    void comparisonOnGetProperty() {
        var result = analyze("getPropertyValue(file, \"filePath\", \"\") == \"gamma.md\"");
        assertTrue(result.recognized());
        assertEquals(Set.of("filePath"), result.referencedProperties());
    }

    @Test
    void comparisonOnGetPropertyNumeric() {
        var result = analyze("getPropertyValue(file, \"price\", 0) >= 10");
        assertTrue(result.recognized());
        assertEquals(Set.of("price"), result.referencedProperties());
    }

    @Test
    void whitespaceAndNewlinesTolerated() {
        var result = analyze("hasPropertyValue(file,\n  \"status\",\n\t\"open\")");
        assertTrue(result.recognized());
        assertEquals(Set.of("status"), result.referencedProperties());
    }

    // --- Unrecognized --------------------------------------------------------------

    @Test
    void unknownFunctionFallsBack() {
        assertFalse(analyze("someUnknownFn(file, \"a\")").recognized());
    }

    @Test
    void fileIndexingFallsBack() {
        assertFalse(analyze("file.tags ~= nil").recognized());
        assertFalse(analyze("file[\"tags\"] ~= nil").recognized());
    }

    @Test
    void wholeFileUseFallsBack() {
        assertFalse(analyze("pairs(file) ~= nil").recognized());
    }

    @Test
    void methodCallSyntaxFallsBack() {
        assertFalse(analyze("file:has()").recognized());
    }

    @Test
    void dynamicArgumentFallsBack() {
        assertFalse(analyze("hasPropertyValue(file, propName, \"x\")").recognized());
    }

    @Test
    void concatenationInFilterFallsBack() {
        assertFalse(analyze("getPropertyValue(file, \"a\", \"\") .. \"x\" == \"ax\"").recognized());
    }

    @Test
    void arithmeticFallsBack() {
        assertFalse(analyze("hasPropertyValueGreaterThan(file, \"a\", 1 + 2)").recognized());
    }

    @Test
    void wrongArityFallsBack() {
        assertFalse(analyze("hasPropertyValue(file, \"a\")").recognized());
        assertFalse(analyze("hasProperty(file, \"a\", \"extra\")").recognized());
    }

    @Test
    void nonLiteralFirstArgFallsBack() {
        assertFalse(analyze("hasPropertyValue(\"file\", \"a\", \"x\")").recognized());
    }

    @Test
    void emptyAndGarbageFallBack() {
        assertFalse(analyze("").recognized());
        assertFalse(analyze("   ").recognized());
        assertFalse(analyze("this is ) not ( valid").recognized());
        assertFalse(analyze("local x = 1; hasProperty(file, \"x\")").recognized());
    }

    @Test
    void assignmentIsNotAFilter() {
        assertFalse(analyze("x = hasProperty(file, \"a\")").recognized());
    }

    // --- Differential: partial coercion must equal full coercion -------------------

    private static final Map<String, Object> PROPS = propertyFixture();

    private static Map<String, Object> propertyFixture() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("filePath", "Books/SciFi/gamma.md");
        props.put("status", "open");
        props.put("tags", List.of("x", "sci-fi"));
        props.put("price", 4.5);
        props.put("flag", true);
        props.put("note", "");
        return props;
    }

    private static final List<String> RECOGNIZED = List.of(
            "hasProperty(file, \"tags\")",
            "hasPropertyValue(file, \"status\", \"open\")",
            "hasPropertyValue(file, \"tags\", \"x\")",
            "hasPropertyContaining(file, \"filePath\", \"SciFi\")",
            "hasPropertyValueIn(file, \"tags\", {\"x\", \"y\"})",
            "hasPropertyContainingAll(file, \"tags\", {\"x\", \"sci-fi\"})",
            "hasPropertyValueGreaterThan(file, \"price\", 4)",
            "hasPropertyValueLessThanOrEqual(file, \"price\", 4.5)",
            "fileFieldStartsWith(file, \"name\", \"gamma\")",
            "fileInFolder(file, \"Books/SciFi\")",
            "hasEmptyProperty(file, \"note\")",
            "hasPropertyValueStartsWith(file, \"status\", \"op\")",
            "not hasPropertyValue(file, \"status\", \"closed\")",
            "(hasPropertyValue(file, \"status\", \"open\") and hasProperty(file, \"tags\"))",
            "not hasPropertyValue(file, \"a\", \"x\") or hasProperty(file, \"tags\")",
            "true",
            "getPropertyValue(file, \"filePath\", \"\") == \"Books/SciFi/gamma.md\"",
            "getPropertyValue(file, \"price\", 0) >= 4.5",
            "(hasPropertyValue(file, \"tags\", \"x\") and hasProperty(file, \"filePath\")) or false");

    private static final List<String> UNRECOGNIZED = List.of(
            "file[\"tags\"] ~= nil",
            "file.tags ~= nil",
            "#file.tags > 0",
            "type(file) == \"table\"");

    @Test
    void partialCoercionMatchesFullCoercion() {
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        for (String expression : RECOGNIZED) {
            var analysis = analyze(expression);
            assertTrue(analysis.recognized(), "battery case must stay recognized: " + expression);
            boolean full = interpreter.evaluateExpression(expression, PROPS);
            boolean partial = interpreter.evaluateExpression(expression, PROPS, analysis.referencedProperties());
            assertEquals(full, partial, "partial != full for: " + expression);
        }
        for (String expression : UNRECOGNIZED) {
            assertFalse(analyze(expression).recognized(), "battery case must stay unrecognized: " + expression);
            // fallback path: both calls go through full coercion
            boolean full = interpreter.evaluateExpression(expression, PROPS);
            boolean partial = interpreter.evaluateExpression(expression, PROPS, null);
            assertEquals(full, partial, "fallback changed results for: " + expression);
        }
    }

    @Test
    void trimmedTableMissingKeysBehaveAsNil() {
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        // Property present in the map but not referenced: trimming must not
        // change hasProperty (the referenced set only ever trims, never adds)
        String expression = "hasProperty(file, \"unrelated\")";
        var analysis = analyze(expression);
        assertFalse(interpreter.evaluateExpression(expression, PROPS));
        assertFalse(interpreter.evaluateExpression(expression, PROPS, analysis.referencedProperties()));
    }
}
