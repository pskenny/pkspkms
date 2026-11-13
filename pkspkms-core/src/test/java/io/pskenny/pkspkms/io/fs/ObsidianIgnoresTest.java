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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ObsidianIgnoresTest {

    @TempDir
    Path vault;

    private Predicate<String> ignoresWith(String appJson) throws IOException {
        Path obsidianDir = vault.resolve(".obsidian");
        Files.createDirectories(obsidianDir);
        if (appJson != null) {
            Files.writeString(obsidianDir.resolve("app.json"), appJson);
        }
        return ObsidianIgnores.from(new JavaFileSystem(vault.toFile()));
    }

    @Test
    void missingAppJson_ignoresNothing() throws IOException {
        Predicate<String> ignores = ignoresWith(null);

        assertFalse(ignores.test("Archive/x.md"));
        assertFalse(ignores.test("Anything.png"));
    }

    @Test
    void malformedJson_ignoresNothing() throws IOException {
        Predicate<String> ignores = ignoresWith("{not json");

        assertFalse(ignores.test("Archive/x.md"));
    }

    @Test
    void noUserIgnoreFiltersKey_ignoresNothing() throws IOException {
        Predicate<String> ignores = ignoresWith("{\"promptDelete\": false}");

        assertFalse(ignores.test("Archive/x.md"));
    }

    @Test
    void folderFilter_respectsPathBoundary() throws IOException {
        Predicate<String> ignores = ignoresWith("{\"userIgnoreFilters\": [\"Archive/\"]}");

        assertTrue(ignores.test("Archive/x.md"));
        assertTrue(ignores.test("Archive/sub/y.md"));
        assertFalse(ignores.test("Archives/x.md"), "prefix must not leak into sibling dirs");
    }

    @Test
    void filtersMatchCaseInsensitively() throws IOException {
        Predicate<String> ignores = ignoresWith("{\"userIgnoreFilters\": [\"archive/\"]}");

        assertTrue(ignores.test("Archive/x.md"));
    }

    @Test
    void specificFileFilter() throws IOException {
        Predicate<String> ignores = ignoresWith("{\"userIgnoreFilters\": [\"Secrets/private.md\"]}");

        assertTrue(ignores.test("Secrets/private.md"));
        assertFalse(ignores.test("Secrets/other.md"));
    }

    @Test
    void bareFileFilter() throws IOException {
        Predicate<String> ignores = ignoresWith("{\"userIgnoreFilters\": [\"drafts\"]}");

        assertTrue(ignores.test("drafts"));
        assertTrue(ignores.test("drafts/x.md"));
        assertFalse(ignores.test("my-drafts/x.md"));
    }

    @Test
    void wildcardFilter() throws IOException {
        Predicate<String> ignores = ignoresWith("{\"userIgnoreFilters\": [\"*.jpg\"]}");

        assertTrue(ignores.test("Images/p.jpg"));
        assertTrue(ignores.test("p.jpg"));
        assertFalse(ignores.test("p.jpeg"), "'*' must not swallow the dot");
    }

    @Test
    void filtersAndPathsAreSlashesTrimmed() throws IOException {
        Predicate<String> ignores = ignoresWith("{\"userIgnoreFilters\": [\"/logseq/\"]}");

        assertTrue(ignores.test("logseq/x.md"));
    }
}
