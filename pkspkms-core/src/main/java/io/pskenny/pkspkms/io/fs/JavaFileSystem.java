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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JavaFileSystem implements PkmsFileSystem {
    private static final Logger logger = LoggerFactory.getLogger(JavaFileSystem.class);
    private static final int BUFFER_SIZE = 8192;
    private final File root;

    public JavaFileSystem(File root) {
        try {
            this.root = root.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to canonicalise root PKMS directory, which is bad: " + root, e);
        }
    }

    @Override
    public List<PkmsEntry> listFiles(List<String> excludedDirectories) throws IOException {
        List<PkmsEntry> result = new ArrayList<>();
        walkFiles(root, excludedDirectories, result, new HashSet<>());
        return result;
    }

    // Resolves a request path to a File under the vault root. Absolute paths
    // are honoured when inside the vault: File(root, absolute) concatenates
    // on Unix, so they must be relativized first. Escapes are rejected.
    private File fileFor(String relativePath) throws IOException {
        File raw = new File(relativePath);
        if (!raw.isAbsolute()) {
            return new File(root, relativePath);
        }

        String absolute = raw.getCanonicalPath();
        String rootPath = root.getCanonicalPath();
        if (!absolute.startsWith(rootPath + File.separator)) {
            throw new IOException("Path escapes the vault: " + relativePath);
        }
        return new File(root, absolute.substring(rootPath.length() + 1));
    }

    @Override
    public PkmsEntry resolve(String relativePath) throws IOException {
        File file = fileFor(relativePath).getCanonicalFile();
        String rootCanonical = root.getCanonicalPath();
        String fileCanonical = file.getCanonicalPath();
        if (!fileCanonical.startsWith(rootCanonical + File.separator)) {
            throw new IOException("File " + fileCanonical + " is not under " + rootCanonical);
        }
        return new JavaFilePkmsEntry(root, file);
    }

    @Override
    public InputStream openInput(String relativePath) throws IOException {
        File input = fileFor(relativePath).getCanonicalFile();
        if (!input.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IOException("Path escapes the vault: " + relativePath);
        }
        return new FileInputStream(input);
    }

    @Override
    public boolean exists(String relativePath) {
        try {
            File file = fileFor(relativePath).getCanonicalFile();
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
                throw new IOException("Path escapes the vault: " + relativePath);
            }
            return file.exists();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public OutputStream openOutput(String relativePath) throws IOException {
        File destination = fileFor(relativePath).getCanonicalFile();

        // Containment before mkdirs: a rejected path must not create
        // directories outside the vault
        if (!destination.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IOException("Path escapes the vault: " + relativePath);
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent);
        }
        return new FileOutputStream(destination);
    }

    @Override
    public void writeString(String relativePath, String content) throws IOException {
        try (OutputStream out = openOutput(relativePath)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void copyFrom(InputStream source, String destRelativePath) throws IOException {
        try (OutputStream out = openOutput(destRelativePath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = source.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } finally {
            source.close();
        }
    }



    private void walkFiles(File dir, List<String> excluded, List<PkmsEntry> result, Set<String> seen) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                // Symlinked directories are never followed: they risk cycles and
                // typically escape the vault
                if (Files.isSymbolicLink(child.toPath())) {
                    continue;
                }
                if (excluded != null && excluded.contains(child.getName())) continue;
                walkFiles(child, excluded, result, seen);
            } else if (child.isFile()) {
                String canonical = child.getCanonicalFile().getCanonicalPath();
                if (Files.isSymbolicLink(child.toPath())
                        && !canonical.startsWith(root.getPath() + File.separator)) {
                    logger.debug("Skipping symlink outside the vault: {}", child);
                    continue;
                }
                // File links that stay in-root index under their target's path
                // and must not duplicate it
                if (seen.add(canonical)) {
                    result.add(new JavaFilePkmsEntry(root, child));
                }
            }
        }
    }

    private static String getRelativePath(File root, File file) throws IOException {
        String rootCanonical = root.getCanonicalPath();
        String fileCanonical = file.getCanonicalPath();
        // Separator-aware containment (BUGS.md 11): a sibling root path must
        // fail loudly instead of bleeding its directory name into the result
        if (!fileCanonical.startsWith(rootCanonical + File.separator)) {
            throw new IOException("File " + fileCanonical + " is not under " + rootCanonical);
        }
        return fileCanonical.substring(rootCanonical.length() + 1)
                .replace(File.separatorChar, '/');
    }

    private static final class JavaFilePkmsEntry implements PkmsEntry {
        private final File root;
        private final File file;

        JavaFilePkmsEntry(File root, File file) {
            this.root = root;
            this.file = file;
        }

        @Override
        public String relativePath() {
            try {
                return getRelativePath(root, file);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String name() {
            return file.getName();
        }

        @Override
        public long lastModified() {
            return file.lastModified();
        }
    }
}