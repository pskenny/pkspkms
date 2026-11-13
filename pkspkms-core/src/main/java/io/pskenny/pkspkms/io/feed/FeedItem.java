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

package io.pskenny.pkspkms.io.feed;

import java.util.List;

/**
 * One item: blog post, podcast episode, or Atom entry. Fields are nullable
 * (or empty-list) when the source omits them; seconds/episode/season/
 * commentsCount bind as numbers for typed range comparisons.
 */
public record FeedItem(String title, String url, String published, String author, String summary,
                       String enclosureUrl, String guid,
                       List<String> tags, Integer seconds, Integer episode, Integer season,
                       String image, String comments, Integer commentsCount, String enclosureType) {}
