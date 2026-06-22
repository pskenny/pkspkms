package io.pskenny.pkspkms.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WikilinkService {
    private static final Logger logger = LoggerFactory.getLogger(WikilinkService.class);
    private final String dbUrl;
    private Map<String, Set<String>> cache = new ConcurrentHashMap<>();
    private long lastCacheUpdate = 0;

    public WikilinkService(String dbUrl) {
        this.dbUrl = dbUrl;
        refreshCache();
    }

    /**
     * Checks if the DB has changed since the last refresh.
     */
    private synchronized void ensureSynced() {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             // Check the latest 'last_sync' time across all files
             ResultSet rs = stmt.executeQuery("SELECT MAX(last_sync) FROM FILES")) {

            if (rs.next()) {
                long latestDbChange = rs.getLong(1);
                if (latestDbChange > lastCacheUpdate) {
                    refreshCache();
                }
            }
        } catch (SQLException e) {
            logger.error("Error syncing wikilink cache", e);
        }
    }

    private synchronized void refreshCache() {
        Map<String, Set<String>> newMap = new HashMap<>();
        String sql = "SELECT file_path, properties FROM FILES";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String path = rs.getString("file_path");
                String name = new java.io.File(path).getName();

                // Add actual filename
                newMap.computeIfAbsent(name, k -> new HashSet<>()).add(path);

                // Add aliases from JSON
                String propsJson = rs.getString("properties");
                parseAliases(propsJson).forEach(alias ->
                        newMap.computeIfAbsent(alias, k -> new HashSet<>()).add(path)
                );
            }
            this.cache = newMap;
            this.lastCacheUpdate = System.currentTimeMillis();
        } catch (SQLException e) {
            logger.error("Error refreshing wikilink cache", e);
        }
    }

    private List<String> parseAliases(String json) {
        // Use your preferred JSON library to extract the $.aliases array
        // (Simplified placeholder logic)
        return new ArrayList<>();
    }

    public String resolve(String text) {
        ensureSynced(); // Automatic sync check before every resolution

        text = text.startsWith("/") ? text.substring(1) : text;
        Set<String> matches = cache.get(text);

        if (matches == null && !text.endsWith(".md")) {
            matches = cache.get(text + ".md");
        }

        return (matches != null && !matches.isEmpty()) ? matches.iterator().next() : null;
    }
}
