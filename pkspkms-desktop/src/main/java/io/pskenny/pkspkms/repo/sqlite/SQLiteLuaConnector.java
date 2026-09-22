package io.pskenny.pkspkms.repo.sqlite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.luabase.LuaBaseInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.Function;
import java.io.IOException;
import java.sql.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SQLiteLuaConnector {

    private static final Logger logger = LoggerFactory.getLogger(SQLiteLuaConnector.class);

    private static final LuaBaseInterpreter LUA = new LuaBaseInterpreter();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // LRU cache capped at 4,096 entries so large vaults stay in cache instead
    // of thrashing JSON parses past the old 1,000-entry cap. Keys are 64-bit
    // FNV-1a hashes of the raw properties JSON blob (8 bytes) instead of the
    // full string (often many KB), so the key array no longer retains multi-MB
    // of JSON text. Collision risk at 4,096 entries with a 64-bit hash is
    // ~1e-10; a collision returns a stale parsed map for one lua_eval call,
    // not a crash. Acceptable tradeoff for the memory win.
    private static final Map<Long, Map<String, Object>> JSON_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<Long, Map<String, Object>>(4096, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Map<String, Object>> eldest) {
                    return size() > 4096;
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
            protected void xFunc() {
                // Nothing may escape this callback into the JNI layer —
                // a throwing xFunc destabilizes the whole SQLite connection.
                try {
                    if (args() < 2) {
                        result(0);
                        return;
                    }

                    String expression = value_text(0);
                    String propertiesJson = value_text(1);
                    if (expression == null || propertiesJson == null) {
                        result(0);
                        return;
                    }

                    long key = fnv64(propertiesJson);
                    Map<String, Object> properties = JSON_CACHE.get(key);
                    if (properties == null) {
                        try {
                            properties = MAPPER.readValue(propertiesJson, new TypeReference<>() {});
                        } catch (IOException e) {
                            logger.warn("lua_eval: malformed properties JSON, treating as non-match");
                            result(0);
                            return;
                        }
                        JSON_CACHE.put(key, properties);
                    }

                    boolean evaluationResult = LUA.evaluateExpression(expression, properties);
                    result(evaluationResult ? 1 : 0);
                } catch (Throwable t) {
                    logger.warn("lua_eval failed, treating as non-match: {}", t.toString());
                    try {
                        result(0);
                    } catch (SQLException secondary) {
                        logger.warn("lua_eval could not report its fallback result", secondary);
                    }
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
