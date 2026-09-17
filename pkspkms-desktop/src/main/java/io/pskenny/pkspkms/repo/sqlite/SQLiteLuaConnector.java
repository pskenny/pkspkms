package io.pskenny.pkspkms.repo.sqlite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.luabase.LuaBaseInterpreter;
import org.sqlite.Function;
import java.sql.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SQLiteLuaConnector {

    private static final LuaBaseInterpreter LUA = new LuaBaseInterpreter();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // LRU cache capped at 1,000 entries. Keys are 64-bit FNV-1a hashes of the raw
    // properties JSON blob (8 bytes) instead of the full string (often many KB),
    // so the key array no longer retains multi-MB of JSON text. Collision risk at
    // 1,000 entries with a 64-bit hash is ~1e-13; a collision returns a stale parsed
    // map for one lua_eval call, not a crash. Acceptable tradeoff for the memory win.
    private static final Map<Long, Map<String, Object>> JSON_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<Long, Map<String, Object>>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Map<String, Object>> eldest) {
                    return size() > 1000;
                }
            });

    // Expose cache for direct unit-testing of correctness and eviction
    public static Map<Long, Map<String, Object>> getCache() {
        return JSON_CACHE;
    }

    // FNV-1a 64-bit. Allocation-free, fast on multi-KB strings.
    private static long fnv64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

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
                    long key = fnv64(propertiesJson);
                    Map<String, Object> properties = JSON_CACHE.get(key);
                    if (properties == null) {
                        properties = MAPPER.readValue(
                                propertiesJson,
                                new TypeReference<>() {}
                        );
                        JSON_CACHE.put(key, properties);
                    }
                    boolean result = LUA.evaluateExpression(expression, properties);
                    result(result ? 1 : 0);
                } catch (java.io.IOException e) {
                    throw new SQLException("Lua execution error: " + e.getMessage(), e);
                }
            }
        });
    }

    public static java.util.function.Consumer<Connection> luaFunctionRegistrar() {
        return conn -> {
            try {
                registerLuaFunction(conn);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
