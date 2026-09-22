package io.pskenny.pkspkms.repo.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

// Performance PRAGMAs applied to every SQLite connection PKSPKMS opens.
public final class SQLitePragmas {

    private static final String[] PRAGMAS = {
            "PRAGMA journal_mode = WAL;",
            "PRAGMA synchronous = NORMAL;",
            "PRAGMA temp_store = MEMORY;",
            "PRAGMA cache_size = -20000;", // 20MB in-memory database cache
    };

    private SQLitePragmas() {}

    public static void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String pragma : PRAGMAS) {
                stmt.execute(pragma);
            }
        }
    }
}
