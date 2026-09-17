package io.pskenny.pkspkms.repo.sqlite;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    void testVaultAliasesTableCreated() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
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

    @Test
    @DisplayName("EMBEDS table has vault_alias_id column")
    void testEmbedsHasVaultAliasIdColumn() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("PRAGMA table_info(EMBEDS)")) {
                boolean hasVaultAliasId = false;
                while (rs.next()) {
                    if ("vault_alias_id".equals(rs.getString("name"))) {
                        hasVaultAliasId = true;
                        break;
                    }
                }
                assertTrue(hasVaultAliasId, "EMBEDS table should have vault_alias_id column");
            }
        }
    }

    @Test
    @DisplayName("LINKS table has vault_alias_id column")
    void testLinksHasVaultAliasIdColumn() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            SQLiteSchema.init(conn);

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("PRAGMA table_info(LINKS)")) {
                boolean hasVaultAliasId = false;
                while (rs.next()) {
                    if ("vault_alias_id".equals(rs.getString("name"))) {
                        hasVaultAliasId = true;
                        break;
                    }
                }
                assertTrue(hasVaultAliasId, "LINKS table should have vault_alias_id column");
            }
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
}
