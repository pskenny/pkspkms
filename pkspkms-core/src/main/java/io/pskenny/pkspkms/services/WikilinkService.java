package io.pskenny.pkspkms.services;

import io.pskenny.pkspkms.repo.sqlite.SQLitePragmas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Wikilink resolution for embed processing: one connection per service,
 * resolution delegated to {@link WikilinkFinder} (LRU cache + one SQL probe).
 * Replaces the old scan-into-HashMap resolver whose duplicate-name lookup was
 * unordered (7) — one resolver implementation, one semantics.
 */
public class WikilinkService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WikilinkService.class);

    private final Connection conn;
    private final WikilinkFinder finder = new WikilinkFinder();

    public WikilinkService(String dbUrl) {
        try {
            this.conn = DriverManager.getConnection(dbUrl);
            SQLitePragmas.apply(this.conn);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open wikilink resolver connection", e);
        }
    }

    // Resolves by full path, name.ext, or bare name — the finder's probe order
    // covers the old cache's text-then-text+".md" fallback
    public String resolve(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String cleaned = text.startsWith("/") ? text.substring(1) : text;
        return finder.resolveWikilink(conn, cleaned);
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            logger.warn("Failed to close wikilink resolver connection", e);
        }
    }
}
