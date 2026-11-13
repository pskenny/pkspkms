package io.pskenny.pkspkms.luabase.renderer;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.LuaBaseInterpreter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

// BUGS.md B17: `|`/newline cells corrupted the emitted table and a comma in
// the header label broke column parsing.
class TableRendererTest {

    private final TableRenderer renderer = new TableRenderer();
    private final LuaBaseInterpreter lua = new LuaBaseInterpreter();

    @Test
    void headerWithCommaStaysIntact() {
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("note", "x");
        }}));

        String table = renderer.render(
                java.util.List.of("getPropertyValue(file, \"note\"), \"Note, full\""),
                files.values(), lua);

        assertTrue(table.contains("| Note, full |"), table);
        assertTrue(table.contains("| x |"), table);
    }

    @Test
    void pipeInCellValueEscaped() {
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("note", "left|right");
        }}));

        String table = renderer.render(
                java.util.List.of("getPropertyValue(file, \"note\"), \"Note\""),
                files.values(), lua);

        assertTrue(table.contains("| left\\|right |"), table);
    }

    @Test
    void newlineInCellFlattened() {
        Map<String, PksFile> files = new HashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>() {{
            put("note", "line1\nline2");
        }}));

        String table = renderer.render(
                java.util.List.of("getPropertyValue(file, \"note\"), \"Note\""),
                files.values(), lua);

        assertTrue(table.contains("| line1 line2 |"), table);
    }

    @Test
    void nilCellKeepsTheLuaNilText() {
        // Pinned behavior (LuaBaseProcessorTest golden): a Lua nil evaluates to
        // the string "nil" in the cell — mdCell(null) is only the Java null
        // defense and is not reached through the Lua path here.
        Map<String, PksFile> files = new java.util.LinkedHashMap<>();
        files.put("a.md", new PksFile("a.md", new HashMap<>()));

        String table = renderer.render(
                java.util.List.of("getPropertyValue(file, \"missing\"), \"Note\""),
                files.values(), lua);

        assertEquals("""
                | Note |
                |---|
                | nil |
                """, table);
    }
}
