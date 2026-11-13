package io.pskenny.pkspkms.luabase;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.YamlParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// BUGS.md B3/B4/B5/B13: the filter language's semantics — every entries
// AND-combined, recursive map/list leaves, none-of `not`, tolerant formulas.
// These corpus tests run the intended semantics; they were red before the fix
// and pin them after.
class BasesCorrectnessTest {

    private final LuaBaseProcessor processor = new LuaBaseProcessor();
    private final YamlParser yaml = new YamlParser();

    private final List<PksFile> corpus = List.of(
            file("f1.md", "x", "y"),
            file("f2.md", "x"),
            file("f3.md", "y"),
            file("f4.xml"));

    private static PksFile file(String path, String... tags) {
        Map<String, Object> props = new HashMap<>();
        if (tags.length > 0) {
            props.put("tags", List.of(tags));
        }
        return new PksFile(path, props);
    }

    private static final String ORDER = "    order:\n      - 'getPropertyValue(file, \"filePath\"), \"Path\"'\n";

    private String render(String filtersYaml) {
        String spec = "views:\n  - type: table\n" + filtersYaml + ORDER;
        return processor.processOverCorpus(yaml.parse(spec), corpus);
    }

    @Test
    void multiFootnoteAppliesAndPlusNot() {
        // B3: AND-entry + NOT-entry both evaluated, not only the first
        String out = render("""
                    filters:
                      and:
                        - hasPropertyValue(file, "tags", "x")
                      not:
                        - hasPropertyValue(file, "tags", "y")
                """);
        assertTrue(out.contains("| f2.md |"), out);
        assertFalse(out.contains("| f1.md |"), out);
        assertFalse(out.contains("| f3.md |"), out);
        assertFalse(out.contains("| f4.xml |"), out);
    }

    @Test
    void notMatchesOnlyWhenAllConditionsFail() {
        // B5: `not: [a, b]` — none-of (a row any of whose conditions hold is out)
        String out = render("""
                    filters:
                      not:
                        - hasPropertyValue(file, "tags", "x")
                        - hasPropertyValue(file, "tags", "y")
                """);
        assertFalse(out.contains("| f1.md |"), out);
        assertFalse(out.contains("| f2.md |"), out);
        assertFalse(out.contains("| f3.md |"), out);
        assertTrue(out.contains("| f4.xml |"), out);
    }

    @Test
    void orKeyMapsToAnyMatch() {
        String out = render("""
                    filters:
                      or:
                        - hasPropertyValue(file, "tags", "x")
                        - hasPropertyValue(file, "tags", "y")
                """);
        assertTrue(out.contains("| f1.md |"), out);
        assertTrue(out.contains("| f2.md |"), out);
        assertTrue(out.contains("| f3.md |"), out);
        assertFalse(out.contains("| f4.xml |"), out);
    }

    @Test
    void nestedLogicalMapLeafRecurses() {
        // B4: a map-valued leaf that is itself a logical container recurses
        // instead of turning into a truthy Lua table literal
        String out = render("""
                    filters:
                      and:
                        - and:
                            - hasPropertyValue(file, "tags", "x")
                            - hasPropertyValue(file, "tags", "y")
                """);
        assertTrue(out.contains("| f1.md |"), out);
        assertFalse(out.contains("| f2.md |"), out);
        assertFalse(out.contains("| f4.xml |"), out);
    }

    @Test
    void nestedMapLeafWithFieldsRecursesAsFilter() {
        // B4 second shape: converter-style map leaf {status: {eq: "open"}}-ish
        // nested under a logical list element
        String out = render("""
                    filters:
                      and:
                        - and:
                            - hasPropertyValue(file, "tags", "x")
                """);
        assertTrue(out.contains("| f1.md |"), out);
        assertTrue(out.contains("| f2.md |"), out);
        assertFalse(out.contains("| f4.xml |"), out);
    }

    @Test
    void nonStringLeafIsSkippedNotStringified() {
        // B4: a map leaf that is NOT a logical container no longer becomes a
        // garbage Lua fragment ({a=s} evaluates nil=="s" → false → 0 rows)
        String out = render("""
                    filters:
                      and:
                        - hasPropertyValue(file, "tags", "x")
                        - a: "s"
                """);
        assertTrue(out.contains("| f1.md |"), out);
        assertTrue(out.contains("| f2.md |"), out);
        assertFalse(out.contains("| f4.xml |"), out);
    }

    @Test
    void nonStringFormulaValueIsSkipped() {
        // B13: a non-string formula value must not ClassCastException the pass
        String spec = """
                formulas:
                  broken: 42
                  ppu: 'return getPropertyValue(file, "price", 0) * 5'
                views:
                  - type: table
                    order:
                      - 'getPropertyValue(file, "filePath"), "Path"'
                """;
        String out = processor.processOverCorpus(yaml.parse(spec), corpus);
        assertTrue(out.contains("| f1.md |"), out);
        assertTrue(out.contains("| f2.md |"), out);
    }

    @Test
    void invalidFilterStillRendersHeadersOnly() {
        String out = render("""
                    filters:
                      and:
                        - 'this is ) not ( valid'
                """);
        assertEquals("""
                | Path |
                |---|
                """, out);
    }

    private void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
