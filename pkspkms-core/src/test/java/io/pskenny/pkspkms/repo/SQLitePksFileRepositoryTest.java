package io.pskenny.pkspkms.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.createFile;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.QueryCompiler;
import io.pskenny.pkspkms.repo.query.QueryParser;
import static org.junit.jupiter.api.Assertions.*;

public class SQLitePksFileRepositoryTest {

    private static final Path TEST_DIR = Paths.get("target", "test-notes", SQLitePksFileRepositoryTest.class.getSimpleName());
    private static final Path VIRTUAL_DIR = Paths.get("target", "test-notes", SQLitePksFileRepositoryTest.class.getSimpleName() + "-virtual");
    private static final String TEST_DB_PATH = "test_pks.db";
    private static final String DB_URL = "jdbc:sqlite:" + TEST_DB_PATH;

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(Path.of(TEST_DB_PATH));
        Files.createDirectories(VIRTUAL_DIR);
        try {
            Files.createDirectories(TEST_DIR);
        } catch (IOException ignored) {}
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(Path.of(TEST_DB_PATH));

        for (Path dir : new Path[]{TEST_DIR, VIRTUAL_DIR}) {
            if (Files.exists(dir)) {
                try (Stream<Path> pathStream = Files.walk(dir)) {
                    pathStream
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete test directory", e);
                }
            }
        }
    }


    private static CompiledQuery Q(String q) {
        return new QueryCompiler().compile(new QueryParser().parse(q));
    }

    @Test
    void testSqliteRepository() throws Exception {
        createFile(TEST_DIR, "test1.md", Map.of(
                        "key", "value",
                        "number", 5,
                        "list", List.of("listItem"),
                        "boolean", true),
                "test"
        );
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT file_name, file_name_ext FROM FILES WHERE file_path = ?")) {
                // The loader stores vault-relative paths (P3: the old absolute
                // TEST_DIR.resolve probe never matched, so this asserted nothing)
                pstmt.setString(1, "test1.md");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next(), "indexed file must be found by its vault-relative path");
                assertEquals("test1", rs.getString("file_name"));
                assertEquals("test1.md", rs.getString("file_name_ext"));
            }
        }
    }

    @Test
    void testDatesRoundTripAsWritten() throws Exception {
        // Dates must not degrade to epoch millis through the properties JSON round trip (B43)
        createFile(TEST_DIR, "dated.md", Map.of("date", "2025-12-06"), "content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("filePath:dated.md"));

            assertEquals(1, results.size());
            assertEquals("2025-12-06", results.get(0).getMutableProperties().get("date"));
        }
    }

    @Test
    void testLuaBase() throws Exception {
        createFile(TEST_DIR, "test1.md", Map.of("key", "value"), "link [test2.md](test2.md)");
        createFile(TEST_DIR, "test2.md", Map.of("tags", List.of("tag1")),
                "http://googleusercontent.com/immersive_entry_chip/0");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("filePath:test2.md"));

            assertEquals(1, results.size());
            assertNotNull(results.get(0).getAsList("luabases"));
        }
    }

    @Disabled("Embed target parsing from EMBEDS table needs verification")
    @Test
    void testEmbedParsing() throws Exception {
        createFile(TEST_DIR, "test_embed.md", Map.of("embeds", List.of("![[TargetFile#Section Header]]")), "Content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT target_file, target_header FROM EMBEDS")) {
                ResultSet rs = pstmt.executeQuery();

                assertTrue(rs.next());
                assertNotNull(rs.getString("target_file"));
                assertEquals("TargetFile", rs.getString("target_file"));
                assertEquals("Section Header", rs.getString("target_header"));
            }
        }
    }

    @Test
    void testSqliteRepositoryCircularBaseLinks() throws Exception {
        createFile(TEST_DIR, "File1.md", Map.of("key", "value"), "[links](File2.md)");
        createFile(TEST_DIR, "File2.md", Map.of("tags", List.of("tag1")), "[link1](File1.md)");
        createFile(TEST_DIR, "File3.md", Map.of("tags", List.of("tag3")), "[[File2]]");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("filePath:File1.md"));

            assertEquals(1, results.size());
        }
    }

    @Test
    void testLinkMarkdownParse() throws Exception {
        createFile(TEST_DIR, "File1.md", Map.of("key", "value"), "[links](File2.md)");
        createFile(TEST_DIR, "File2.md", Map.of(), "[links](File2.md)");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("filePath:File1.md"));

            assertEquals(1, results.size());
            ArrayList<String> links = (ArrayList<String>) results.get(0).getMutableProperties().get("links");
            assertEquals("File2.md", links.get(0));
        }
    }

    @Test
    void testWikilinkMarkdownParse() throws Exception {
        createFile(TEST_DIR, "File1.md", Map.of("key", "value"), "[[File2]]");
        createFile(TEST_DIR, "File2.md", Map.of(), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("filePath:File1.md"));

            assertEquals(1, results.size());
            var links = results.get(0).getAsList("links");
            assertEquals("File2.md", links.get(0));
        }
    }

    @Test
    void testDuplicateLinkDeduplication() throws Exception {
        // File1 has both a markdown link and a wikilink to File2
        createFile(TEST_DIR, "File1.md", Map.of("key", "value"), "[File2](File2.md)\n[[File2]]");
        createFile(TEST_DIR, "File2.md", Map.of(), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            // Should not throw UNIQUE constraint violation
            repository.loadDirectoryIntoRepository();

            var results = repository.searchRegular(Q("filePath:File1.md"));

            assertEquals(1, results.size());
            var links = results.get(0).getAsList("links");
            assertEquals(1, links.size(), "Duplicate links to same target should be deduplicated");
            assertEquals("File2.md", links.get(0));
        }
    }

    @Test
    public void testInclusionFilteringMany() throws IOException, SQLException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("tags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of("tags", List.of("tags2")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("tags:tags1"));

            assertEquals(2, results.size());
        }
    }

    @Test
    public void testExclusionManyFilteringMany() throws IOException, SQLException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("tags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of("tags", List.of("tags2")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("NOT tags:tags1"));

            assertEquals(1, results.size());
        }
    }

    @Test
    public void testInclusionAndExclusionFiltering() throws IOException, SQLException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("tags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of("tags", List.of("tags2")), "");
        createFile(TEST_DIR, "test4.md", Map.of("tags", List.of("tags3")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("tags:tags1 AND NOT tags:tags2"));

            assertEquals(1, results.size());
        }
    }

    @Test
    public void testInclusionAndExclusionFilteringMany()  throws IOException, SQLException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("tags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of("tags", List.of("tags2")), "");
        createFile(TEST_DIR, "test4.md", Map.of("tags", List.of("tags3")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("tags:tags1 AND tags:tags2 AND NOT tags:tags3"));

            assertEquals(1, results.size());
        }
    }

    @Test
    public void testHasOneProperty() throws SQLException, IOException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("notags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of(), "");
        createFile(TEST_DIR, "test4.md", Map.of("tags", List.of("")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("tags:*"));

            assertEquals(2, results.size());
        }
    }

    @Test
    public void testHasManyProperties() throws SQLException, IOException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("notags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of(), "");
        createFile(TEST_DIR, "test4.md", Map.of("tags", List.of("")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("tags:* OR notags:*"));

            assertEquals(3, results.size());
        }
    }

    @Test
    void searchRegular_nullPropertiesRow_returnsFileWithEmptyProperties() throws Exception {
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            // Mimics cacheFile() upserts: FILES row without the properties column.
            // Only close the Statement — the Connection is the repository's shared one.
            try (Statement stmt = repository.getConnection().createStatement()) {
                stmt.execute("INSERT INTO FILES (file_path, file_name, file_name_ext) VALUES ('cached.png', 'cached', 'cached.png')");
            }

            List<PksFile> files = repository.searchRegular(Q(""));

            assertEquals(1, files.size());
            assertEquals("cached.png", files.get(0).getFilePath());
        }
    }

    @Test
    void searchRegular_jsonNullPropertiesRow_returnsFileWithEmptyProperties() throws Exception {
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            // JSON literal 'null' passes engine validation but parses to a null Map — must degrade, not NPE
            try (Statement stmt = repository.getConnection().createStatement()) {
                stmt.execute("INSERT INTO FILES (file_path, file_name, file_name_ext, properties) VALUES ('null.md', 'null', 'null.md', 'null')");
            }

            List<PksFile> files = repository.searchRegular(Q(""));

            assertEquals(1, files.size());
            assertEquals("null.md", files.get(0).getFilePath());
        }
    }

    @Test
    void load_failingSerializer_throwsRepositoryException() throws Exception {
        createFile(TEST_DIR, "note.md", Map.of("key", "value"), "content");

        // Mapper that always fails on write, to drive the serialization step
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("boom") {};
            }
        };

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile())) {
            @Override
            public ObjectMapper getJsonMapper() {
                return failingMapper;
            }
        }) {
            assertThrows(RepositoryException.class, repository::loadDirectoryIntoRepository);

            // Rollback pin: fabricated "{}" must not be persisted (loader stores vault-relative paths)
            try (PreparedStatement pstmt = repository.getConnection().prepareStatement("SELECT properties FROM FILES WHERE file_path = ?")) {
                pstmt.setString(1, "note.md");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next());
                assertNull(rs.getString("properties"));
            }
        }
    }

    @Test
    void load_resolvesAliasWikilink_targetInLinks() throws Exception {
        createFile(TEST_DIR, "File1.md", Map.of(), "text");
        createFile(TEST_DIR, "File2.md", Map.of(), "[[File1|Some Name]]");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            var results = repository.searchRegular(Q("filePath:File2.md"));

            assertEquals(1, results.size());
            assertTrue(results.get(0).getAsList("links").contains("File1.md"));
        }
    }

    @Test
    void resolveWikilink_stripsAliasText() throws Exception {
        createFile(TEST_DIR, "File1.md", Map.of(), "text");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            assertEquals("File1.md", repository.resolveWikilink("File1|Some Name"));
            assertEquals("File1.md", repository.resolveWikilink("File1#Section|Name"));
        }
    }

    @Test
    void searchRegular_backlinksDerivedFromLinks() throws Exception {
        // File1 -> File2 via wikilink AND markdown link (dedup check, B29)
        // File2 -> File3 via markdown link; File4 has no inbound links
        createFile(TEST_DIR, "File1.md", Map.of(), "[File2](File2.md)\n[[File2]]");
        createFile(TEST_DIR, "File2.md", Map.of(), "[File3](File3.md)");
        createFile(TEST_DIR, "File3.md", Map.of(), "");
        createFile(TEST_DIR, "File4.md", Map.of(), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            var f2 = repository.searchRegular(Q("filePath:File2.md"));
            assertEquals(List.of("File1.md"), f2.get(0).getAsList("backlinks"), "deduped across link types");

            var f3 = repository.searchRegular(Q("filePath:File3.md"));
            assertEquals(List.of("File2.md"), f3.get(0).getAsList("backlinks"), "markdown links count");

            assertTrue(repository.searchRegular(Q("filePath:File1.md")).get(0).getAsList("backlinks").isEmpty(),
                    "no backlinks -> key absent");
        }
    }

    @Test
    void sqliteFilesAreOwnerOnly() throws Exception {
        // Vault index metadata is sensitive: other local users must not read it
        // (the umask default left it group/world-readable) (B3 quick win)
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        createFile(TEST_DIR, "keep.md", Map.of(), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            java.util.Set<java.nio.file.attribute.PosixFilePermission> ownerOnly = java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            assertEquals(ownerOnly, Files.getPosixFilePermissions(Path.of(TEST_DB_PATH)));
            for (String suffix : List.of("-wal", "-shm")) {
                Path side = Path.of(TEST_DB_PATH + suffix);
                if (Files.exists(side)) {
                    assertEquals(ownerOnly, Files.getPosixFilePermissions(side), suffix + " sidecar");
                }
            }
        }
    }

    @Test
    void load_honorsObsidianUserIgnoreFilters() throws Exception {
        // .obsidian/app.json userIgnoreFilters replace the hardcoded exclusions
        Path obsidianDir = TEST_DIR.resolve(".obsidian");
        Files.createDirectories(obsidianDir);
        Files.writeString(obsidianDir.resolve("app.json"),
                "{\"userIgnoreFilters\": [\"Archive/\", \"*.jpg\", \"Secrets/private.md\"]}");

        Files.createDirectories(TEST_DIR.resolve("Archive"));
        createFile(TEST_DIR, "Archive/a.md", Map.of(), "");
        Files.createDirectories(TEST_DIR.resolve("Archives"));
        createFile(TEST_DIR, "Archives/keep.md", Map.of(), "");
        Files.createDirectories(TEST_DIR.resolve("Images"));
        Files.write(TEST_DIR.resolve("Images/p.jpg"), new byte[]{1});
        Files.createDirectories(TEST_DIR.resolve("Secrets"));
        createFile(TEST_DIR, "Secrets/private.md", Map.of(), "");
        createFile(TEST_DIR, "Secrets/other.md", Map.of(), "");
        createFile(TEST_DIR, "Keep.md", Map.of(), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            try (Statement stmt = repository.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT file_path FROM FILES ORDER BY file_path")) {
                List<String> paths = new ArrayList<>();
                while (rs.next()) {
                    paths.add(rs.getString("file_path"));
                }
                assertEquals(List.of("Archives/keep.md", "Keep.md", "Secrets/other.md"), paths);
            }
        }
    }

    @Test
    void load_withoutAppJson_indexesAllExceptSystemDirs() throws Exception {
        // Pure replacement: no app.json -> nothing extra ignored beyond .obsidian/.trash
        Files.createDirectories(TEST_DIR.resolve("Archive"));
        createFile(TEST_DIR, "Archive/old.md", Map.of(), "");
        createFile(TEST_DIR, "keep.md", Map.of(), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            try (Statement stmt = repository.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT file_path FROM FILES ORDER BY file_path")) {
                List<String> paths = new ArrayList<>();
                while (rs.next()) {
                    paths.add(rs.getString("file_path"));
                }
                assertEquals(List.of("Archive/old.md", "keep.md"), paths);
            }
        }
    }

    @Test
    void load_excludesPkspkmsCacheDirectory() throws Exception {
        // Cached plugin copies must not be indexed as vault notes (B55-adjacent
        // Obsidian plugin prerequisite). Covers both the dot-named default and
        // the plain variant the plugin uses so Obsidian can open the files.
        Files.createDirectories(TEST_DIR.resolve(".pkspkms-cache").resolve("@gwern"));
        Files.writeString(TEST_DIR.resolve(".pkspkms-cache").resolve("@gwern").resolve("java.md"),
                "---\ntags:\n  - Java\n---\ncached copy");
        Files.createDirectories(TEST_DIR.resolve("pkspkms-cache").resolve("@scy"));
        Files.writeString(TEST_DIR.resolve("pkspkms-cache").resolve("@scy").resolve("rust.md"),
                "---\ntags:\n  - Rust\n---\ncached copy");
        createFile(TEST_DIR, "keep.md", Map.of(), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            try (Statement stmt = repository.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT file_path FROM FILES ORDER BY file_path")) {
                List<String> paths = new ArrayList<>();
                while (rs.next()) {
                    paths.add(rs.getString("file_path"));
                }
                assertEquals(List.of("keep.md"), paths);
            }
        }
    }

    @Test
    void loadVirtualVault_defersEmbedRenderingToProcessEmbeds() throws Exception {
        // Embed rendering is global over all vaults, so it must run once after
        // every vault mounts — a mount only seeds EMBEDS rows
        createFile(TEST_DIR, "keep.md", Map.of(), "");
        createFile(VIRTUAL_DIR, "note.md", Map.of(), """
                ```luabase
                views:
                  - type: list
                ```
                """);

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            try (Statement stmt = repository.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT generated_content FROM EMBEDS WHERE type = 'luabase'")) {
                assertTrue(rs.next(), "virtual vault base seeded");
                assertNull(rs.getString("generated_content"), "a mount must not render embeds");
            }

            repository.processEmbeds();

            try (Statement stmt = repository.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT generated_content FROM EMBEDS WHERE type = 'luabase'")) {
                assertTrue(rs.next());
                assertNotNull(rs.getString("generated_content"), "explicit processEmbeds renders");
            }
        }
    }
}