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

package io.pskenny.pkspkms;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.fs.PkmsEntry;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExportTest {
    Export export;
    private static final Path TEST_DIR = Paths.get("target", "test-notes", ExportTest.class.getSimpleName());

    @BeforeEach
    void setup() throws IOException {
        if (!Files.exists(TEST_DIR)) {
            Files.createDirectories(TEST_DIR);
        }
    }

    @AfterEach
    void tearDown() {
        if (Files.exists(TEST_DIR)) {
            try (Stream<Path> pathStream = Files.walk(TEST_DIR)) {
                pathStream
                        .sorted(Comparator.reverseOrder()) // Must delete children before parents
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete test directory", e);
            }
        }
    }

    @Test
    @DisplayName("Don't export when dryRun is true")
    void testExportDryRunDoesntWriteFiles() throws IOException {
        var text = """
---
tags:
- Tag1
---
[test2](test2-graph.md)
[test1 doesn't exist](test1-no-existy.md)""".trim();
        createFile(TEST_DIR, "test.md", Map.of(),
                text
        );
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                true);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        assertEquals(false, fileExists(TEST_DIR + "/output/test.md"));
    }

    @Test
    @DisplayName("Export a single Markdown file")
    void testSingleMarkdownExport() throws IOException {
        var text = """
---
tags:
- Tag1
---
[test2](test2-graph.md)
[test1 doesn't exist](test1-no-existy.md)""".trim();
        createFile(TEST_DIR, "test.md", Map.of(),
                text
        );
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        assertEquals(text, readFile(TEST_DIR + "/output/test.md"));
    }

    @Test
    @DisplayName("Exports valid Wikilinks as Markdown hyperlinks")
    void testMarkdownWikilinkExport() throws IOException {
        createFile(TEST_DIR, "File1.md", Map.of(), "text");
        createFile(TEST_DIR, "File2.md", Map.of(),"[[File1]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        var expected = "[File1](File1.md)";
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Export copies Markdown hyperlinked file to output directory")
    void testMarkdownExportCopiesLinkedFile() throws IOException {
        createFile(TEST_DIR, "File1.txt", Map.of(), "text");
        createFile(TEST_DIR, "File2.md", Map.of(),"[[File1]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        var expected = "[File1](File1.txt)";
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);

        var expected2 = "text";
        var actual2 = readFile(TEST_DIR + "/output/File1.txt");
        assertEquals(expected2, actual2);
    }

    @Test
    @DisplayName("Export copies matched non-markdown files byte-exactly (no UTF-8 mangling)")
    void testMarkdownExportCopiesMatchedBinaryFileByteExactly() throws IOException {
        // Bytes that are invalid UTF-8 — a String round-trip would corrupt
        // them into U+FFFD replacement sequences.
        byte[] original = new byte[256];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (0x80 + (i % 0x7F));
        }
        Files.write(TEST_DIR.resolve("Media.jpg"), original);
        createFile(TEST_DIR, "Note.md", Map.of(), "![[Media.jpg]]");

        // Empty query mirrors production: matches every file, including media
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        byte[] exported = Files.readAllBytes(TEST_DIR.resolve("output/Media.jpg"));
        assertArrayEquals(original, exported);

        String exportedNote = readFile(TEST_DIR + "/output/Note.md");
        assertTrue(exportedNote.contains("![](Media.jpg)"),
                "media embed should become a link, was: " + exportedNote);
    }

    @Test
    @DisplayName("Export replaces Markdown Wikilink embeds with content")
    void testMarkdownExportReplacesWikilinkEmbed() throws IOException {
        createFile(TEST_DIR, "File1.md", Map.of(), "text");
        createFile(TEST_DIR, "File2.md", Map.of(),"![[File1]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        var expected = "text";
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Export replaces Markdown Wikilink embed with heading with content")
    void testMarkdownExportReplacesWikilinkEmbedWithHeading() throws IOException {
        createFile(TEST_DIR, "File5.md", Map.of(), """
## Title1

text

## Title2

Other""");
        createFile(TEST_DIR, "File6.md", Map.of(),"![[File5#Title2]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        // flex round-trip: paragraphs carry the trailing newline (md-correct)
        var expected = "Other\n";
        var actual = readFile(TEST_DIR + "/output/File6.md");
        assertEquals(expected, actual);
    }

    // Records close() so the test can assert the stream was released
    static final class CloseTrackingStream extends ByteArrayInputStream {
        boolean closed = false;

        CloseTrackingStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    @DisplayName("Export closes the input stream after reading file content")
    void exportClosesInputStreamAfterRead() {
        CloseTrackingStream stream = new CloseTrackingStream("text".getBytes(StandardCharsets.UTF_8));

        PkmsFileSystem inputFs = new PkmsFileSystem() {
            @Override
            public InputStream openInput(String relativePath) {
                return stream;
            }

            @Override
            public boolean exists(String relativePath) {
                return true;
            }

            @Override
            public List<PkmsEntry> listFiles(List<String> excludedDirectories) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PkmsEntry resolve(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OutputStream openOutput(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void writeString(String relativePath, String content) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void copyFrom(InputStream source, String destRelativePath) {
                throw new UnsupportedOperationException();
            }
        };

        PksFileRepository repository = new io.pskenny.pkspkms.test.UnsupportedPksRepository() {
            @Override
            public List<PksFile> searchRegular(io.pskenny.pkspkms.repo.query.CompiledQuery query) {
                return List.of(new PksFile("note.md", new HashMap<>()));
            }
        };

        Export.ExportConfig config = new Export.ExportConfig(
                "vault", "unused.db", "", "out", Export.ExportMode.MARKDOWN, true);

        new Export(config, repository, inputFs, inputFs).export();

        assertTrue(stream.closed, "input stream must be closed after reading content");
    }

    @Test
    @DisplayName("Export resolves alias wikilinks using the alias as display text")
    void testMarkdownWikilinkAliasExport() throws IOException {
        createFile(TEST_DIR, "File1.md", Map.of(), "text");
        createFile(TEST_DIR, "File2.md", Map.of(), "[[File1|My Display]]");
        createFile(TEST_DIR, "File3.md", Map.of(), "[[Missing|X]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        assertEquals("[My Display](File1.md)", readFile(TEST_DIR + "/output/File2.md"));
        assertEquals("[[Missing|X]]", readFile(TEST_DIR + "/output/File3.md")); // unresolved stays raw
    }

    @Test
    @DisplayName("v7: frontmatter wikilinks stay literal in export; body links transform")
    void testMarkdownExportLeavesFrontmatterLiteral() throws IOException {
        createFile(TEST_DIR, "House.md", Map.of(), "house content");
        // unquoted frontmatter value + a body link to the same target — only
        // the BODY occurrence may transform
        createFile(TEST_DIR, "fmw.md", Map.of("parent", "[[House]]"), "body [[House]] here");

        Export.ExportConfig config = new Export.ExportConfig(TEST_DIR.toString(), "pkspkms.db",
                "filePath:fmw.md", TEST_DIR + "/output", Export.ExportMode.MARKDOWN, false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        String exported = readFile(TEST_DIR + "/output/fmw.md");
        assertTrue(exported.contains("parent: [[House]]"),
                "frontmatter region is verbatim: " + exported);
        assertTrue(exported.contains("body [House](House.md) here"),
                "body links still transform: " + exported);
    }

    @Test
    @DisplayName("Export inlines embed targets containing apostrophes")
    void testMarkdownExportEmbedWithApostrophe() throws IOException {
        createFile(TEST_DIR, "Maus_ A Survivor's Tale-Hdbk - Art Spiegelman.cbz", Map.of(), "cbz-content");
        createFile(TEST_DIR, "note.md", Map.of(), "![[Maus_ A Survivor's Tale-Hdbk - Art Spiegelman.cbz]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        // Non-markdown targets are never inlined: copy + keep the !, destination is the raw root-relative path
        assertEquals("![](Maus_ A Survivor's Tale-Hdbk - Art Spiegelman.cbz)",
                readFile(TEST_DIR + "/output/note.md"));
        assertTrue(fileExists(TEST_DIR + "/output/Maus_ A Survivor's Tale-Hdbk - Art Spiegelman.cbz"));
    }

    @Test
    @DisplayName("Export inlines embed targets containing parentheses")
    void testMarkdownExportEmbedWithParentheses() throws IOException {
        createFile(TEST_DIR, "Meeting (2024).md", Map.of(), "meeting-content");
        createFile(TEST_DIR, "note.md", Map.of(), "![[Meeting (2024).md]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // try-with: clean close deletes the -wal/-shm sidecars (leftover pact)
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }

        assertEquals("meeting-content", readFile(TEST_DIR + "/output/note.md"));
    }

    @Test
    @DisplayName("Export continues past files that fail to process")
    void markdownExportContinuesAfterPerFileFailure() {
        Set<String> written = new HashSet<>();

        PkmsFileSystem inputFs = new PkmsFileSystem() {
            @Override
            public InputStream openInput(String relativePath) {
                if (relativePath.equals("b.md")) {
                    throw new IllegalStateException("boom");
                }
                return new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public boolean exists(String relativePath) {
                return false;
            }

            @Override
            public List<PkmsEntry> listFiles(List<String> excludedDirectories) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PkmsEntry resolve(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OutputStream openOutput(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void writeString(String relativePath, String content) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void copyFrom(InputStream source, String destRelativePath) {
                throw new UnsupportedOperationException();
            }
        };

        PkmsFileSystem outputFs = new PkmsFileSystem() {
            @Override
            public void writeString(String relativePath, String content) {
                written.add(relativePath);
            }

            @Override
            public boolean exists(String relativePath) {
                return false;
            }

            @Override
            public List<PkmsEntry> listFiles(List<String> excludedDirectories) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PkmsEntry resolve(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InputStream openInput(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OutputStream openOutput(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void copyFrom(InputStream source, String destRelativePath) {
                throw new UnsupportedOperationException();
            }
        };

        PksFileRepository repository = new io.pskenny.pkspkms.test.UnsupportedPksRepository() {
            @Override
            public List<PksFile> searchRegular(io.pskenny.pkspkms.repo.query.CompiledQuery query) {
                return List.of(
                        new PksFile("a.md", new HashMap<>()),
                        new PksFile("b.md", new HashMap<>()),
                        new PksFile("c.md", new HashMap<>()));
            }
        };

        Export.ExportConfig config = new Export.ExportConfig(
                "vault", "unused.db", "", "out", Export.ExportMode.MARKDOWN, false);

        new Export(config, repository, inputFs, outputFs).export();

        assertTrue(written.contains("a.md"));
        assertTrue(written.contains("c.md"), "Export must continue past a failing file");
        assertFalse(written.contains("b.md"));
    }

    private Export.ExportConfig markdownConfig(boolean dryRun) {
        return new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                dryRun);
    }

    private void exportWithConfig(Export.ExportConfig config) {
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        // Close the repository: the connection must release cleanly or the
        // -wal/-shm sidecars linger past the JVM (clean-close contract).
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
            repository.loadDirectoryIntoRepository();
            export = new Export(config, repository, inputFs, outputFs);
            export.export();
        }
    }

    @Test
    @DisplayName("Export turns media embeds into links and copies the file")
    void testMarkdownExportMediaEmbedBecomesLink() throws IOException {
        createFile(TEST_DIR, "video.mp4", Map.of(), "fake-video");
        createFile(TEST_DIR, "note.md", Map.of(), "![[video.mp4]]");
        exportWithConfig(markdownConfig(false));

        assertEquals("![](video.mp4)", readFile(TEST_DIR + "/output/note.md"));
        assertTrue(fileExists(TEST_DIR + "/output/video.mp4"));
    }

    @Test
    @DisplayName("Media embeds are case-insensitive and never inline bytes")
    void testMarkdownExportMediaEmbedUppercase() throws IOException {
        byte[] bytes = new byte[2048];
        new java.util.Random(7).nextBytes(bytes);
        Files.write(TEST_DIR.resolve("clip.MP4"), bytes);
        createFile(TEST_DIR, "note.md", Map.of(), "![[clip.MP4]]");
        exportWithConfig(markdownConfig(false));

        String out = readFile(TEST_DIR + "/output/note.md");
        assertEquals("![](clip.MP4)", out);
        assertTrue(fileExists(TEST_DIR + "/output/clip.MP4"));
        assertFalse(out.contains("\0"), "binary bytes must never be inlined");
    }

    @Test
    @DisplayName("Image embeds keep the ! and become links, not bytes")
    void testMarkdownExportImageEmbedBecomesLink() throws IOException {
        createFile(TEST_DIR, "chart.png", Map.of(), "fake-png");
        createFile(TEST_DIR, "note.md", Map.of(), "![[chart.png]]");
        exportWithConfig(markdownConfig(false));

        assertEquals("![](chart.png)", readFile(TEST_DIR + "/output/note.md"));
        assertTrue(fileExists(TEST_DIR + "/output/chart.png"));
    }

    @Test
    @DisplayName("Embeds inside frontmatter become bare YAML values")
    void testMarkdownExportFrontmatterEmbedBecomesBarePath() throws IOException {
        createFile(TEST_DIR, "banner.png", Map.of(), "fake-png");
        Files.writeString(TEST_DIR.resolve("note.md"),
                "---\nbanner: \"![[banner.png]]\"\ntitle: Test\n---\nBody text.\n");
        exportWithConfig(markdownConfig(false));

        String out = readFile(TEST_DIR + "/output/note.md");
        assertTrue(out.contains("banner: \"banner.png\""), "frontmatter embed must become a bare YAML value");
        assertFalse(out.contains("![["));
        assertFalse(out.contains("fake-png"));
    }

    @Test
    @DisplayName("Dry run does not copy media files")
    void testMarkdownExportDryRunDoesNotCopyMedia() throws IOException {
        createFile(TEST_DIR, "video.mp4", Map.of(), "fake-video");
        createFile(TEST_DIR, "note.md", Map.of(), "![[video.mp4]]");
        exportWithConfig(markdownConfig(true));

        assertFalse(fileExists(TEST_DIR + "/output/video.mp4"));
        assertFalse(fileExists(TEST_DIR + "/output/note.md"));
    }

    @Test
    @DisplayName("Oversized markdown embeds become links instead of inlining")
    void testMarkdownExportOversizedEmbedBecomesLink() throws IOException {
        createFile(TEST_DIR, "big.md", Map.of(), "x".repeat(1_100_000));
        createFile(TEST_DIR, "note.md", Map.of(), "![[big.md]]");
        exportWithConfig(markdownConfig(false));

        assertEquals("![](big.md)", readFile(TEST_DIR + "/output/note.md"));
    }

    @Test
    @DisplayName("Media destinations stay root-relative and unencoded")
    void testMarkdownExportMediaEmbedSpaceAtRoot() throws IOException {
        createFile(TEST_DIR, "My Video.mp4", Map.of(), "fake-video");
        createFile(TEST_DIR, "note.md", Map.of(), "![[My Video.mp4]]");
        exportWithConfig(markdownConfig(false));

        assertEquals("![](My Video.mp4)", readFile(TEST_DIR + "/output/note.md"));
        assertTrue(fileExists(TEST_DIR + "/output/My Video.mp4"));
    }

    @Test
    @DisplayName("Nested note media destinations stay root-relative and unencoded")
    void testMarkdownExportMediaEmbedSpaceInNestedNote() throws IOException {
        createFile(TEST_DIR, "My Video.mp4", Map.of(), "fake-video");
        Files.createDirectories(TEST_DIR.resolve("Notes").resolve("sub"));
        Files.writeString(TEST_DIR.resolve("Notes").resolve("sub").resolve("note.md"), "![[My Video.mp4]]");
        exportWithConfig(markdownConfig(false));

        assertEquals("![](My Video.mp4)", readFile(TEST_DIR + "/output/Notes/sub/note.md"));
        assertTrue(fileExists(TEST_DIR + "/output/My Video.mp4"));
    }

    @Test
    @DisplayName("Media destinations keep parentheses unencoded")
    void testMarkdownExportMediaEmbedParenthesesDestination() throws IOException {
        createFile(TEST_DIR, "Meeting (2024).mp4", Map.of(), "fake-video");
        createFile(TEST_DIR, "note.md", Map.of(), "![[Meeting (2024).mp4]]");
        exportWithConfig(markdownConfig(false));

        assertEquals("![](Meeting (2024).mp4)", readFile(TEST_DIR + "/output/note.md"));
        assertTrue(fileExists(TEST_DIR + "/output/Meeting (2024).mp4"));
    }

    @Test
    @DisplayName("Frontmatter embeds stay raw root-relative values")
    void testMarkdownExportFrontmatterEmbedWithSpace() throws IOException {
        createFile(TEST_DIR, "My Image.png", Map.of(), "fake-png");
        Files.writeString(TEST_DIR.resolve("note.md"),
                "---\nbanner: \"![[My Image.png]]\"\ntitle: Test\n---\nBody text.\n");
        exportWithConfig(markdownConfig(false));

        String out = readFile(TEST_DIR + "/output/note.md");
        assertTrue(out.contains("banner: \"My Image.png\""));
        assertFalse(out.contains("%20"));
        assertFalse(out.contains("fake-png"));
    }

    @Test
    @DisplayName("Plain wikilink destinations stay root-relative and unencoded")
    void testMarkdownExportPlainWikilinkRootRelative() throws IOException {
        createFile(TEST_DIR, "My Target.md", Map.of(), "target-content");
        Files.createDirectories(TEST_DIR.resolve("Notes").resolve("sub"));
        Files.writeString(TEST_DIR.resolve("Notes").resolve("sub").resolve("note.md"), "[[My Target]]");
        exportWithConfig(markdownConfig(false));

        assertEquals("[My Target](My Target.md)", readFile(TEST_DIR + "/output/Notes/sub/note.md"));
        assertTrue(fileExists(TEST_DIR + "/output/My Target.md"));
    }

    @Test
    @DisplayName("Plain links to non-markdown files are copied regardless of extension")
    void testMarkdownExportCopiesLinkedUnknownExtension() throws IOException {
        createFile(TEST_DIR, "Maus.cbz", Map.of(), "cbz-content");
        createFile(TEST_DIR, "note.md", Map.of(), "[Maus](Maus.cbz)");
        exportWithConfig(markdownConfig(false));

        assertTrue(fileExists(TEST_DIR + "/output/Maus.cbz"));
        assertEquals("[Maus](Maus.cbz)", readFile(TEST_DIR + "/output/note.md"));
    }

    @Test
    @DisplayName("URL-encoded destinations decode before copying (B33)")
    void testMarkdownExportCopiesPercentEncodedLink() throws IOException {
        createFile(TEST_DIR, "My Image.png", Map.of(), "fake-png");
        createFile(TEST_DIR, "note.md", Map.of(), "[img](My%20Image.png)");
        exportWithConfig(markdownConfig(false));

        assertTrue(fileExists(TEST_DIR + "/output/My Image.png"));
    }

    @Test
    @DisplayName("Linked markdown files are not duplicated by the copy pass")
    void testMarkdownExportDoesNotCopyLinkedMarkdown() throws IOException {
        createFile(TEST_DIR, "Target.md", Map.of(), "target");
        createFile(TEST_DIR, "note.md", Map.of(), "[T](Target.md)");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:note.md",
                TEST_DIR + "/output",
                Export.ExportMode.MARKDOWN,
                false);
        exportWithConfig(config);

        assertTrue(fileExists(TEST_DIR + "/output/note.md"));
        assertFalse(fileExists(TEST_DIR + "/output/Target.md"));
    }

    @Test
    @DisplayName("Literal percent filenames are not corrupted by decoding")
    void testMarkdownExportKeepsLiteralPercentFilename() throws IOException {
        createFile(TEST_DIR, "50%.txt", Map.of(), "fifty");
        createFile(TEST_DIR, "note.md", Map.of(), "[x](50%.txt)");
        exportWithConfig(markdownConfig(false));

        assertTrue(fileExists(TEST_DIR + "/output/50%.txt"));
    }

    // --- B9: a hard-failing embed exports as empty, never "ERROR" or raw YAML ---

    @Test
    @DisplayName("Failing base embed exports empty, no ERROR literal or YAML leak")
    void testMarkdownExportFailingBaseExportsEmpty() throws IOException {
        // YAML parse failure in the view → embed render → "ERROR" diagnostic;
        // the export must replace the block with an empty render
        createFile(TEST_DIR, "note.md", Map.of(), """
                ```base
                views:
                  - [unclosed
                ```
                """);
        exportWithConfig(markdownConfig(false));

        String out = readFile(TEST_DIR + "/output/note.md");
        assertFalse(out.contains("ERROR"), out);
        assertFalse(out.contains("unclosed"), "raw yaml must not leak: " + out);
        assertFalse(out.contains("```base"), out);
    }

    // --- BUGS.md 12 closed as by-design: virtual vaults are not export targets ---

    @Test
    @DisplayName("Virtual-vault subjects are skipped with intent, main vault still exports")
    void testExportSkipsVirtualVaultSubjectsByDesign() throws IOException {
        createFile(TEST_DIR, "main.md", Map.of("tags", "export-this"), "main body");

        Path virtualDir = Paths.get("target", "test-notes", "export-skip-virtual");
        Files.createDirectories(virtualDir.resolve("sub"));
        createFile(virtualDir, "sub/virtual.md", Map.of("tags", "export-this"), "virtual body");

        try {
            Export.ExportConfig config = new Export.ExportConfig(
                    TEST_DIR.toString(),
                    "pkspkms.db",
                    "tags:export-this",
                    TEST_DIR + "/output",
                    Export.ExportMode.MARKDOWN,
                    false);
            PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
            PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
            try (SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs)) {
                repository.loadDirectoryIntoRepository();
                // isolation: earlier runs leave the alias row in the shared
                // fixture db — clear it before this re-mount
                try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + config.dbPath());
                     var stmt = conn.createStatement()) {
                    stmt.executeUpdate("DELETE FROM VAULT_ALIASES WHERE alias='peer'");
                } catch (java.sql.SQLException cleanupFailure) {
                    // nothing to clean: fresh fixture db
                }
                repository.loadVirtualVault(new JavaFileSystem(virtualDir.toFile()), "peer");
                export = new Export(config, repository, inputFs, outputFs);
                export.export();
            }

            assertTrue(fileExists(TEST_DIR + "/output/main.md"), "main vault exports normally");
            assertFalse(fileExists(TEST_DIR + "/output/@peer/sub/virtual.md"),
                    "virtual vault subjects are not written");
            assertFalse(fileExists(TEST_DIR + "/output/peer"), "no alias output path by design");
        } finally {
            try (var walk = Files.walk(virtualDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            } catch (IOException ignored) {
            }
        }
    }

    // --- B14: fenced demo embeds must not expand ---

    @Test
    @DisplayName("Embed inside a code fence is left verbatim (B14/B15)")
    void testMarkdownExportFencedEmbedNotExpanded() throws IOException {
        String note = "Real target exists:\n\n```md\n![[Target.md]]\n```\n";
        createFile(TEST_DIR, "Target.md", Map.of(), "target-content");
        createFile(TEST_DIR, "note.md", Map.of(), note);
        exportWithConfig(markdownConfig(false));

        assertEquals(note, readFile(TEST_DIR + "/output/note.md"), "fenced demo untouched");
    }

    // --- B20: identical content skips the copy, changed content recopies ---

    @Test
    @DisplayName("Unchanged linked file keeps its output mtime")
    void testMarkdownExportUnchangedLinkedFileSkipsRecopy() throws IOException {
        createFile(TEST_DIR, "media.png", Map.of(), "pixels");
        createFile(TEST_DIR, "note.md", Map.of(), "[img](media.png)");
        exportWithConfig(markdownConfig(false));
        Path out = TEST_DIR.resolve("output/media.png");
        java.nio.file.attribute.FileTime pinned =
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000);
        Files.setLastModifiedTime(out, pinned);

        exportWithConfig(markdownConfig(false)); // identical second pass

        assertEquals(pinned.toMillis(), Files.getLastModifiedTime(out).toMillis(),
                "identical content must not be re-copied");
    }

    @Test
    @DisplayName("Changed linked file is re-copied even when output exists")
    void testMarkdownExportChangedLinkedFileRecopied() throws IOException {
        createFile(TEST_DIR, "media.png", Map.of(), "pixels");
        createFile(TEST_DIR, "note.md", Map.of(), "[img](media.png)");
        exportWithConfig(markdownConfig(false));
        Path out = TEST_DIR.resolve("output/media.png");
        java.nio.file.attribute.FileTime pinned =
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000);
        Files.setLastModifiedTime(out, pinned);

        Files.writeString(TEST_DIR.resolve("media.png"), "pixels-v2");
        exportWithConfig(markdownConfig(false));

        assertEquals("pixels-v2", readFile(TEST_DIR + "/output/media.png"),
                "changed source must overwrite the stale copy");
    }

    @Test
    @DisplayName("Content compare works even without a FILES row")
    void testMarkdownExportHashCompareWithoutFilesRow() throws Exception {
        createFile(TEST_DIR, "extra.png", Map.of(), "bytes");
        createFile(TEST_DIR, "note.md", Map.of(), "[x](extra.png)");
        exportWithConfig(markdownConfig(false));

        // Simulate a FILES row vanished (cacheFile-style): output stays intact
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:pkspkms.db");
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM FILES WHERE file_path = 'extra.png'");
        }

        java.nio.file.attribute.FileTime pinned =
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000);
        Files.setLastModifiedTime(TEST_DIR.resolve("output/extra.png"), pinned);
        exportWithConfig(markdownConfig(false));

        assertEquals(pinned.toMillis(),
                Files.getLastModifiedTime(TEST_DIR.resolve("output/extra.png")).toMillis(),
                "input-stream fallback hash must match the skipped copy");
    }

    // --- B8: embedded content keeps real backslashes, unescapes renderer escapes ---

    @Test
    @DisplayName("Embedded backslashes survive except markdown escapes (B8)")
    void testMarkdownExportEmbedBackslashesSurvive() throws IOException {
        createFile(TEST_DIR, "target.md", Map.of(), """
                ## Section

                Path `C:\\Users\\pk\\notes` and plain `back\\slash`.
                """);
        createFile(TEST_DIR, "note.md", Map.of(), "![[target.md#Section]]");
        exportWithConfig(markdownConfig(false));

        String out = readFile(TEST_DIR + "/output/note.md");
        assertTrue(out.contains("C:\\Users\\pk\\notes"), "Windows path intact, was: " + out);
        assertTrue(out.contains("back\\slash"), "non-escapable backslashes intact");
    }

    @Test
    @DisplayName("One unresolvable linked file doesn't stop the rest")
    void testMarkdownExportContinuesPastBadLink() {
        Set<String> copied = new HashSet<>();

        PkmsFileSystem inputFs = new PkmsFileSystem() {
            @Override
            public InputStream openInput(String relativePath) {
                if (relativePath.equals("bad.txt")) {
                    throw new IllegalStateException("boom");
                }
                return new ByteArrayInputStream("good".getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public boolean exists(String relativePath) {
                return false;
            }

            @Override
            public List<PkmsEntry> listFiles(List<String> excludedDirectories) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PkmsEntry resolve(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OutputStream openOutput(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void writeString(String relativePath, String content) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void copyFrom(InputStream source, String destRelativePath) {
                throw new UnsupportedOperationException();
            }
        };

        PkmsFileSystem outputFs = new PkmsFileSystem() {
            @Override
            public void copyFrom(InputStream source, String destRelativePath) {
                copied.add(destRelativePath);
            }

            @Override
            public boolean exists(String relativePath) {
                return false;
            }

            @Override
            public List<PkmsEntry> listFiles(List<String> excludedDirectories) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PkmsEntry resolve(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InputStream openInput(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OutputStream openOutput(String relativePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void writeString(String relativePath, String content) {
                // written, not asserted
            }
        };

        PksFileRepository repository = new io.pskenny.pkspkms.test.UnsupportedPksRepository() {
            @Override
            public List<PksFile> searchRegular(io.pskenny.pkspkms.repo.query.CompiledQuery query) {
                PksFile note = new PksFile("note.md", new HashMap<>());
                note.getMutableProperties().put("links", List.of("bad.txt", "good.txt"));
                return List.of(note);
            }
        };

        Export.ExportConfig config = new Export.ExportConfig(
                "vault", "unused.db", "", "out", Export.ExportMode.MARKDOWN, false);

        new Export(config, repository, inputFs, outputFs).export();

        assertTrue(copied.contains("good.txt"), "must continue past a failing linked file");
        assertFalse(copied.contains("bad.bin"));
    }
}
