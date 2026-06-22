package io.pskenny.pkspkms.luabase;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class LuaBaseInterpreter {

    private final Globals globals;

    public LuaBaseInterpreter() {
        this.globals = JsePlatform.standardGlobals();
        try (InputStream is = getClass().getResourceAsStream("/luabase/functions.lua")) {
            if (is == null) {
                throw new IllegalStateException("Could not find /luabase/functions.lua");
            }
            String result = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Load standard Lua functions and custom utility functions
            this.globals.load(result).call();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean evaluateExpression(String expression, Map<String, Object> properties) {
        LuaValue luaFile = CoerceJavaToLua.coerce(properties);
        // We wrap the expression in a function that receives 'file'
        String script = "local file = ...; return (" + expression + ")";
        LuaValue chunk = globals.load(script);
        return chunk.call(luaFile).toboolean();
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
