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

package io.pskenny.pkspkms.services;

import io.pskenny.pkspkms.repo.sqlite.SqliteFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;

/**
 * Service to resolve wikilinks using the SQLite repository as a backend.
 * Supports resolution by file name, file name with extension, or full path.
 */
public class WikilinkFinder {
    private static final Logger logger = LoggerFactory.getLogger(WikilinkFinder.class);
    private static final int CACHE_CAP = 512;

    // LRU-bounded cache. Synchronized via synchronizedMap; compound access (containsKey/get/put)
    // may race on concurrent misses for the same key, but the worst case is a redundant DB probe,
    // not data corruption.
    private final Map<String, String> cache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_CAP, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_CAP;
                }
            });

    /**
     * Resolves a wikilink string to a full file path through the repository's
     * typed SQL surface (FilesDao) — no raw connection crosses the service
     * layer (BUGS.md P3). Search order:
     * 1. Direct cache hit (Name, Name.ext, or Path)
     * 2. Database probe against normalized columns in the FILES table
     *
     * @param cleanLink The wikilink target (e.g., "MyNote" — heading text
     *                  must already be stripped or it cleans them here)
     * @return The resolved file path or null if not found
     */
    public String resolveWikilink(io.pskenny.pkspkms.repo.sqlite.SqliteFiles files, String wikilink) {
        if (wikilink == null || wikilink.isEmpty()) return null;

        // Clean link: drop display alias, then heading/block references
        String cleanLink = wikilink.split("\\|")[0]
                .split("#")[0]
                .replace("[[", "")
                .replace("]]", "");

        // 1. Single-flight cache probe (BUGS.md D2): one synchronized get —
        // the old containsKey→get pair allowed an LRU eviction in between to
        // return a false dangler for a known-good link
        String cached = cache.get(cleanLink);
        if (cached != null) {
            return cached;
        }

        // 2. Deterministic probe (BUGS.md B7 ordering lives in the DAO):
        //    filename, filename.ext, or full path
        try {
            return files.resolveName(cleanLink)
                    .map(resolved -> {
                        cache.putIfAbsent(cleanLink, resolved);
                        return resolved;
                    })
                    .orElse(null);
        } catch (SQLException e) {
            logger.error("Error resolving wikilink: {}", e.getMessage());
            return null;
        }
    }

    // Loader refresh: after a load's deleted-rows phase the cache must not
    // answer for files that no longer exist.
    public void clear() {
        cache.clear();
    }
}
