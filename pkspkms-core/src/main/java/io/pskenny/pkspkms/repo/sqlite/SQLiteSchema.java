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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Versioned schema lifecycle.
 *
 * {@link #init} creates the schema once, stamps {@code META.schema_version},
 * and never drops anything: reconstructions run idempotent repair DDL, legacy
 * pre-versioning databases are adopted (or rejected with a clear message), and
 * older versions walk a migration ladder (v3 adds FILES.body + the FTS5
 * full-text index; v4 forces a one-shot re-extract for the outline/span
 * key-shape migration — no table changes, the mtime-null lever only; v5
 * stamps the wikilink span facts with the parsed `#anchor` (plus the wiki
 * display/alt texts) — again no table changes, mtime-null re-extract only;
 * v6 renders the FTS body to plain text (TextCollectingVisitor — seam:
 * `text:` matches/searches rendered prose, not raw markdown) and stamps
 * `taskSpans`/`embedSpans` (the third span fact family) — no table changes,
 * mtime-null re-extract only; v7 lifts frontmatter wikilink-shaped values
 * ({@code key: "[[Note]]"}) into the graph — same span facts with
 * kind=frontmatter, resolved/dangling like body links; export leaves the
 * frontmatter region literal — the old drop-and-recreate made --db
 * persistence illusory.
 *
 * {@link #reset} is the explicit escape hatch behind the CLI's --reset-db
 * flag: it drops and rebuilds the tables, forcing a full rescan.
 */
public final class SQLiteSchema {

    public static final int SCHEMA_VERSION = 7;

    private static final Logger logger = LoggerFactory.getLogger(SQLiteSchema.class);

    private static final String CREATE_META_TABLE =
            "CREATE TABLE IF NOT EXISTS META (key TEXT PRIMARY KEY, value TEXT);";

    private static final String CREATE_VAULT_ALIASES_TABLE =
            "CREATE TABLE IF NOT EXISTS VAULT_ALIASES (id INTEGER PRIMARY KEY AUTOINCREMENT, alias TEXT NOT NULL UNIQUE, directory TEXT NOT NULL, is_virtual INTEGER NOT NULL DEFAULT 0, created_at INTEGER);";

    // v3 shape: dead columns dropped (v2 sweep), body column + FTS added (v3).
    // body is never NULL: '' marks unindexed rows (binaries, media cache stubs)
    // so the external-content FTS invariant (every indexed rowid has a content
    // row) holds through every write.
    private static final String CREATE_FILES_TABLE =
            "CREATE TABLE IF NOT EXISTS FILES (id INTEGER PRIMARY KEY AUTOINCREMENT, vault_alias_id INTEGER REFERENCES VAULT_ALIASES(id), file_path TEXT NOT NULL UNIQUE, file_name TEXT, file_name_ext TEXT, file_last_modified INTEGER, properties json, blake3 TEXT, body TEXT NOT NULL DEFAULT '');";

    private static final String CREATE_EMBEDS_TABLE =
            "CREATE TABLE IF NOT EXISTS EMBEDS (id INTEGER PRIMARY KEY AUTOINCREMENT, file_id INTEGER, type TEXT, original_match TEXT, target_file TEXT, target_header TEXT, generated_content TEXT);";

    private static final String CREATE_LINKS_TABLE =
            "CREATE TABLE IF NOT EXISTS LINKS (source_file_id INTEGER, target_file_id INTEGER, link_type TEXT, PRIMARY KEY (source_file_id, target_file_id, link_type));";

    // Backlink lookups filter on target; the LINKS PK (source, target, link_type)
    // can't serve them
    private static final String CREATE_LINKS_TARGET_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_links_target ON LINKS (target_file_id, source_file_id);";

    // Full-text index over note bodies: external-content — FTS5 stores only the
    // tokenized index and reads body values from FILES at query time. Sync is
    // trigger-driven (the standard external-content pattern); empty-body rows
    // (binaries, media) token to nothing and never match text: probes.
    private static final String CREATE_FILES_FTS =
            "CREATE VIRTUAL TABLE IF NOT EXISTS files_fts USING fts5(body, content='FILES', content_rowid='id', tokenize='unicode61');";

    private static final String CREATE_TRIGGER_AI =
            "CREATE TRIGGER IF NOT EXISTS files_ai AFTER INSERT ON FILES BEGIN "
                    + "INSERT INTO files_fts(rowid, body) VALUES (new.id, new.body); END;";

    private static final String CREATE_TRIGGER_AD =
            "CREATE TRIGGER IF NOT EXISTS files_ad AFTER DELETE ON FILES BEGIN "
                    + "INSERT INTO files_fts(files_fts, rowid, body) VALUES('delete', old.id, old.body); END;";

    // Single UPDATE trigger: the delete command ALWAYS precedes the insert —
    // with two same-event UPDATE triggers the firing order is undefined, and
    // a prior insert could be clobbered by the subsequent delete (tokens
    // shared between old and new bodies would vanish from MATCH). The
    // 'delete' command with a NULL/'' old body is an effective no-op.
    private static final String CREATE_TRIGGER_AU =
            "CREATE TRIGGER IF NOT EXISTS files_au AFTER UPDATE ON FILES BEGIN "
                    + "INSERT INTO files_fts(files_fts, rowid, body) VALUES('delete', old.id, old.body); "
                    + "INSERT INTO files_fts(rowid, body) VALUES (new.id, new.body); END;";

    // Dead-column ladder (v1 -> v2). Columns dropped from the slim DDL above
    // when an older database still carries them; idempotent, re-runnable.
    private static final String[][] DEAD_COLUMNS = {
            {"FILES", "file_created"},
            {"FILES", "last_sync"},
            {"FILES", "frontmatter"},
            {"LINKS", "vault_alias_id"},
            {"EMBEDS", "vault_alias_id"},
            {"EMBEDS", "computed_props"},
    };

    private SQLiteSchema() {}

    public static void init(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = OFF;");
            try {
                stmt.execute(CREATE_META_TABLE);
                Integer version = readVersion(stmt);

                if (version == null && !tableExists(stmt, "FILES")) {
                    // Fresh database: create everything and stamp.
                    createTables(stmt);
                    stampVersion(stmt, SCHEMA_VERSION);
                    stampFtsParity(stmt);
                } else if (version == null) {
                    // Legacy pre-versioning database: adopt when the layout is
                    // recognizable, never wipe silently. Normalize dead
                    // columns on the way in.
                    if (!legacyLayoutCompatible(stmt)) {
                        throw new SQLException(
                                "Unsupported legacy database schema (no META.version, unexpected FILES layout). "
                                        + "Rename or delete the database file to rebuild it from the vault.");
                    }
                    migrateTo3(stmt);
                    forceReparse(stmt);
                    stampVersion(stmt, SCHEMA_VERSION);
                    stampFtsParity(stmt);
                } else if (version == SCHEMA_VERSION) {
                    // Idempotent repair: fills in any missing table/index.
                    // Also drops superseded trigger shapes from the pre-FTS
                    // session (the two-trigger files_au pair is replaced by
                    // the single files_au).
                    dropLegacyTriggers(stmt);
                    createTables(stmt);
                    // One-time FTS parity rebuild (BUGS.md M6): the pre-FTS
                    // trigger pair wrote index drift, and a database copied
                    // without its WAL can drift too. The marker row gates the
                    // rebuild to the FIRST boot on this build family — never
                    // a per-boot cost. A failed rebuild logs and continues;
                    // --reset-db remains the escape hatch.
                    try {
                        if (!metaHas(stmt, FTS_PARITY_KEY)) {
                            logger.info("Rebuilding files_fts (one-time parity pass, BUGS.md M6)");
                            stmt.execute("INSERT INTO files_fts(files_fts) VALUES('rebuild');");
                        }
                    } catch (SQLException rebuildFailure) {
                        logger.warn("files_fts rebuild skipped: {}", rebuildFailure.getMessage());
                    }
                    stampFtsParity(stmt);
                } else if (version >= 1 && version < SCHEMA_VERSION) {
                    // Migration ladder v1/v2/v3 -> v4.
                    dropLegacyTriggers(stmt);
                    migrateTo3(stmt);
                    forceReparse(stmt);
                    stampVersion(stmt, SCHEMA_VERSION);
                    stampFtsParity(stmt);
                } else {
                    throw new SQLException("Database " + "schema version " + version
                            + " is newer than this build (" + SCHEMA_VERSION + "). "
                            + "Upgrade PKSPKMS or delete the database file.");
                }
            } finally {
                stmt.execute("PRAGMA foreign_keys = ON;");
            }
        }
    }

    // Superseded trigger shapes from earlier builds — repair/migration runs
    // must not leave a two-trigger UPDATE pair alongside the single files_au
    // (undefined firing order would clobber index re-sync).
    private static void dropLegacyTriggers(Statement stmt) throws SQLException {
        stmt.execute("DROP TRIGGER IF EXISTS files_au_del;");
        stmt.execute("DROP TRIGGER IF EXISTS files_au_ins;");
    }

    // v3 migration: dead columns + body column (NOT NULL '') + FTS index +
    // triggers + a full FTS 'rebuild' + a forced one-shot reparse (NULL
    // mtimes) so real bodies backfill through the regular parse path.
    private static void migrateTo3(Statement stmt) throws SQLException {
        dropDeadColumns(stmt);
        if (!columnExists(stmt, "FILES", "body")) {
            stmt.execute("ALTER TABLE FILES ADD COLUMN body TEXT NOT NULL DEFAULT '';");
        }
        createTables(stmt);
        // Reconstruct the FTS index from the (just-added) content column,
        // otherwise legacy rows predate the sync invariant
        stmt.execute("INSERT INTO files_fts(files_fts) VALUES('rebuild');");
        stmt.execute("UPDATE FILES SET file_last_modified = NULL;");
    }

    // v4 migration: no table/column changes — the outline/generated-key shape
    // changed in the extractor, which only recomputes on change, so the lever
    // (NULL mtimes → one-shot full re-extract through the normal pipeline)
    // makes every note re-extract once. The same lever v3 used for the body
    // backfill; run AFTER migrateTo3 on the ladder (idempotent — selector is
    // full-table and the mtimes are already NULL for v1-v3 walkers).
    private static void forceReparse(Statement stmt) throws SQLException {
        stmt.execute("UPDATE FILES SET file_last_modified = NULL;");
    }

    /** Escape hatch (--reset-db): drop everything and rebuild empty. */
    public static void reset(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = OFF;");
            try {
                stmt.execute("DROP TABLE IF EXISTS files_fts;");
                stmt.execute("DROP TRIGGER IF EXISTS files_ai;");
                stmt.execute("DROP TRIGGER IF EXISTS files_ad;");
                stmt.execute("DROP TRIGGER IF EXISTS files_au;");
                stmt.execute("DROP TABLE IF EXISTS EMBEDS;");
                stmt.execute("DROP TABLE IF EXISTS LINKS;");
                stmt.execute("DROP TABLE IF EXISTS FILES;");
                stmt.execute("DROP TABLE IF EXISTS VAULT_ALIASES;");
                // createTables does not cover META (init creates it separately) —
                // without this, a reset-path stampVersion wrote into a missing
                // table ("no such table META") and every REINDEX start failed on
                // both platforms.
                stmt.execute(CREATE_META_TABLE);
                createTables(stmt);
                stampVersion(stmt, SCHEMA_VERSION);
            } finally {
                stmt.execute("PRAGMA foreign_keys = ON;");
            }
        }
    }

    private static final String FTS_PARITY_KEY = "fts_parity";

    // One-time FTS parity marker (BUGS.md M6): gates the files_fts rebuild to
    // the first boot on this build family. Stamped on healthy paths (fresh
    // create, adopt, migrate — their FTS family is restored as part of the
    // reconstruct) so the rebuild only fires on the repaired/drifted family.
    private static void stampFtsParity(Statement stmt) throws SQLException {
        stampMeta(stmt, FTS_PARITY_KEY, String.valueOf(SCHEMA_VERSION));
    }

    private static boolean metaHas(Statement stmt, String key) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT 1 FROM META WHERE key = '" + key + "'")) {
            return rs.next();
        }
    }

    private static void stampMeta(Statement stmt, String key, String value) throws SQLException {
        stmt.execute("INSERT OR REPLACE INTO META (key, value) VALUES ('" + key + "', '" + value + "');");
    }

    private static void createTables(Statement stmt) throws SQLException {
        stmt.execute(CREATE_VAULT_ALIASES_TABLE);
        stmt.execute(CREATE_FILES_TABLE);
        stmt.execute(CREATE_LINKS_TABLE);
        stmt.execute(CREATE_EMBEDS_TABLE);
        stmt.execute(CREATE_LINKS_TARGET_INDEX);
        stmt.execute(CREATE_FILES_FTS);
        stmt.execute(CREATE_TRIGGER_AI);
        stmt.execute(CREATE_TRIGGER_AD);
        stmt.execute(CREATE_TRIGGER_AU);
    }

    // Column-presence-guarded drops: an ALTER on a missing column throws, so
    // probe first (the ladder must stay re-runnable)
    private static void dropDeadColumns(Statement stmt) throws SQLException {
        for (String[] dead : DEAD_COLUMNS) {
            if (columnExists(stmt, dead[0], dead[1])) {
                stmt.execute("ALTER TABLE " + dead[0] + " DROP COLUMN " + dead[1] + ";");
            }
        }
    }

    private static boolean columnExists(Statement stmt, String table, String column) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Integer readVersion(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT value FROM META WHERE key = 'schema_version'")) {
            if (rs.next()) {
                try {
                    return Integer.parseInt(rs.getString("value").strip());
                } catch (NumberFormatException e) {
                    throw new SQLException("META.schema_version is not a number: "
                            + rs.getString("value"), e);
                }
            }
        }
        return null;
    }

    private static void stampVersion(Statement stmt, int version) throws SQLException {
        stmt.execute("INSERT OR REPLACE INTO META (key, value) VALUES ('schema_version', '"
                + version + "');");
    }

    private static boolean tableExists(Statement stmt, String table) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '"
                        + table + "'")) {
            return rs.next();
        }
    }

    // A pre-versioning DB is adoptable only when its FILES layout carries the
    // columns every code path assumes (file_path, blake3, file_last_modified,
    // properties).
    private static boolean legacyLayoutCompatible(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(FILES)")) {
            boolean hasPath = false, hasBlake3 = false, hasMtime = false, hasProps = false;
            while (rs.next()) {
                String name = rs.getString("name");
                if ("file_path".equals(name)) hasPath = true;
                if ("blake3".equals(name)) hasBlake3 = true;
                if ("file_last_modified".equals(name)) hasMtime = true;
                if ("properties".equals(name)) hasProps = true;
            }
            return hasPath && hasBlake3 && hasMtime && hasProps;
        }
    }
}
