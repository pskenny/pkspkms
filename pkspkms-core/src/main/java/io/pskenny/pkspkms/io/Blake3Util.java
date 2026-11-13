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

package io.pskenny.pkspkms.io;

import org.apache.commons.codec.digest.Blake3;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Blake3Util {

    private static final int BUFFER_SIZE = 8192;

    public static String hashFile(File file) throws IOException {
        Blake3 blake3 = Blake3.initHash();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                blake3.update(buffer, 0, bytesRead);
            }
        }
        byte[] hash = new byte[32];
        blake3.doFinalize(hash);
        return bytesToHex(hash);
    }

    public static String hashBytes(byte[] data) {
        Blake3 blake3 = Blake3.initHash();
        blake3.update(data);
        byte[] hash = new byte[32];
        blake3.doFinalize(hash);
        return bytesToHex(hash);
    }

    public static String hashStream(InputStream is) throws IOException {
        Blake3 blake3 = Blake3.initHash();
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            blake3.update(buffer, 0, bytesRead);
        }
        byte[] hash = new byte[32];
        blake3.doFinalize(hash);
        return bytesToHex(hash);
    }

    public static String hashString(String data) {
        return hashBytes(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
