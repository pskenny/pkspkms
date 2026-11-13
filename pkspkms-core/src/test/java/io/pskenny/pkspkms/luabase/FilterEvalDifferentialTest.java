package io.pskenny.pkspkms.luabase;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tier 8 heavyweight: the Java evaluator (FilterEval) must return EXACTLY the
// Lua interpreter's answer (full coercion, FilterAnalyzer full results) for
// every recognized expression over every fixture — the whitelist is the
// correctness gate, this harness is its differential proof.
class FilterEvalDifferentialTest {

    private final LuaBaseInterpreter lua = new LuaBaseInterpreter();

    private static final String A_PATH = "Books/SciFi/gamma.md";

    private Map<String, Object> propsA() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("filePath", A_PATH);
        props.put("status", "open");
        props.put("tags", List.of("x", "sci-fi"));
        props.put("price", 4.5);
        props.put("flag", true);
        props.put("note", "");
        return props;
    }

    // Numbers stored as strings, mixed-type traps
    private Map<String, Object> propsB() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("filePath", "Books/beta.md");
        props.put("price", "20");
        props.put("tags", List.of(1, 2));
        props.put("status", "open");
        return props;
    }

    // Almost empty: only filePath
    private Map<String, Object> propsC() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("filePath", "a.md");
        return props;
    }

    // D fixture: boolean-false property, integral number, numeric-string
    // styles, map-valued property, non-ASCII ordering (BUGS.md M1 fixtures)
    private Map<String, Object> propsD() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("filePath", "Books/delta.md");
        props.put("status", "open");
        props.put("flag", false);
        props.put("bytes", 1000);
        props.put("hex", "0x10");
        props.put("id", "20d");
        props.put("meta", Map.of("foo", "bar"));
        props.put("title", "éclair");
        props.put("tags", List.of("x"));
        return props;
    }

    private List<Map<String, Object>> fixtures() {
        return List.of(propsA(), propsB(), propsC(), propsD());
    }

    private static final List<String> BATTERY = List.of(
            "hasProperty(file, \"tags\")",
            "hasProperty(file, \"missing\")",
            "hasPropertyValue(file, \"status\", \"open\")",
            "hasPropertyValue(file, \"status\", \"closed\")",
            "hasPropertyValue(file, \"price\", 4.5)",
            "hasPropertyValue(file, \"price\", \"4.5\")",
            "hasPropertyValue(file, \"price\", \"20\")",
            "hasPropertyValue(file, \"tags\", \"x\")",
            "hasPropertyValue(file, \"tags\", 1)",
            "hasPropertyValueIn(file, \"tags\", {\"x\", \"sci-fi\"})",
            "hasPropertyValueIn(file, \"tags\", {\"none\"})",
            "hasPropertyContaining(file, \"filePath\", \"SciFi\")",
            "hasPropertyContaining(file, \"note\", \"\")",
            "hasPropertyContaining(file, \"price\", 4.5)",
            "hasPropertyContaining(file, \"tags\", \"x\")",
            "hasPropertyContainingAll(file, \"tags\", {\"x\", \"sci-fi\"})",
            "hasPropertyContainingAll(file, \"tags\", {})",
            "hasPropertyContainingAll(file, \"tags\", {\"none\"})",
            "hasPropertyContainingAll(file, \"tags\", {\"x\"})",
            "hasPropertyContainingAll(file, \"filePath\", {})".replace("file, \"filePath\"", "file, \"status\""),
            "hasPropertyValueStartsWith(file, \"status\", \"op\")",
            "hasPropertyValueStartsWith(file, \"filePath\", \"Books\")",
            "hasEmptyProperty(file, \"note\")",
            "hasEmptyProperty(file, \"missing\")",
            "hasEmptyProperty(file, \"tags\")",
            "hasEmptyProperty(file, \"status\")".replace("\"status\"", "\"price\""),
            "hasPropertyValueGreaterThan(file, \"price\", 4)",
            "hasPropertyValueGreaterThan(file, \"price\", \"20\")",
            "hasPropertyValueGreaterThanOrEqual(file, \"price\", 4.5)",
            "hasPropertyValueLessThan(file, \"price\", 5)",
            "hasPropertyValueLessThan(file, \"price\", \"4.4\")",
            "hasPropertyValueLessThanOrEqual(file, \"price\", 4.5)",
            "fileFieldStartsWith(file, \"name\", \"gamma\")",
            "fileFieldStartsWith(file, \"basename\", \"gamma\")",
            "fileFieldStartsWith(file, \"path\", \"Books/SciFi\")",
            "fileFieldStartsWith(file, \"ext\", \"md\")",
            "fileInFolder(file, \"Books/SciFi\")",
            "fileInFolder(file, \"\")",
            "not hasPropertyValue(file, \"status\", \"closed\")",
            "not not hasProperty(file, \"tags\")",
            "hasProperty(file, \"tags\") and hasProperty(file, \"filePath\")",
            "hasProperty(file, \"tags\") or hasProperty(file, \"status\")",
            "true and hasProperty(file, \"tags\")",
            "false or hasProperty(file, \"tags\")",
            "nil",
            "(hasProperty(file, \"tags\") and not hasProperty(file, \"status\")) or false",
            "getPropertyValue(file, \"filePath\", \"\") == \"Books/SciFi/gamma.md\"",
            "getPropertyValue(file, \"missing\", \"\") == \"\"",
            "getPropertyValue(file, \"missing\", \"d\") ~= \"other\"",
            "getPropertyValue(file, \"price\", 0) >= 4.5",
            "getPropertyValue(file, \"price\", 0) > \"abc\"",
            "getPropertyValue(file, \"status\", nil) ~= nil",
            "getPropertyValue(file, \"price\", 0) < 4.4",
            "hasProperty(file, \"tags\") and getPropertyValue(file, \"price\", 0) > 4 and hasProperty(file, \"filePath\")",
            // --- M1 batch-C parity pins (C1/C2/C3/C4/C5/C6/C14) ---
            // C1: boolean false is still a value — hasProperty is `~= nil`
            "hasProperty(file, \"flag\")",
            "hasProperty(file, \"flag\") and hasProperty(file, \"filePath\")",
            // C2: integral doubles render without ".0" in tostring
            "hasPropertyContaining(file, \"bytes\", \".0\")",
            "hasPropertyContaining(file, \"bytes\", \"1000.0\")",
            // C3/C14: table-valued props are "table: 0x…" in Lua — substring/prefix fail
            "hasPropertyContaining(file, \"meta\", \"foo\")",
            "hasPropertyContaining(file, \"meta\", \"{\")",
            "hasPropertyValueStartsWith(file, \"tags\", \"[\")",
            // C5: tonumber mirrors LuaJ scannumber — hex yes, d-suffix never
            "getPropertyValue(file, \"hex\", 0) > 0",
            "hasPropertyValueGreaterThan(file, \"hex\", 15)",
            "getPropertyValue(file, \"id\", 0) > 0",
            // C6: string ordering is signed UTF-8 bytes, not UTF-16 code units
            "getPropertyValue(file, \"title\", \"\") >= \"e\"",
            // C4: `not x > y` parses as `(not x) > y` (boolean vs number errors)
            "not getPropertyValue(file, \"price\", 0) > 5",
            "not getPropertyValue(file, \"status\", nil) == nil");

    @Test
    void differential_overWholeBatteryAndFixtures() {
        int checked = 0;
        for (String expression : BATTERY) {
            var analysis = FilterAnalyzer.analyze(expression);
            assertTrue(analysis.recognized(), "battery case must stay recognized: " + expression);
            boolean luaj = luaFullCoercionOn(expression, propsA());
            boolean javin = FilterEval.eval(analysis.ast(), propsA());
            assertEquals(luaj, javin, "propsA mismatch for: " + expression);

            checked++;
        }
        assertTrue(checked >= BATTERY.size());
    }

    @Test
    void differential_perFixture() {
        for (Map<String, Object> props : fixtures()) {
            for (String expression : BATTERY) {
                var analysis = FilterAnalyzer.analyze(expression);
                assertTrue(analysis.recognized(), "recognized: " + expression);
                boolean luaj = luaFullCoercionOn(expression, props);
                boolean javin = FilterEval.eval(analysis.ast(), props);
                assertEquals(luaj, javin, "fixture mismatch for: " + expression + " props=" + props);
            }
        }
    }

    private boolean luaFullCoercionOn(String expression, Map<String, Object> props) {
        try {
            return lua.evaluateExpression(expression, props);
        } catch (RuntimeException e) {
            // processOverCorpus parity: a crashing expression (e.g. comparing
            // number with string) is a per-row non-match
            return false;
        }
    }
}
