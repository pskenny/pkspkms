package io.pskenny.pkspkms.services;

import io.pskenny.pkspkms.repo.sqlite.SQLitePragmas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class WikilinkService {
    private static final Logger logger = LoggerFactory.getLogger(WikilinkService.class);
    private final String dbUrl;
    private volatile Map<String, Set<String>> cache = new HashMap<>();

    public WikilinkService(String dbUrl) {
        this.dbUrl = dbUrl;
        refreshCache();
    }

    private synchronized void refreshCache() {
        Map<String, Set<String>> newMap = new HashMap<>();
        String sql = "SELECT file_path FROM FILES";

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            // PRAGMAs before the query so they actually apply to it (B25)
            SQLitePragmas.apply(conn);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                while (rs.next()) {
                    String path = rs.getString("file_path");
                    String name = new java.io.File(path).getName();

                    // Cache maps filename -> paths
                    newMap.computeIfAbsent(name, k -> new HashSet<>()).add(path);
                }
            }
            this.cache = newMap;
        } catch (SQLException e) {
            logger.error("Error refreshing wikilink cache", e);
        }
    }

    public String resolve(String text) {
        text = text.startsWith("/") ? text.substring(1) : text;
        Set<String> matches = cache.get(text);

        if (matches == null && !text.endsWith(".md")) {
            matches = cache.get(text + ".md");
        }

        return (matches != null && !matches.isEmpty()) ? matches.iterator().next() : null;
    }
}
