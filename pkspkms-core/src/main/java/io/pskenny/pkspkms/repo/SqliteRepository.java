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

package io.pskenny.pkspkms.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.repo.sqlite.SqliteFiles;

/**
 * Repository-level contract. The raw connection stays private to the
 * {@link FilesDao} — this interface and its implementers never hand one out
 * (BUGS.md P3: the old `getConnection()` leak). Test suites that need direct
 * SQL use `SQLitePksFileRepository`'s concrete `@Deprecated` connection
 * accessor, not this interface.
 */
public interface SqliteRepository extends PksFileRepository {
    ObjectMapper getJsonMapper();

    /** Typed SQL surface for the FILES row graph. */
    SqliteFiles files();
}
