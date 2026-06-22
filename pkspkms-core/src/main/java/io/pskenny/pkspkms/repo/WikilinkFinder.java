package io.pskenny.pkspkms.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to resolve wikilinks using the SQLite repository as a backend.
 * Supports resolution by file name, file name with extension, or full path.
 */
public class WikilinkFinder {
    private static final Logger logger = LoggerFactory.getLogger(WikilinkFinder.class);
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Resolves a wikilink string to a full file path using the repository's connection.
     * Search order:
     * 1. Direct cache hit (Name, Name.ext, or Path)
     * 2. Database query against normalized columns in the FILES table
     * * @param conn The database connection from the repository
     * @param wikilink The raw wikilink text (e.g., "MyNote" or "[[MyNote#Header]]")
     * @return The resolved file path or null if not found
     */
    public String resolveWikilink(Connection conn, String wikilink) {
        if (wikilink == null || wikilink.isEmpty()) return null;

        // Clean link if it contains header or block references
        String cleanLink = wikilink.split("#")[0]
                .replace("[[", "")
                .replace("]]", "");

        // 1. Check local cache
        if (cache.containsKey(cleanLink)) {
            return cache.get(cleanLink);
        }

        // 2. Query the repository for normalized matches
        String sql = "SELECT file_path FROM FILES WHERE file_path = ? OR file_name_ext = ? OR file_name = ? LIMIT 1";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, cleanLink);
            pstmt.setString(2, cleanLink);
            pstmt.setString(3, cleanLink);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String resolved = rs.getString("file_path");
                    cache.put(cleanLink, resolved);
                    return resolved;
                }
            }
        } catch (SQLException e) {
            logger.error("Error resolving wikilink via repository: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Clears the internal resolution cache.
     */
    public void clearCache() {
        cache.clear();
    }
}
