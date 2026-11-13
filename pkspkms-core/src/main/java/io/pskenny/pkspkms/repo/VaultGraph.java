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

import java.util.List;
import java.util.Map;

/** Link-graph reads: one-hop neighborhood, orphans, hop walks, dangling report. */
public interface VaultGraph {

    /**
     * One note's one-hop link neighborhood {@code {path, outgoing, incoming}}
     * for a path or wikilink-form input; unresolved inputs reject with
     * {@link IllegalArgumentException}.
     */
    Map<String, Object> related(String pathOrWikilink);

    /** Markdown notes with no link edges in either direction, capped at {@code limit}. */
    List<String> orphanPaths(int limit, int offset);

    long orphanCount();

    /**
     * Link-graph neighborhood: 1–2 hops, both directions, capped at 200 ids.
     * Graph-shaped file reductions ride the payload.
     */
    Map<String, Object> graphHops(String pathOrWikilink, int hops);

    /**
     * Vault-wide dangling-wikilink report: aggregated over the loader's
     * {@code danglingLinks} property — near-miss spellings cluster under one
     * normalized bucket; raw spelling of the max-refcount variant names the
     * bucket. Shape: {@code {total_broken, targets: [{target, refcount,
     * sources}]}}; refcount desc by default, sources capped.
     */
    Map<String, Object> brokenLinks(String sort, int limit, int minRefs, String aliasScope);
}
