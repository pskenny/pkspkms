/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of PKSPKMS.
 * Copyright (C) 2026 Paul Kenny
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.pskenny.pkspkms.repo.sqlite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("legacy")
public class SQLiteLuaConnectorTest {

    @BeforeEach
    void setUp() {
        SQLiteLuaConnector.getCache().clear();
    }

    @Test
    void testCacheIdentityAndCorrectness() throws Exception {
        Map<Long, Map<String, Object>> cache = SQLiteLuaConnector.getCache();
        long key = 42L;

        // Ensure initially empty
        assertNull(cache.get(key));

        // Use standard Jackson parse or simulate Connector population
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> parsed = mapper.readValue("{\"tags\":[\"test\"],\"type\":\"book\"}", new com.fasterxml.jackson.core.type.TypeReference<>() {});

        cache.put(key, parsed);

        // Fetch again and verify exact object instance reference is reused
        Map<String, Object> cached = cache.get(key);
        assertNotNull(cached);
        assertSame(parsed, cached); // Verifies object identity matches exactly (No re-parsing)
    }

    @Test
    void testCacheEviction() {
        Map<Long, Map<String, Object>> cache = SQLiteLuaConnector.getCache();

        // Populate past the 4,096 cap limit
        int totalInserts = 4200;
        java.util.HashMap<String, Object> dummy = new java.util.HashMap<>();

        for (int i = 0; i < totalInserts; i++) {
            cache.put((long) i, dummy);
        }

        // Verify size is strictly capped at 4,096 entries (preventing memory leaks)
        assertEquals(4096, cache.size());
    }

    @Test
    void testCacheThreadSafety() throws InterruptedException {
        Map<Long, Map<String, Object>> cache = SQLiteLuaConnector.getCache();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        java.util.HashMap<String, Object> dummy = new java.util.HashMap<>();

        // Perform concurrent high-load reads and writes to verify thread safety
        for (int i = 0; i < 50000; i++) {
            final long key = i;
            executor.submit(() -> {
                cache.put(key, dummy);
                cache.get(key);
            });
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertTrue(finished, "Executor did not finish in time");

        // Cap limit must still be preserved and map must be stable without throwing ConcurrentModificationException
        assertTrue(cache.size() <= 4096);
    }

    // --- B5: the callback must never let unexpected throwables escape into the JNI layer ---

    private int eval(String expression, String propertiesJson) throws Exception {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) {
            SQLiteLuaConnector.registerLuaFunction(conn);
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement("SELECT lua_eval(?, ?)")) {
                pstmt.setString(1, expression);
                pstmt.setString(2, propertiesJson);
                try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }

    @Test
    void luaRuntimeErrorYieldsZeroInsteadOfEscaping() throws Exception {
        assertEquals(0, eval("error(\"boom\")", "{}"), "Lua runtime error → 0, never a JNI escape");
    }

    @Test
    void nullPropertiesYieldsZero() throws Exception {
        assertEquals(0, eval("true", null), "NULL property row → 0, never an NPE escape");
    }

    @Test
    void malformedPropertiesYieldZero() throws Exception {
        assertEquals(0, eval("true", "{not json"), "malformed JSON → 0");
    }
}
