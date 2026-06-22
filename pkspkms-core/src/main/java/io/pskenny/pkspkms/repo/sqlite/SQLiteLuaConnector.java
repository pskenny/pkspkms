package io.pskenny.pkspkms.repo.sqlite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.luabase.LuaBaseInterpreter;
import org.sqlite.Function;
import java.sql.*;
import java.util.Map;

public class SQLiteLuaConnector {

    private static final LuaBaseInterpreter LUA = new LuaBaseInterpreter();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void registerLuaFunction(Connection conn) throws SQLException {
        Function.create(conn, "lua_eval", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                if (args() < 2) {
                    throw new SQLException("lua_eval(expression, properties_json) requires 2 arguments");
                }

                String expression = value_text(0);
                String propertiesJson = value_text(1);

                try {
                    Map<String, Object> properties = MAPPER.readValue(
                            propertiesJson,
                            new TypeReference<>() {}
                    );
                    boolean result = LUA.evaluateExpression(expression, properties);
                    result(result ? 1 : 0);
                } catch (java.io.IOException e) {
                    throw new SQLException("Lua execution error: " + e.getMessage(), e);
                }
            }
        });
    }
}
