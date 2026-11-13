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

package io.pskenny.pkspkms.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public class FileUtil {

    public static void createFile(Path TEST_DIR, String fileName, Map<String, Object> frontmatter) throws IOException {
        createFile(TEST_DIR, fileName, frontmatter, "");
    }

    public static void createFile(Path testDirectory, String fileName, Map<String, Object> frontmatter, String content) throws IOException {
        StringBuilder contentBuilder = new StringBuilder();
        if (frontmatter.isEmpty()) {
            contentBuilder.append(content);

            Path filePath = testDirectory.resolve(fileName);
            Files.writeString(filePath, contentBuilder.toString());
            return;
        }
        contentBuilder.append("---\n");

        for (Map.Entry<String, Object> entry : frontmatter.entrySet()) {
            contentBuilder.append(entry.getKey()).append(": ");
            Object value = entry.getValue();
            if (value instanceof Collection) {
                contentBuilder.append(
                        ((Collection<?>) value).stream()
                                .map(item -> "\n  - " + item.toString())
                                .collect(Collectors.joining())
                );
            } else {
                contentBuilder.append(value.toString());
            }
            contentBuilder.append("\n");
        }

        contentBuilder.append("---\n# This is the body content for: ")
                .append(fileName)
                .append("\n")
                .append(content);

        Path filePath = testDirectory.resolve(fileName);
        Files.writeString(filePath, contentBuilder.toString());
    }

    public static String readTestData(String relativePath) {
        return readFile("test/data/" + relativePath);
    }

    public static String readFile(String relativePath) {
        try {
            Path path = resolveFromProjectRoot(relativePath);
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read test data file: " + relativePath, e);
        }
    }

    public static boolean fileExists(String relativePath) {
        Path path = resolveFromProjectRoot(relativePath);
        return path.toFile().exists();
    }

    public static long countFiles(Path dir) {
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) {
            return 0;
        }
        try {
            try (var stream = Files.walk(dir)) {
                return stream.filter(Files::isRegularFile).count();
            }
        } catch (IOException e) {
            return 0;
        }
    }

    private static Path resolveFromProjectRoot(String relativePath) {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path current = start;
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            if (Files.exists(current.resolve(".git"))) {
                // Reached project root; return candidate even if missing so caller gets proper error
                return current.resolve(relativePath);
            }
            current = current.getParent();
        }
        return start.resolve(relativePath);
    }
}
