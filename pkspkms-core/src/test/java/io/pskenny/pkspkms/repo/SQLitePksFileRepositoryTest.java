package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.repo.sqlite.SqlQueryParser;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.createFile;
import static org.junit.jupiter.api.Assertions.*;

public class SQLitePksFileRepositoryTest {

    private static final Path TEST_DIR = Paths.get("target", "test-notes", SQLitePksFileRepositoryTest.class.getSimpleName());
    private static final String TEST_DB_PATH = "test_pks.db";
    private static final String DB_URL = "jdbc:sqlite:" + TEST_DB_PATH;

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(Path.of(TEST_DB_PATH));
        try {
            Files.createDirectories(TEST_DIR);
        } catch (IOException ignored) {}
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(Path.of(TEST_DB_PATH));

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
    void testSqliteRepository() throws Exception {
        createFile(TEST_DIR, "test1.md", Map.of(
                        "key", "value",
                        "number", 5,
                        "list", List.of("listItem"),
                        "boolean", true),
                "test"
        );
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT file_name, file_name_ext FROM FILES WHERE file_path = ?")) {
                pstmt.setString(1, TEST_DIR.resolve("test1.md").toString());
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    assertEquals("test1", rs.getString("file_name"));
                    assertEquals("test1.md", rs.getString("file_name_ext"));
                }
            }
        }
    }

    @Test
    void testLuaBase() throws Exception {
        createFile(TEST_DIR, "test1.md", Map.of("key", "value"), "link [test2.md](test2.md)");
        createFile(TEST_DIR, "test2.md", Map.of("tags", List.of("tag1")),
                "http://googleusercontent.com/immersive_entry_chip/0");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("filePath = 'test2.md'");
            var results = repository.searchRegular(sqliteQuery);

            assertEquals(1, results.size());
            assertNotNull(results.get(0).getAsList("luabases"));
        }
    }

    @Disabled("Embed target parsing from EMBEDS table needs verification")
    @Test
    void testEmbedParsing() throws Exception {
        createFile(TEST_DIR, "test_embed.md", Map.of("embeds", List.of("![[TargetFile#Section Header]]")), "Content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());

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

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("filePath = 'File1.md'");
            var results = repository.searchRegular(sqliteQuery);

            assertEquals(1, results.size());
        }
    }

    @Test
    void testLinkMarkdownParse() throws Exception {
        createFile(TEST_DIR, "File1.md", Map.of("key", "value"), "[links](File2.md)");
        createFile(TEST_DIR, "File2.md", Map.of(), "[links](File2.md)");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("filePath = 'File1.md'");
            var results = repository.searchRegular(sqliteQuery);

            assertEquals(1, results.size());
            ArrayList<String> links = (ArrayList<String>) results.get(0).getProperties().get("links");
            assertEquals("File2.md", links.get(0));
        }
    }

    @Test
    void testWikilinkMarkdownParse() throws Exception {
        createFile(TEST_DIR, "File1.md", Map.of("key", "value"), "[[File2]]");
        createFile(TEST_DIR, "File2.md", Map.of(), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("filePath = 'File1.md'");
            var results = repository.searchRegular(sqliteQuery);

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

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            // Should not throw UNIQUE constraint violation
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());

            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("filePath = 'File1.md'");
            var results = repository.searchRegular(sqliteQuery);

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

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("tags = 'tags1'");
            var results = repository.searchRegular(sqliteQuery);

            assertEquals(2, results.size());
        }
    }

    @Test
    public void testExclusionManyFilteringMany() throws IOException, SQLException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("tags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of("tags", List.of("tags2")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("NOT tags = 'tags1'");
            var results = repository.searchRegular(sqliteQuery);

            assertEquals(1, results.size());
        }
    }

    @Test
    public void testInclusionAndExclusionFiltering() throws IOException, SQLException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("tags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of("tags", List.of("tags2")), "");
        createFile(TEST_DIR, "test4.md", Map.of("tags", List.of("tags3")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("tags = 'tags1' AND NOT tags = 'tags2'");
            var results = repository.searchRegular(sqliteQuery);

            assertEquals(1, results.size());
        }
    }

    @Test
    public void testInclusionAndExclusionFilteringMany()  throws IOException, SQLException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("tags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of("tags", List.of("tags2")), "");
        createFile(TEST_DIR, "test4.md", Map.of("tags", List.of("tags3")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("tags = 'tags1' AND tags = 'tags2' AND NOT tags = 'tags3'");
            var results = repository.searchRegular(sqliteQuery);

            assertEquals(1, results.size());
        }
    }

    @Test
    public void testHasOneProperty() throws SQLException, IOException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("notags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of(), "");
        createFile(TEST_DIR, "test4.md", Map.of("tags", List.of("")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("tags");
            var results = repository.searchRegular(sqliteQuery);

            assertEquals(2, results.size());
        }
    }

    @Test
    public void testHasManyProperties() throws SQLException, IOException {
        createFile(TEST_DIR, "test1.md", Map.of("tags", List.of("tags1", "tags2")), "");
        createFile(TEST_DIR, "test2.md", Map.of("notags", List.of("tags1")),"");
        createFile(TEST_DIR, "test3.md", Map.of(), "");
        createFile(TEST_DIR, "test4.md", Map.of("tags", List.of("")), "");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL)) {
            repository.loadDirectoryIntoRepository(TEST_DIR.toString());
            SqlQueryParser parser = new SqlQueryParser();
            var sqliteQuery = parser.parseToFullJoinQuery("tags OR notags");
            var results = repository.searchRegular(sqliteQuery);

            assertEquals(3, results.size());
        }
    }
}