package io.pskenny.pkspkms.luabase;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LuaBaseInterpreter {

    private static final String FUNCTIONS_LUA;
    static {
        try (InputStream is = LuaBaseInterpreter.class.getResourceAsStream("/luabase/functions.lua")) {
            if (is == null) {
                throw new IllegalStateException("Could not find /luabase/functions.lua");
            }
            FUNCTIONS_LUA = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final ThreadLocal<ThreadState> threadState = ThreadLocal.withInitial(ThreadState::new);

    private static class ThreadState {
        final Globals globals;
        final Map<String, LuaValue> compiledExpressions = new ConcurrentHashMap<>();
        final Map<String, LuaValue> compiledLuaExpressions = new ConcurrentHashMap<>();

        ThreadState() {
            this.globals = JsePlatform.standardGlobals();
            this.globals.load(FUNCTIONS_LUA).call();
        }
    }

    public boolean evaluateExpression(String expression, Map<String, Object> properties) {
        ThreadState ts = threadState.get();
        LuaValue chunk = ts.compiledExpressions.computeIfAbsent(expression, expr -> {
            String script = "local file = ...; return (" + expr + ")";
            return ts.globals.load(script);
        });

        LuaValue luaFile = CoerceJavaToLua.coerce(properties);
        return chunk.call(luaFile).toboolean();
    }

    public void addFunction(String name, String function) {
        ThreadState ts = threadState.get();
        String script = "function " + name + "(file) " + function + " end";
        ts.globals.load(script).call();
    }

    public LuaValue evaluateLuaExpression(String expression, Map<String, Object> file) {
        ThreadState ts = threadState.get();
        LuaValue function = ts.compiledLuaExpressions.computeIfAbsent(expression, expr -> {
            String script = "return function(file) return " + expr + " end";
            LuaValue functionChunk = ts.globals.load(script);
            return functionChunk.call();
        });

        LuaValue luaFile = CoerceJavaToLua.coerce(file);
        return function.call(luaFile);
    }

    public void validateExpression(String expression) {
        ThreadState ts = threadState.get();
        String script = "local file = ...; return (" + expression + ")";
        ts.globals.load(script);
    }
}
