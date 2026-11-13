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

/**
 * When base/luabase embeds render relative to a vault load. Embed passes are
 * global over every mounted vault, so the server pattern is: mount each vault
 * with {@link #DEFERRED}, then call {@code processEmbeds()} once.
 */
public enum EmbedMode {
    /** Render embeds at the end of this load (CLI export path). */
    RENDER_NOW,
    /** Seed EMBEDS rows only; the caller renders later via processEmbeds(). */
    DEFERRED,
}
