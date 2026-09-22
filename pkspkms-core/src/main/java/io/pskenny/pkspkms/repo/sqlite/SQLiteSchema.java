package io.pskenny.pkspkms.repo.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class SQLiteSchema {

    private static final String CREATE_VAULT_ALIASES_TABLE =
            "CREATE TABLE IF NOT EXISTS VAULT_ALIASES (id INTEGER PRIMARY KEY AUTOINCREMENT, alias TEXT NOT NULL UNIQUE, directory TEXT NOT NULL, is_virtual INTEGER NOT NULL DEFAULT 0, created_at INTEGER);";

    private static final String CREATE_FILES_TABLE =
            "CREATE TABLE IF NOT EXISTS FILES (id INTEGER PRIMARY KEY AUTOINCREMENT, vault_alias_id INTEGER REFERENCES VAULT_ALIASES(id), file_path TEXT NOT NULL UNIQUE, file_name TEXT, file_name_ext TEXT, file_created INTEGER, file_last_modified INTEGER, last_sync INTEGER, properties json, frontmatter json, blake3 TEXT);";

    private static final String CREATE_EMBEDS_TABLE =
            "CREATE TABLE IF NOT EXISTS EMBEDS (id INTEGER PRIMARY KEY AUTOINCREMENT, vault_alias_id INTEGER REFERENCES VAULT_ALIASES(id), file_id INTEGER, type TEXT, original_match TEXT, target_file TEXT, target_header TEXT, generated_content TEXT, computed_props TEXT);";

    private static final String CREATE_LINKS_TABLE =
            "CREATE TABLE IF NOT EXISTS LINKS (vault_alias_id INTEGER REFERENCES VAULT_ALIASES(id), source_file_id INTEGER, target_file_id INTEGER, link_type TEXT, PRIMARY KEY (source_file_id, target_file_id, link_type));";

    public static void init(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = OFF;");

            stmt.execute("DROP TABLE IF EXISTS EMBEDS;");
            stmt.execute("DROP TABLE IF EXISTS LINKS;");
            stmt.execute("DROP TABLE IF EXISTS FILES;");
            stmt.execute("DROP TABLE IF EXISTS VAULT_ALIASES;");

            stmt.execute("PRAGMA foreign_keys = ON;");

            stmt.execute(CREATE_VAULT_ALIASES_TABLE);
            stmt.execute(CREATE_FILES_TABLE);
            stmt.execute(CREATE_LINKS_TABLE);
            stmt.execute(CREATE_EMBEDS_TABLE);

            // Backlink lookups filter on target; the LINKS PK (source, target, link_type)
            // can't serve them
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_links_target ON LINKS (target_file_id, source_file_id);");
        }
    }
}
