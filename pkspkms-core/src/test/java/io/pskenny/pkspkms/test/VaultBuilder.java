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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Fluent wrapper around {@link FileUtil#createFile} — the test-vault idiom in
 * one place, including the fact every fixture list relies on: a note written
 * with NONEMPTY frontmatter gets the stamped heading line
 * {@code # This is the body content for: <file>} at the top of its body
 * (writes chain, failures propagate).
 */
public final class VaultBuilder {

    private final Path dir;

    private VaultBuilder(Path dir) {
        this.dir = dir;
    }

    public static VaultBuilder at(Path dir) {
        return new VaultBuilder(dir);
    }

    public VaultBuilder note(String fileName, Map<String, Object> frontmatter) throws IOException {
        return note(fileName, frontmatter, "");
    }

    public VaultBuilder note(String fileName, Map<String, Object> frontmatter, String content) throws IOException {
        FileUtil.createFile(dir, fileName, frontmatter == null ? Map.of() : frontmatter, content);
        return this;
    }

    /** Text blocks are common enough to deserve their own (Java 15+ blocks tr). */
    public VaultBuilder md(String fileName, Map<String, Object> frontmatter, String block) throws IOException {
        return note(fileName, frontmatter, block);
    }
}
