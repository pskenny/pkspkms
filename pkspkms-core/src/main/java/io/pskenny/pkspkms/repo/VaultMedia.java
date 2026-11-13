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

import java.util.Map;

/** Binary/embed-read surface: MCP binary access, pkspkms:// caching, rendered content. */
public interface VaultMedia {

    /**
     * Read-only binary surface for MCP: {@code mode=stat} gives
     * {@code {path, size, modified, ext}}; {@code mode=base64} adds a
     * bounded Base64 {@code data} field (rejected over 5 MB). Same path
     * validation rules as {@link VaultReader#readFile}.
     */
    Map<String, Object> binaryFile(String path, String mode);

    Map<String, Object> cacheFile(String address, String location, String cacheDirectory);

    /**
     * Per-alias {@code {filePath, blake3}} pairs for pkspkms:// targets.
     * Main-vault rows (vault_alias_id null) are excluded: /cache only serves
     * registered aliases.
     */
    Map<String, Object> manifest();

    String getMarkdownFromLuaBase(String luaBaseYaml);

    String getMarkdownFromBase(String baseYaml);
}
