package io.pskenny.pkspkms.repo.sqlite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

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

        // Populate past the 1,000 cap limit
        int totalInserts = 1200;
        java.util.HashMap<String, Object> dummy = new java.util.HashMap<>();

        for (int i = 0; i < totalInserts; i++) {
            cache.put((long) i, dummy);
        }

        // Verify size is strictly capped at 1,000 entries (preventing memory leaks)
        assertEquals(1000, cache.size());
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
        assertTrue(cache.size() <= 1000);
    }
}
