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

import io.pskenny.pkspkms.io.fs.PkmsFileSystem;

/**
 * Load/mount orchestration: vault walk, incremental diff, virtual vault
 * mounts, embed rendering. The application drives the sequence
 * (load → mount all → processEmbeds); repositories only carry the phases.
 */
public interface VaultLoader {

    void loadDirectoryIntoRepository();

    /**
     * Same as {@link #loadDirectoryIntoRepository()}, with embed rendering
     * mode explicit for callers that run a global embed pass later. The
     * default is {@link EmbedMode#RENDER_NOW} (CLI export path).
     */
    default void loadDirectoryIntoRepository(EmbedMode mode) {
        loadDirectoryIntoRepository();
    }

    void loadVirtualVault(PkmsFileSystem aliasFs, String alias);

    /** Renders base/luabase embeds; call once after all vaults have loaded. */
    default void processEmbeds() {}
}
