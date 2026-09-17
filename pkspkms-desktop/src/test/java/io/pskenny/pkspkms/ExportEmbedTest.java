package io.pskenny.pkspkms;

import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;

import java.io.File;
import io.pskenny.pkspkms.repo.sqlite.SQLiteLuaConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExportEmbedTest {
    Export export;
    private static final Path TEST_DIR = Paths.get("target", "test-notes", ExportEmbedTest.class.getSimpleName());

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
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete test directory", e);
            }
        }
    }

    @Test
    @DisplayName("Export replaces simple embedded LuaBase table with Markdown table")
    void testMarkdownExportReplacesLuaBaseEmbed() throws IOException {
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
                "filePath:*.md",
                TEST_DIR + "/output",
                "markdown",
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), SQLiteLuaConnector.luaFunctionRegistrar(), inputFs);
        repository.loadDirectoryIntoRepository();
        export = new Export(config, repository, inputFs, outputFs);
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
    void testMarkdownExportReplacesBaseEmbed() throws IOException {
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
                "filePath:*.md",
                TEST_DIR + "/output",
                "markdown",
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), SQLiteLuaConnector.luaFunctionRegistrar(), inputFs);
        repository.loadDirectoryIntoRepository();
        export = new Export(config, repository, inputFs, outputFs);
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
    void testMarkdownExportReplacesBaseEmbedAdvanced() throws IOException {
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
                "filePath:*.md",
                TEST_DIR + "/output",
                "markdown",
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), SQLiteLuaConnector.luaFunctionRegistrar(), inputFs);
        repository.loadDirectoryIntoRepository();
        export = new Export(config, repository, inputFs, outputFs);
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
    void testMarkdownExportReplacesBaseEmbed_WithTwoProperties() throws IOException {
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
                "filePath:*.md",
                TEST_DIR + "/output",
                "markdown",
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), SQLiteLuaConnector.luaFunctionRegistrar(), inputFs);
        repository.loadDirectoryIntoRepository();
        export = new Export(config, repository, inputFs, outputFs);
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
    void testMarkdownExportReplacesBaseEmbed_WithTagsProperties() throws IOException {
        createFile(TEST_DIR, "File1.md", Map.of("tags", "Tag1"), "");
        createFile(TEST_DIR, "File2.md", Map.of(), """
```base
views:
  - type: table
    name: Table
    filters:
      and:
      - file.tags.containsAny("Tag1")
    order:
      - file.name
      - tags
```
                """);
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                TEST_DIR + "/output",
                "markdown",
                false);
        PkmsFileSystem inputFs = new JavaFileSystem(TEST_DIR.toFile());
        PkmsFileSystem outputFs = new JavaFileSystem(new File(TEST_DIR + "/output"));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), SQLiteLuaConnector.luaFunctionRegistrar(), inputFs);
        repository.loadDirectoryIntoRepository();
        export = new Export(config, repository, inputFs, outputFs);
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
