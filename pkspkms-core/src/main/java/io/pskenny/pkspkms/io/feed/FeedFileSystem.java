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

import io.pskenny.pkspkms.io.PathUtil;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem.SynthFile;

import java.io.IOException;
import java.util.Map;
import java.util.Map;

/**
 * Read-only vault over one fetched or local feed: a channel note plus one
 * note per item, all under the feed's title. Built once at server start.
 */
public final class FeedFileSystem extends SynthesizedFileSystem {

    public FeedFileSystem(byte[] feedBytes, String feedUrl, long mtime) throws IOException {
        super(materialize(feedBytes, feedUrl, mtime));
    }

    // Package-visible: FeedCollectionFileSystem inlines feed sources
    static Map<String, SynthFile> materialize(byte[] feedBytes, String feedUrl, long mtime) throws IOException {
        Feed feed = FeedParser.parse(feedBytes);
        String feedDir = PathUtil.safeFileName(feed.title());
        return FeedNotes.materialize(feed, feedDir + ".md", feedDir, feedUrl, mtime);
    }
}
