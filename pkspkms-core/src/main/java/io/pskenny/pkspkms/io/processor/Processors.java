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

package io.pskenny.pkspkms.io.processor;

import io.pskenny.pkspkms.io.fs.PkmsEntry;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.Blake3Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;

public class Processors {
    private static final Logger logger = LoggerFactory.getLogger(Processors.class);

    private final MarkdownProcessor markdownProcessor = new MarkdownProcessor();

    public PksFile initialReadOnlyParse(PkmsEntry entry, PkmsFileSystem fs) {
        try {
            PksFile pksFile;
            if (entry.name().toLowerCase(Locale.ROOT).endsWith(".md")) {
                byte[] bytes;
                try (InputStream in = fs.openInput(entry.relativePath())) {
                    bytes = in.readAllBytes();
                }
                pksFile = markdownProcessor.initialParse(entry, bytes);
            } else {
                pksFile = new PksFile(entry.relativePath(), new HashMap<>());
                try (InputStream in = fs.openInput(entry.relativePath())) {
                    pksFile.setHash(Blake3Util.hashStream(in));
                }
                logger.debug("Stream-hashed binary: {}", entry.relativePath());
            }
            if (pksFile != null) {
                pksFile.setLastModified(entry.lastModified());
            }
            return pksFile;
        } catch (Exception e) {
            logger.error("Couldn't parse file at: {}", entry.relativePath(), e);
        }
        return null;
    }
}
