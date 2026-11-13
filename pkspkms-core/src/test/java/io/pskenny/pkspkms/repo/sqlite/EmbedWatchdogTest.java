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

import io.pskenny.pkspkms.luabase.LuaBaseProcessor;
import io.pskenny.pkspkms.luabase.NaiveBaseToLuaBaseConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Embed-level DoS backstop (BUGS.md M2): an embed whose note-controlled Lua
 * loops forever must be marked "ERROR" after the per-embed timeout instead of
 * stalling the whole mount/render pass.
 *
 * luaj 3.0.1 has no instruction hooks on compiled chunks, so the watchdog is
 * a wall-clock budget on a daemon worker pool; the stalled worker leaks until
 * process exit — bounded to the pool size (documented residual).
 */
public class EmbedWatchdogTest {

    private static final String SPEC =
            "views:\n"
            + "  - type: table\n"
            + "    filters:\n"
            + "      and:\n"
            + "        - '(function() while true do end end)()'\n";

    @TempDir
    Path tmp;

    @Test
    @Timeout(30)
    void infiniteFilterIsMarkedErrorWithinBudget() throws Exception {
        Path db = tmp.resolve("watchdog.db");
        String dbUrl = "jdbc:sqlite:" + db;

        io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository repo =
                new io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository(dbUrl, null,
                        new io.pskenny.pkspkms.io.fs.JavaFileSystem(tmp.toFile()), false);

        // One FILES row (so the filter loop actually evaluates against a
        // corpus entry) and one hostile luabase EMBEDS row whose filter
        // never terminates. PreparedStatements keep the quotes in the spec intact.
        try (var insertFile = repo.getConnection().prepareStatement(
                "INSERT INTO FILES (file_path, file_name, file_name_ext, body) VALUES (?, ?, ?, ?)");
             var insertEmbeds = repo.getConnection().prepareStatement(
                "INSERT INTO EMBEDS (file_id, type, original_match) VALUES (?, ?, ?)")) {
            insertFile.setString(1, "a.md");
            insertFile.setString(2, "a");
            insertFile.setString(3, "a.md");
            insertFile.setString(4, "x");
            insertFile.executeUpdate();
            insertEmbeds.setInt(1, 1);
            insertEmbeds.setString(2, "luabase");
            insertEmbeds.setString(3, SPEC);
            insertEmbeds.executeUpdate();
        }

        SqliteEmbedProcessor processor = new SqliteEmbedProcessor(dbUrl,
                new LuaBaseProcessor(), new NaiveBaseToLuaBaseConverter(), repo, 400);
        long start = System.nanoTime();
        processor.processAll();
        long ms = (System.nanoTime() - start) / 1_000_000;

        try (Statement s = repo.getConnection().createStatement();
             ResultSet rs = s.executeQuery("SELECT generated_content FROM EMBEDS")) {
            assertTrue(rs.next(), "embed row present");
            assertEquals("ERROR", rs.getString(1), "hostile embed renders as ERROR after budget");
        }
        assertTrue(ms < 5_000, "mount must not stall on a hostile embed (took " + ms + "ms)");
    }
}
