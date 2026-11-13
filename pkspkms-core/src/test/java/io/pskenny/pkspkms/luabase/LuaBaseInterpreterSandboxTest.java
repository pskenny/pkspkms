package io.pskenny.pkspkms.luabase;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sandbox pins for the note-controlled Lua environment (BUGS.md S1-S3).
 *
 * The environment must be an ALLOWLIST: only the standard-comparison builtins,
 * the string/table/math libraries, and the trusted helpers from
 * /luabase/functions.lua exist. Everything else — most importantly the
 * `package` table (its searchers can re-arm `os` and `luajava` from
 * classpath classes: BUGS.md S1), filesystem primitives (`loadfile` — S2)
 * and a live `_G` (cross-note global poisoning — S3) — must be denied.
 */
public class LuaBaseInterpreterSandboxTest {

    // S1: the deny list used to keep `package` alive; PackageLib's
    // java_searcher does Class.forName + newInstance + initupvalue1, so
    // `package.searchers[3]("os")("os", _G)` re-armed os (execute) from a
    // note expression. package must not exist at all.
    @Test
    public void packageTableIsAbsent() {
        assertUnreachable("package ~= nil");
    }

    @Test
    public void javaSearcherCannotReArmOs() {
        // The re-arm payload itself must fail in-sandbox (S1's exploit path;
        // payload uses the fully-qualified classname, which survives
        // PackageLib.toClassname and hits Class.forName directly)
        assertUnreachable("(function() return (package.searchers[3](\"org.luaj.vm2.lib.OsLib\"))(\"os\", _G) end)()");
        // ...and even in a separately-compiled eval, `os` must stay absent
        assertUnreachable("os ~= nil");
    }

    @Test
    public void javaSearcherCannotReArmLuajava() {
        assertUnreachable("(function() return (package.searchers[3](\"org.luaj.vm2.lib.jse.LuajavaLib\"))(\"luajava\", _G) end)()");
        assertUnreachable("luajava ~= nil");
    }

    // S2: `loadfile` survived the old deny list and opened new File(filename)
    // inside JseBaseLib's resource locator — filesystem read/existence oracle.
    @Test
    public void loadfileIsDenied() {
        assertUnreachable("loadfile ~= nil");
        assertUnreachable("loadfile(\"/etc/passwd\") ~= nil");
    }

    @Test
    public void packageSearchpathIsDenied() {
        assertUnreachable("package.searchpath ~= nil");
        assertUnreachable("package.searchpath(\"hosts\", \"/etc/\")");
    }

    // S3: no live _G — the named table, when present, is a frozen snapshot,
    // so note code cannot poison helper globals used by later notes.
    @Test
    public void gPoisoningDoesNotLeak() {
        Map<String, Object> file = new HashMap<>();
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        interpreter.evaluateLuaExpression("(function() _G.hasProperty = function() return true end end)()", file);
        // after snapshot hardening the write goes to a copy, not the env:
        // hasProperty must still resolve to functions.lua's semantics
        // (property "x" is absent on `file` -> false)
        assertFalse(interpreter.evaluateLuaExpression("hasProperty(file, \"x\")", new HashMap<>()).toboolean(),
                "a note must not be able to override trusted helpers via _G");    }

    // Log-spam DoS primitive: print must not be reachable (M2).
    @Test
    public void printIsDenied() {
        assertUnreachable("print ~= nil");
    }

    // Confirm the whole old deny list stays denied, not just the package cases.
    @Test
    public void legacyDenyListStaysDenied() {
        for (String name : new String[]{"os", "io", "luajava", "load", "loadstring", "dofile", "require"}) {
            assertUnreachable(name + " ~= nil");
        }
    }

    // The allowlist must not strand the trusted helpers the converter emits.
    @Test
    public void allowlistKeepsTrustedVocabulary() {
        Map<String, Object> file = new HashMap<>() {{
            put("price", 120);
            put("tags", java.util.List.of("x", "y"));
        }};
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();

        assertEquals(120.0, interpreter.evaluateLuaExpression("tonumber(getPropertyValue(file, \"price\", 0))", file).todouble(), 1e-9);
        assertTrue(interpreter.evaluateLuaExpression("hasProperty(file, \"price\")", file).toboolean());
        assertTrue(interpreter.evaluateLuaExpression("hasPropertyContaining(file, \"tags\", \"y\")", file).toboolean(),
                "list membership via functions.lua helpers still works");
        assertTrue(interpreter.evaluateLuaExpression("hasPropertyValueGreaterThan(file, \"price\", 100)", file).toboolean(),
                "numeric comparisons still work");
        assertEquals("he", interpreter.evaluateLuaExpression("string.sub(\"hello\", 1, 2)", file).tojstring());
        assertEquals("a,b", interpreter.evaluateLuaExpression("table.concat({\"a\", \"b\"}, \",\")", file).tojstring(),
                "the tags template needs table.concat");
        assertEquals("table", interpreter.evaluateLuaExpression("type({})", file).tojstring());
    }

    // Filter-ordering helper coverage pinned here so the allowlist rebuild
    // cannot silently drop functions.lua editions:
    @Test
    public void functionsLuaHelpersAreAllCallable() {
        Map<String, Object> file = new HashMap<>() {{
            put("filePath", "Notes/Book.md");
            put("status", "open");
            put("done", false);
        }};
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        assertTrue(interpreter.evaluateLuaExpression("fileInFolder(file, \"Notes\")", file).toboolean());
        assertTrue(interpreter.evaluateLuaExpression("fileFieldStartsWith(file, \"basename\", \"Book\")", file).toboolean());
        assertTrue(interpreter.evaluateLuaExpression("hasPropertyValueIn(file, \"status\", {\"open\", \"done\"})", file).toboolean());
        assertTrue(interpreter.evaluateLuaExpression("hasEmptyProperty(file, \"missing\")", file).toboolean());
        assertFalse(interpreter.evaluateLuaExpression("hasPropertyContainingAll(file, \"status\", {\"open\", \"done\"})", file).toboolean());
        assertTrue(interpreter.evaluateLuaExpression("hasPropertyValue(file, \"status\", \"open\")", file).toboolean());
        assertTrue(interpreter.evaluateLuaExpression("hasPropertyValueStartsWith(file, \"status\", \"op\")", file).toboolean());
        assertFalse(interpreter.evaluateLuaExpression("hasProperty(file, \"done\") and false", file).toboolean(),
                "helper must still be callable post-rebuild");
    }

    // Denied = throws (stray nil index / compile error) OR evaluates falsy.
    private void assertUnreachable(String expression) {
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        // evaluateLuaExpression wraps as `return function(file) return <expr> end`;
        // strip a leading `return ` so simple expressions compile here
        String body = expression.startsWith("return ") ? expression.substring("return ".length()) : expression;
        try {
            var result = interpreter.evaluateLuaExpression(body, new HashMap<>());
            if (result.toboolean()) {
                throw new AssertionError("note-controlled expression reached an unsafe value: " + expression + " -> " + result);
            }
        } catch (RuntimeException e) {
            // fine: LuaError is a RuntimeException in luaj; denied names are
            // stray nils / compile errors in-sandbox
        }
    }
}
