package io.github.pskenny.luabase;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

public class LuaBaseInterpreter {

    private final Globals globals;

    public LuaBaseInterpreter() {
        this.globals = JsePlatform.standardGlobals();
        StringBuilder resultBuilder = new StringBuilder("");
        try (InputStream in = getClass().getResourceAsStream("/luabase/functions.lua");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                resultBuilder.append(line).append("\n");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Load standard Lua functions and custom utility functions
        this.globals.load(resultBuilder.toString()).call();
    }

    public void addFunction(String name, String function) {
        String script = "function " + name + "(file) " + function + " end";
        globals.load(script).call();
    }

    public LuaValue evaluateLuaExpression(String expression, Map<String, Object> file) {
        LuaValue luaFile = CoerceJavaToLua.coerce(file);
        String script = "return function(file) return " + expression + " end";
        LuaValue functionChunk = globals.load(script);
        LuaValue function = functionChunk.call();
        return function.call(luaFile);
    }
}
