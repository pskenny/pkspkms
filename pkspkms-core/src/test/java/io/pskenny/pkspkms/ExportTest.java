package io.pskenny.pkspkms;

import io.pskenny.pkspkms.repo.SQLitePksFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void testExportDryRunDoesntWriteFiles() throws IOException, SQLException {
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
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                true,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        assertEquals(false, fileExists(TEST_DIR + "/output/test.md"));
    }

    @Test
    @DisplayName("Export a single Markdown file")
    void testSingleMarkdownExport() throws IOException, SQLException {
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
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        assertEquals(text, readFile(TEST_DIR + "/output/test.md"));
    }

    @Test
    @DisplayName("Exports valid Wikilinks as Markdown hyperlinks")
    void testMarkdownWikilinkExport() throws IOException, SQLException {
        createFile(TEST_DIR, "File1.md", Map.of(), "text");
        createFile(TEST_DIR, "File2.md", Map.of(),"[[File1]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        var expected = "[File1](File1.md)";
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Export copies Markdown hyperlinked file to output directory")
    void testMarkdownExportCopiesLinkedFile() throws IOException, SQLException {
        createFile(TEST_DIR, "File1.txt", Map.of(), "text");
        createFile(TEST_DIR, "File2.md", Map.of(),"[[File1]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        var expected = "[File1](File1.txt)";
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);

        var expected2 = "text";
        var actual2 = readFile(TEST_DIR + "/output/File1.txt");
        assertEquals(expected2, actual2);
    }

    @Test
    @DisplayName("Export replaces Markdown Wikilink embeds with content")
    void testMarkdownExportReplacesWikilinkEmbed() throws IOException, SQLException {
        createFile(TEST_DIR, "File1.md", Map.of(), "text");
        createFile(TEST_DIR, "File2.md", Map.of(),"![[File1]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        var expected = "text";
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Export replaces Markdown Wikilink embed with heading with content")
    void testMarkdownExportReplacesWikilinkEmbedWithHeading() throws IOException, SQLException {
        createFile(TEST_DIR, "File5.md", Map.of(), """
## Title1

text

## Title2

Other""");
        createFile(TEST_DIR, "File6.md", Map.of(),"![[File5#Title2]]");
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        var expected = "Other";
        var actual = readFile(TEST_DIR + "/output/File6.md");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Export replaces simple embedded LuaBase table with Markdown table")
    void testMarkdownExportReplacesLuaBaseEmbed() throws IOException, SQLException {
        createFile(TEST_DIR, "File1.md", Map.of("tags", "Tag1"), "");
        createFile(TEST_DIR, "File2.md", Map.of(), """
```luabase
views:
- type: table
  name: "My table"
  filters:
    and:
      - hasPropertyValue(file, "filePath", "File1.md")
  order:
    - 'getPropertyValue(file, "filePath"), "Path"'
```
                """);
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        var expected = """
| Path |
|---|
| File1.md |

                """;
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Export replaces simple embedded Base table with Markdown table")
    void testMarkdownExportReplacesBaseEmbed() throws IOException, SQLException {
        createFile(TEST_DIR, "File1.md", Map.of("tags", "Tag1"), "");
        createFile(TEST_DIR, "File2.md", Map.of(), """
```base
views:
- type: table
  name: "My table"
  filters:
    and:
      - file.name == "File1.md"
```
                """);
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        var expected = """
| Path |
|---|
| File1.md |

                """;
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Export replaces simple embedded Base table with Markdown table")
    void testMarkdownExportReplacesBaseEmbedAdvanced() throws IOException, SQLException {
        createFile(TEST_DIR, "File1.md", Map.of("tags", "Tag1", "access", "Public"), "");
        createFile(TEST_DIR, "File2.md", Map.of(), """
```base
views:
  - type: table
    name: Table
    filters:
      and:
      - file.tags.containsAny("Tag1")
      - access == "Public"
```
                """);
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        var expected = """
| Path |
|---|
| File1.md |

                """;
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);
    }


    @Test
    @DisplayName("Export replaces simple embedded Base table with Markdown table displaying two properties")
    void testMarkdownExportReplacesBaseEmbed_WithTwoProperties() throws IOException, SQLException {
        createFile(TEST_DIR, "File1.md", Map.of("tags", "Tag1",
                "description", "Hello", "access", "Public"), "");
        createFile(TEST_DIR, "File2.md", Map.of(), """
```base
views:
  - type: table
    name: Table
    filters:
      and:
      - file.tags.containsAny("Tag1")
      - access == "Public"
    order:
      - file.name
      - description
```
                """);
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        var expected = """
| filePath | description |
|---|---|
| [File1.md](File1.md) | Hello |

                """;
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);
    }

//    @Test
    @DisplayName("Export replaces simple embedded Base table with Markdown table displaying tags properties")
    void testMarkdownExportReplacesBaseEmbed_WithTagsProperties() throws IOException, SQLException {
        createFile(TEST_DIR, "File1.md", Map.of("tags", "Tag1",
                "description", "Hello", "access", "Public"), "");
        createFile(TEST_DIR, "File2.md", Map.of(), """
```base
views:
  - type: table
    name: Table
    filters:
      and:
      - file.tags.containsAny("Tag1")
      - access == "Public"
    order:
      - file.name
      - tags
```
                """);
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath = *.md",
                TEST_DIR + "/output",
                "markdown",
                "wikilinks",
                false,
                true);
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath());
        repository.loadDirectoryIntoRepository(config.directory());
        export = new Export(config, repository);
        export.export();

        var expected = """
| filePath | description |
|---|---|
| [File1.md](File1.md) | Tag1 |

                """;
        var actual = readFile(TEST_DIR + "/output/File2.md");
        assertEquals(expected, actual);
    }
}
