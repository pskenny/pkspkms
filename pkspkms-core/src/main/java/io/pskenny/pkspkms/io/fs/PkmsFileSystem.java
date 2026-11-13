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

package io.pskenny.pkspkms.io.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

// Why the abstraction? So I can implement it on Android using SAF
public interface PkmsFileSystem {
    List<PkmsEntry> listFiles(List<String> excludedDirectories) throws IOException;
    PkmsEntry resolve(String relativePath) throws IOException;
    InputStream openInput(String relativePath) throws IOException;
    boolean exists(String relativePath);
    OutputStream openOutput(String relativePath) throws IOException;
    void writeString(String relativePath, String content) throws IOException;
    void copyFrom(InputStream source, String destRelativePath) throws IOException;



    /**
     * File size in bytes; the default counts by streaming so every
     * implementation (including future virtual vaults) is correct.
     */
    default long size(String relativePath) throws IOException {
        long bytes = 0;
        try (InputStream in = openInput(relativePath)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                bytes += n;
            }
        }
        return bytes;
    }
}