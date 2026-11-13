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

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.PropertyTypes;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Read/query surface: row search (property + `text:` FTS), corpus reads,
 * wikilink resolution, file content reads, generated lookup helpers.
 */
public interface VaultReader {

    List<PksFile> searchRegular(CompiledQuery query);

    /**
     * Streaming form; the bridge default delegates to the list variant.
     * SQLite overrides it with the batched-backlink implementation.
     */
    default void searchRegular(CompiledQuery query, Consumer<PksFile> consumer) {
        searchRegular(query).forEach(consumer);
    }

    /**
     * All files as parsed PksFile maps, in storage order. One JSON parse per
     * file — the corpus for single-pass embed rendering.
     */
    List<PksFile> loadCorpus();

    /**
     * Property types declared in the vault's .obsidian/types.json; empty when
     * unknown — semantic default keeps non-SQLite consumers honest.
     */
    default PropertyTypes getPropertyTypes() {
        return PropertyTypes.empty();
    }

    String resolveWikilink(String wikilink);

    /**
     * Raw file content by vault-relative path, or {@code @alias/}-prefixed for
     * virtual vaults. Path traversal and {@code .obsidian}/{@code .trash} are
     * rejected with {@link IllegalArgumentException}.
     */
    String readFile(String filePath);

    /**
     * Blake3 of an indexed file, empty when the file has no FILES row (e.g.
     * cacheFile upserts) — used by export freshness checks. Semantic default:
     * an unknown hash skips freshness work rather than failing it.
     */
    default java.util.Optional<String> blake3Of(String filePath) {
        return java.util.Optional.empty();
    }

    /**
     * Batched parsed properties for exact paths — span provenance reads
     * sources' stored {@code wikilinkSpans} without per-file round-trips.
     */
    Map<String, Map<String, Object>> propertiesForPaths(List<String> paths);

    /**
     * Facet value counts over the query's result set: {@code {resultCount,
     * facets: {key: [{value, count}]}}}. Excluded keys (text, body, filePath,
     * links, backlinks) are rejected with {@link IllegalArgumentException}.
     * The mapping layer compiles {@code query}; malformed queries surface
     * there.
     */
    Map<String, Object> facets(CompiledQuery query, List<String> keys, int limit);
}
