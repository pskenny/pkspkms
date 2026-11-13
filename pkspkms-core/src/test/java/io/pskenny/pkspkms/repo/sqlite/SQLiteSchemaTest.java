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

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class SQLiteSchemaTest {

    private static final String TEST_DB_PATH = "test_schema.db";
    private static final String DB_URL = "jdbc:sqlite:" + TEST_DB_PATH;
    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(Path.of(TEST_DB_PATH));
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(Path.of(TEST_DB_PATH));
    }

    @Test
    @DisplayName("VAULT_ALIASES table is created")
    void testVaultAliasesTableCreated() throws SQLException {        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='VAULT_ALIASES'")) {
                assertTrue(rs.next(), "VAULT_ALIASES table should exist");
            }
        }
    }

    @Test
    @DisplayName("FILES table has blake3 column")
    void testFilesHasBlake3Column() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("PRAGMA table_info(FILES)")) {
                boolean hasBlake3 = false;
                while (rs.next()) {
                    if ("blake3".equals(rs.getString("name"))) {
                        hasBlake3 = true;
                        break;
                    }
                }
                assertTrue(hasBlake3, "FILES table should have blake3 column");
            }
        }
    }

    @Test
    @DisplayName("FILES table has vault_alias_id column")
    void testFilesHasVaultAliasIdColumn() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("PRAGMA table_info(FILES)")) {
                boolean hasVaultAliasId = false;
                while (rs.next()) {
                    if ("vault_alias_id".equals(rs.getString("name"))) {
                        hasVaultAliasId = true;
                        break;
                    }
                }
                assertTrue(hasVaultAliasId, "FILES table should have vault_alias_id column");
            }
        }
    }

    // --- v2 slim shapes: the dead FILES columns are gone natively ---

    private boolean columnPresent(Connection conn, String table, String column) throws SQLException {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tablePresent(Connection conn, String table) throws SQLException {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")) {
            return rs.next();
        }
    }

    @Test
    @DisplayName("Fresh DB is v3 natively: no dead columns, body + FTS present")
    void freshDbIsV3Natively() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);
            assertFalse(columnPresent(conn, "FILES", "file_created"));
            assertFalse(columnPresent(conn, "FILES", "last_sync"));
            assertFalse(columnPresent(conn, "FILES", "frontmatter"));
            assertFalse(columnPresent(conn, "LINKS", "vault_alias_id"));
            assertFalse(columnPresent(conn, "EMBEDS", "vault_alias_id"));
            assertFalse(columnPresent(conn, "EMBEDS", "computed_props"));
            assertTrue(columnPresent(conn, "FILES", "body"));
            assertTrue(tablePresent(conn, "files_fts"));
            assertEquals(String.valueOf(SQLiteSchema.SCHEMA_VERSION), metaVersion(conn));
        }
    }

    @Test
    @DisplayName("v1 stamped DB migrates: dead columns dropped, rows kept")
    void v1DbMigratesDeadColumnsAway() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (var stmt = conn.createStatement()) {
                // hand-build the exact v1 shape + one live row + stamp v1
                stmt.execute("CREATE TABLE FILES (id INTEGER PRIMARY KEY AUTOINCREMENT, vault_alias_id INTEGER, file_path TEXT NOT NULL UNIQUE, file_name TEXT, file_name_ext TEXT, file_created INTEGER, file_last_modified INTEGER, last_sync INTEGER, properties json, frontmatter json, blake3 TEXT);");
                stmt.execute("CREATE TABLE LINKS (vault_alias_id INTEGER, source_file_id INTEGER, target_file_id INTEGER, link_type TEXT, PRIMARY KEY (source_file_id, target_file_id, link_type));");
                stmt.execute("CREATE TABLE EMBEDS (id INTEGER PRIMARY KEY AUTOINCREMENT, vault_alias_id INTEGER, file_id INTEGER, type TEXT, original_match TEXT, target_file TEXT, target_header TEXT, generated_content TEXT, computed_props TEXT);");
                stmt.execute("CREATE TABLE VAULT_ALIASES (id INTEGER PRIMARY KEY AUTOINCREMENT, alias TEXT, directory TEXT, is_virtual INTEGER, created_at INTEGER);");
                stmt.execute("CREATE TABLE META (key TEXT PRIMARY KEY, value TEXT);");
                stmt.execute("INSERT INTO META VALUES ('schema_version', '1');");
                stmt.execute("INSERT INTO FILES (file_path, properties, blake3, file_last_modified) VALUES ('old.md', '{}', 'abc', 123);");
                stmt.execute("INSERT INTO LINKS (source_file_id, target_file_id, link_type) VALUES (1, 1, 'outgoing');");
                stmt.execute("INSERT INTO EMBEDS (file_id, type, original_match) VALUES (1, 'base', 'views:');");
            }

            SQLiteSchema.init(conn);

            assertEquals(String.valueOf(SQLiteSchema.SCHEMA_VERSION), metaVersion(conn), "stamped current");
            assertFalse(columnPresent(conn, "FILES", "file_created"));
            assertFalse(columnPresent(conn, "LINKS", "vault_alias_id"));
            assertTrue(columnPresent(conn, "FILES", "body"), "v3 adds the body column");
            assertTrue(tablePresent(conn, "files_fts"), "v3 adds the FTS index");
            // NULL mtime = forced one-shot reparse (bodies backfill next load)
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT file_last_modified FROM FILES WHERE file_path = 'old.md'")) {
                rs.next();
                assertNull(rs.getObject(1), "mtime nulled to force reparse");
            }
        }
    }

    @Test
    @DisplayName("v6 lever: stamped v5 migrates to current with the mtime-null reparse")
    void v5DbMigratesWithReparseLever() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            // minimal current shape stamped one version behind
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE FILES (id INTEGER PRIMARY KEY AUTOINCREMENT, vault_alias_id INTEGER REFERENCES VAULT_ALIASES(id), file_path TEXT NOT NULL UNIQUE, file_name TEXT, file_name_ext TEXT, file_last_modified INTEGER, properties json, blake3 TEXT, body TEXT NOT NULL DEFAULT '');");
                stmt.execute("CREATE TABLE VAULT_ALIASES (id INTEGER PRIMARY KEY AUTOINCREMENT, alias TEXT NOT NULL UNIQUE, directory TEXT NOT NULL, is_virtual INTEGER NOT NULL DEFAULT 0, created_at INTEGER);");
                stmt.execute("CREATE TABLE META (key TEXT PRIMARY KEY, value TEXT);");
                stmt.execute("INSERT INTO META VALUES ('schema_version', '5');");
                stmt.execute("INSERT INTO VAULT_ALIASES (alias, directory, is_virtual) VALUES ('v', '', 1);");
                stmt.execute("INSERT INTO FILES (file_path, file_last_modified, properties, blake3) VALUES ('levers.md', 4242, '{}', 'h');");
            }

            SQLiteSchema.init(conn);

            assertEquals(String.valueOf(SQLiteSchema.SCHEMA_VERSION), metaVersion(conn), "stamped current");
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT file_last_modified FROM FILES WHERE file_path = 'levers.md'")) {
                rs.next();
                assertNull(rs.getObject(1), "mtime nulled → re-extract rides v6 (body render + new spans)");
            }
        }
    }

    @Test
    @DisplayName("EMBEDS table has no legacy vault_alias_id column (v2 slim shape)")
    void testEmbedsHasNoVaultAliasIdColumn() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);
            assertFalse(columnPresent(conn, "EMBEDS", "vault_alias_id"));
        }
    }

    @Test
    @DisplayName("LINKS table has no legacy vault_alias_id column (v2 slim shape)")
    void testLinksHasNoVaultAliasIdColumn() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);
            assertFalse(columnPresent(conn, "LINKS", "vault_alias_id"));
        }
    }

    @Test
    @DisplayName("VAULT_ALIASES table has correct columns")
    void testVaultAliasesColumns() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("PRAGMA table_info(VAULT_ALIASES)")) {
                boolean hasAlias = false, hasDirectory = false, hasIsVirtual = false, hasCreatedAt = false;
                while (rs.next()) {
                    String name = rs.getString("name");
                    if ("alias".equals(name)) hasAlias = true;
                    if ("directory".equals(name)) hasDirectory = true;
                    if ("is_virtual".equals(name)) hasIsVirtual = true;
                    if ("created_at".equals(name)) hasCreatedAt = true;
                }
                assertTrue(hasAlias, "VAULT_ALIASES should have alias column");
                assertTrue(hasDirectory, "VAULT_ALIASES should have directory column");
                assertTrue(hasIsVirtual, "VAULT_ALIASES should have is_virtual column");
                assertTrue(hasCreatedAt, "VAULT_ALIASES should have created_at column");
            }
        }
    }

    // --- Versioned lifecycle (BUGS.md B1) ---

    private String metaVersion(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT value FROM META WHERE key = 'schema_version'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    @DisplayName("Fresh DB: schema stamped with the current version")
    void versionStampedOnFreshDb() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);
            assertEquals(String.valueOf(SQLiteSchema.SCHEMA_VERSION), metaVersion(conn));
        }
    }

    @Test
    @DisplayName("Repeated init is idempotent and keeps data")
    void repeatedInitIsIdempotent() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);
            try (var stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO VAULT_ALIASES (alias, directory, is_virtual) VALUES ('a', '', 1)");
            }
            long afterFirst;
            try (var stmt = conn.createStatement()) {
                afterFirst = countRows(stmt, "VAULT_ALIASES");
            }

            SQLiteSchema.init(conn);
            SQLiteSchema.init(conn);

            try (var stmt = conn.createStatement()) {
                assertEquals(afterFirst, countRows(stmt, "VAULT_ALIASES"), "no data loss across inits");
            }
            assertEquals(String.valueOf(SQLiteSchema.SCHEMA_VERSION), metaVersion(conn));
        }
    }

    @Test
    @DisplayName("Legacy DB (no META, recognizable layout) is adopted")
    void legacyLayoutAdopted() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (var stmt = conn.createStatement()) {
                // Build a pre-versioning FILES table by hand
                stmt.execute("CREATE TABLE FILES (id INTEGER PRIMARY KEY AUTOINCREMENT, file_path TEXT, file_name TEXT, file_name_ext TEXT, file_last_modified INTEGER, properties json, blake3 TEXT);");
                stmt.execute("CREATE TABLE VAULT_ALIASES (id INTEGER PRIMARY KEY AUTOINCREMENT, alias TEXT, directory TEXT, is_virtual INTEGER, created_at INTEGER);");
                stmt.execute("CREATE TABLE LINKS (source_file_id INTEGER, target_file_id INTEGER, link_type TEXT);");
                stmt.execute("CREATE TABLE EMBEDS (id INTEGER PRIMARY KEY AUTOINCREMENT, file_id INTEGER, type TEXT, original_match TEXT);");
                stmt.execute("INSERT INTO FILES (file_path, properties, blake3, file_last_modified) VALUES ('old.md', '{}', 'abc', 123);");
            }

            SQLiteSchema.init(conn);

            assertEquals(String.valueOf(SQLiteSchema.SCHEMA_VERSION), metaVersion(conn), "version stamped");
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM FILES")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "legacy row kept");
            }
        }
    }

    @Test
    @DisplayName("Unsupported legacy layout is rejected, not wiped")
    void unsupportedLegacyLayoutRejected() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE FILES (id INTEGER PRIMARY KEY AUTOINCREMENT, file_path TEXT);");
                stmt.execute("INSERT INTO FILES (file_path) VALUES ('x.md');");
            }

            SQLException e = assertThrows(SQLException.class, () -> SQLiteSchema.init(conn));
            assertTrue(e.getMessage().contains("Unsupported legacy"), e.getMessage());
            // The reject must not have dropped the user's tables
            assertEquals(1, countRows(conn.createStatement(), "FILES"));
        }
    }

    private long countRows(Statement stmt, String table) throws SQLException {
        try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // --- M6: one-time FTS parity rebuild gate ---

    @Test
    @DisplayName("drifted files_fts heals on the first post-drift init (M6)")
    void driftedFtsHealsOnFirstInit() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);
            try (Statement s = conn.createStatement()) {
                s.execute("INSERT INTO FILES (file_path, file_name, file_name_ext, body) "
                        + "VALUES ('a.md', 'a', 'a.md', 'fermion charger')");
            }
            // Simulate a drifted index: remove an FTS row that mirrors FILES.
            // The drift predates this build's marker (pre-batch database) —
            // clear the marker so the rebuild gate fires on this init.
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM files_fts WHERE rowid = (SELECT id FROM FILES WHERE file_path = 'a.md')");
            }
            // The drift predates this build's marker (pre-batch database) —
            // simulate by clearing the marker so the rebuild gate fires
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM META WHERE key = 'fts_parity'");
            }
            // First init with the marker missing → one rebuild
            SQLiteSchema.init(conn);
        }
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM files_fts WHERE files_fts MATCH 'fermion'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "drifted index healed by the one-time rebuild");
        }
    }

    @Test
    @DisplayName("the rebuild runs once — the marker prevents per-boot cost (M6)")
    void ftsRebuildHappensOnce() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);
            try (Statement s = conn.createStatement()) {
                s.execute("INSERT INTO FILES (file_path, file_name, file_name_ext, body) "
                        + "VALUES ('a.md', 'a', 'a.md', 'fermion charger')");
            }
        }
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT value FROM META WHERE key = 'fts_parity'")) {
            assertTrue(rs.next(), "marker stamped on the fresh path");
            assertEquals(String.valueOf(SQLiteSchema.SCHEMA_VERSION), rs.getString("value"));
        }
        // Simulate drift AFTER the marker exists, then init again — no rebuild
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM files_fts WHERE rowid = (SELECT id FROM FILES WHERE file_path = 'a.md')");
            }
            SQLiteSchema.init(conn);
        }
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM files_fts WHERE files_fts MATCH 'fermion'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "no second rebuild after the marker");
        }
    }
}
