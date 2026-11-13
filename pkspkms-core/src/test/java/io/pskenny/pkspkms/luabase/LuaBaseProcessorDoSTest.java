package io.pskenny.pkspkms.luabase;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DoS containment pins for note-controlled Lua (BUGS.md M2).
 *
 * The engine must survive pathological note content: runtime-recursion
 * StackOverflowError (an Error, previously escaping the row-level catch),
 * unbounded computed cells, and — at the embed level — infinite loops
 * (watchdog, see EmbedWatchdogTest).
 */
public class LuaBaseProcessorDoSTest {

    private static final LuaBaseProcessor PROCESSOR = new LuaBaseProcessor();

    private static io.pskenny.pkspkms.io.PksFile file(String path, Map<String, Object> props) {
        return new io.pskenny.pkspkms.io.PksFile(path, props);
    }

    // Runtime recursion raises a raw StackOverflowError in luaj — an Error.
    // The recursion is deliberately NON-tail (`return 1 + f()` grows the
    // frame); `return f()` alone is tail-called and would loop forever
    // without growing (that path is only bounded by the embed watchdog).
    // The per-row catch (now Throwable) must degrade the ROW, not the batch;
    // the embed renders as headers-only.
    @Test
    @Timeout(20)
    void runtimeRecursionDegradesRowsNotBatch() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", "table");
        view.put("order", List.of("getPropertyValue(file, \"filePath\", \"\"), \"Path\""));
        // filters live on the VIEW (ViewSpec.from), not on the spec root
        view.put("filters", Map.of("and", List.of(
                "(function() local function f() return 1 + f() end return f() end)()")));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("views", List.of(view));

        String out = PROCESSOR.processOverCorpus(spec, List.of(
                file("a.md", Map.of("filePath", "a.md")),
                file("b.md", Map.of("filePath", "b.md"))));

        // headers rendered; no rows matched (per-filter-loop SOEs degrade)
        assertTrue(out.contains("| Path |"), "headers survive: " + out);
        assertFalse(out.contains("ERROR"), "recursion must not error the embed");
        assertFalse(out.contains("| a.md |") || out.contains("| b.md |"),
                "no rows may match via a crashing filter: " + out);
    }

    // Unbounded computed cell values clamp to MAX_CELL_CHARS with a visible
    // marker instead of inflating the rendered table by megabytes.
    @Test
    void giantCellValuesClamp() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", "table");
        view.put("order", List.of("string.rep(\"A\", 5000000), \"X\""));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("views", List.of(view));

        String out = PROCESSOR.processOverCorpus(spec, List.of(
                file("a.md", Map.of("filePath", "a.md"))));

        assertTrue(out.length() < 100_000, "cell clamped to a sane size, was " + out.length());
        assertTrue(out.contains("cell truncated"), "truncation is visible: " + out);
    }
}
