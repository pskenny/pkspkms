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
            "PRAGMA journal_size_limit = 8388608;", // keep burst WAL from staying huge between checkpoints
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
