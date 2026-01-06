package io.github.pskenny.luabase;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LuaBaseInterpreterTest {

    @Test
    public void testEvalLua_getPropertyValue() {
        Map<String, Object> file =  new HashMap<String, Object>() {{
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
        Map<String, Object> file =  new HashMap<String, Object>() {{
            put("number", 10);
        }};
        LuaBaseInterpreter luaBaseProcessor = new LuaBaseInterpreter();

        var getPropertyPresent = "getPropertyValue(file, \"number\", \"\")";
        var val = luaBaseProcessor.evaluateLuaExpression(getPropertyPresent, file).toString();
        assertEquals("10", val, "getProperty returns present value");
    }
}
