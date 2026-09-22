package io.pskenny.pkspkms.luabase;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LuaBaseInterpreterTest {

    @Test
    public void testEvalLua_getPropertyValue() {
        Map<String, Object> file =  new HashMap<>() {{
            put("number", 10);
        }};
        LuaBaseInterpreter luaBaseProcessor = new LuaBaseInterpreter();

        var getPropertyPresent = "getPropertyValue(file, \"number\", \"\")";
        var val = luaBaseProcessor.evaluateLuaExpression(getPropertyPresent, file).toString();
        assertEquals("10", val, "getProperty returns present value");

        var getPropertyNotPresent = "getPropertyValue(file, \"not_present\")";
        val = luaBaseProcessor.evaluateLuaExpression(getPropertyNotPresent, file).toString();
        assertEquals("nil", val, "getProperty returns null for not present value");

        var getPropertyNotPresentWithDefault = "getPropertyValue(file, \"not_present\", \"default\")";
        val = luaBaseProcessor.evaluateLuaExpression(getPropertyNotPresentWithDefault, file).toString();
        assertEquals("default", val, "getProperty returns default value for not present value");
    }

    @Test
    public void testEvalLua_function() {
        Map<String, Object> file =  new HashMap<>() {{
            put("number", 10);
        }};
        LuaBaseInterpreter luaBaseProcessor = new LuaBaseInterpreter();

        var getPropertyPresent = "getPropertyValue(file, \"number\", \"\")";
        var val = luaBaseProcessor.evaluateLuaExpression(getPropertyPresent, file).toString();
        assertEquals("10", val, "getProperty returns present value");
    }

    @Test
    public void osExecuteIsDenied() {
        assertNotExecutable("os.execute(\"touch /tmp/pwned-b2\")");
    }

    @Test
    public void ioOpenIsDenied() {
        assertNotExecutable("io.open(\"/etc/passwd\", \"r\")");
    }

    @Test
    public void luajavaReflectionIsDenied() {
        assertNotExecutable("luajava.newInstance(\"java.lang.Runtime\")");
    }

    @Test
    public void dynamicLoadIsDenied() {
        assertNotExecutable("load(\"return 1\")");
        assertNotExecutable("loadstring(\"return 1\")");
        assertNotExecutable("dofile(\"/etc/passwd\")");
        assertNotExecutable("require(\"os\")");
    }

    @Test
    public void osTableIsAbsent() {
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        assertNotExecutable("return os ~= nil");
    }

    @Test
    public void oversizedExpressionIsRejected() {
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        Map<String, Object> file = new HashMap<>();
        String huge = "a".repeat(4096 + 1);
        assertThrows(org.luaj.vm2.LuaError.class, () -> interpreter.evaluateLuaExpression(huge, file),
                "oversized filters are rejected before compilation");
    }

    private void assertNotExecutable(String expression) {
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> interpreter.evaluateLuaExpression(expression, new HashMap<>()),
                "note-controlled '" + expression.split("\\(")[0] + "' must fail in-sandbox");
    }
}
