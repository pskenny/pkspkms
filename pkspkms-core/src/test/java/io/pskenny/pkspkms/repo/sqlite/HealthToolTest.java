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

import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP {@code health} tool pins: read-only facts with the ok/degraded gate.
 * Degraded = FTS probe fails, generated-content ERROR rows exist, or
 * cache-shaped rows wait unparsed (properties IS NULL).
 */
public class HealthToolTest {

    @TempDir
    Path vault;

    private static final String DB_URL_BASE = "jdbc:sqlite:";

    private SQLitePksFileRepository freshRepo() {
        return new SQLitePksFileRepository(DB_URL_BASE + vault.resolve("index.db"), null,
                new JavaFileSystem(vault.toFile()), false);
    }

    @Test
    void emptyVaultIsOk() throws Exception {
        try (SQLitePksFileRepository repo = freshRepo()) {
            var health = repo.healthSnapshot();
            assertEquals("ok", health.get("status"));
            var index = (java.util.Map<?, ?>) health.get("index");
            assertEquals(0L, index.get("unparsedRows"));
            assertEquals(0L, index.get("embedErrors"));
            assertFalse(index.containsKey("newestNoteModified"), "no mtime on an empty vault");
            var fts = (java.util.Map<?, ?>) health.get("fts");
            assertTrue("ok".equals(fts.get("probe")) || "empty".equals(fts.get("probe")));
        }
    }

    @Test
    void seededVaultCountsFilesAndAliases() throws Exception {
        Files.writeString(vault.resolve("README.md"), "# main vault note");
        try (SQLitePksFileRepository repo = freshRepo()) {
            repo.loadDirectoryIntoRepository();
            var health = repo.healthSnapshot();
            assertEquals("ok", health.get("status"));

            var vaults = (java.util.List<?>) health.get("vaults");
            assertTrue(vaults.stream().anyMatch(v -> String.valueOf(v).contains("(main)")),
                    "main vault labeled (main): " + vaults);

            var index = (java.util.Map<?, ?>) health.get("index");
            assertTrue((long) Long.parseLong(String.valueOf(index.get("links"))) >= 0);
            assertTrue(index.containsKey("newestNoteModified"), "mtime present after a load");
            var server = (java.util.Map<?, ?>) health.get("server");
            assertEquals(SQLiteSchema.SCHEMA_VERSION, server.get("schemaVersion"));
        }
    }

    @Test
    void unParsedRowsDegrade() throws Exception {
        try (SQLitePksFileRepository repo = freshRepo()) {
            repo.loadDirectoryIntoRepository();
            // cache-shaped row: no properties, no body — like cacheFile upserts
            try (PreparedStatement ps = repo.getConnection().prepareStatement(
                    "INSERT INTO FILES (file_path, file_name, file_name_ext, body) VALUES ('.pkspkms-cache/@x/n.md', 'n', 'n.md', '')")) {
                ps.executeUpdate();
            }
            var health = repo.healthSnapshot();
            assertEquals("degraded", health.get("status"));
            var index = (java.util.Map<?, ?>) health.get("index");
            assertEquals(1L, index.get("unparsedRows"));
        }
    }

    @Test
    void embedErrorRowsDegrade() throws Exception {
        try (SQLitePksFileRepository repo = freshRepo()) {
            repo.loadDirectoryIntoRepository();
            try (PreparedStatement ps = repo.getConnection().prepareStatement(
                    "INSERT INTO EMBEDS (file_id, type, original_match, generated_content) "
                            + "VALUES (999, 'luabase', 'drifted spec', 'ERROR')")) {
                ps.executeUpdate();
            }
            var health = repo.healthSnapshot();
            assertEquals("degraded", health.get("status"));
            var index = (java.util.Map<?, ?>) health.get("index");
            assertEquals(1L, index.get("embedErrors"));
        }
    }
}
