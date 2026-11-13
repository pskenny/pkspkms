package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
import io.pskenny.pkspkms.repo.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.createFile;
import static org.junit.jupiter.api.Assertions.*;

// BUGS.md B1/B2: the repository used to drop and recreate every table on
// construction, so --db persistence was illusory. These tests pin the
// versioned schema lifecycle: data survives restarts, unchanged files skip
// re-parsing, legacy databases are adopted, unknown versions fail loudly.
public class PersistenceTest {

    private static final Path TEST_DIR = Paths.get("target", "test-notes", PersistenceTest.class.getSimpleName());
    private static final String TEST_DB_PATH = "test_persistence.db";
    private static final String DB_URL = "jdbc:sqlite:" + TEST_DB_PATH;

    @BeforeEach
    void setUp() throws IOException {
        deleteDb();
        Files.createDirectories(TEST_DIR);
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteDb();
        if (Files.exists(TEST_DIR)) {
            try (Stream<Path> walk = Files.walk(TEST_DIR)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    private void deleteDb() throws IOException {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(TEST_DB_PATH + suffix));
        }
    }

    @Test
    @DisplayName("Rows and aliases survive a close/reopen cycle")
    void tablesSurviveReconstruction() throws Exception {
        createFile(TEST_DIR, "one.md", Map.of("tags", "persistence"), "one");
        createFile(TEST_DIR, "two.md", Map.of(), "two");

        try (SQLitePksFileRepository first = repo()) {
            first.loadDirectoryIntoRepository();
        }

        try (SQLitePksFileRepository second = repo()) {
            assertEquals(2, count("FILES"), "rows must survive reconstruction");
            assertTrue(second.searchRegular(new io.pskenny.pkspkms.repo.query.QueryCompiler().compile(
                    new io.pskenny.pkspkms.repo.query.QueryParser().parse("tags:persistence"))).size() == 1,
                    "query hits the persisted row");
        }
    }

    @Test
    @DisplayName("Second load skips unchanged files, re-parses the edited one")
    void secondLoadSkipsUnchangedFiles() throws Exception {
        Path untouched = TEST_DIR.resolve("keep.md");
        Path edited = TEST_DIR.resolve("edit.md");
        createFile(TEST_DIR, "keep.md", Map.of(), "unchanged body");
        createFile(TEST_DIR, "edit.md", Map.of(), "original body");

        try (SQLitePksFileRepository repo = repo()) {
            repo.loadDirectoryIntoRepository();
            long keepMtime = mtimeOf("keep.md");

            Thread.sleep(5); // mtime resolution
            Files.writeString(edited, "---\nrevision: 2\n---\nnew body");
            long editMtime = Files.getLastModifiedTime(edited).toMillis();
            Files.setLastModifiedTime(untouched, java.nio.file.attribute.FileTime.fromMillis(keepMtime));

            repo.loadDirectoryIntoRepository();

            assertEquals(keepMtime, mtimeOf("keep.md"), "untouched file keeps its mtime row");
            assertEquals(editMtime, mtimeOf("edit.md"), "edited file was re-parsed");
            var queried = repo.searchRegular(new io.pskenny.pkspkms.repo.query.QueryCompiler().compile(
                    new io.pskenny.pkspkms.repo.query.QueryParser().parse("revision:2")));
            assertEquals(1, queried.size(), "edited file's new frontmatter indexed");
        }
    }

    @Test
    @DisplayName("Legacy DB without META is adopted, not wiped")
    void legacyDbWithoutMetaIsAdopted() throws Exception {
        createFile(TEST_DIR, "legacy.md", Map.of(), "legacy");
        try (SQLitePksFileRepository repo = repo()) {
            repo.loadDirectoryIntoRepository();
        }
        // Assume the shape of a pre-versioning index: remove the META row
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS META;");
        }

        try (SQLitePksFileRepository repo = repo()) {
            assertEquals(1, count("FILES"), "legacy rows adopted");
            assertEquals(String.valueOf(SQLiteSchema.SCHEMA_VERSION),
                    metaVersion(), "version stamped on adoption");
        }
    }

    @Test
    @DisplayName("Unknown newer schema version fails loudly")
    void unknownNewerVersionFailsLoudly() throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            io.pskenny.pkspkms.repo.sqlite.SQLiteSchema.init(conn);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT OR REPLACE INTO META (key, value) VALUES ('schema_version', '99');");
            }
        }

        try (SQLitePksFileRepository ignored = repo()) {
            fail("construction must fail on a newer schema");
        } catch (RepositoryException e) {
            String combined = e.getMessage() + " / " + e.getCause().getMessage();
            assertTrue(combined.contains("newer"), combined);
        }
    }

    @Test
    @DisplayName("reset flag drops and rebuilds the index")
    void resetFlagRebuilds() throws Exception {
        createFile(TEST_DIR, "gone.md", Map.of(), "content");
        try (SQLitePksFileRepository repo = repo()) {
            repo.loadDirectoryIntoRepository();
        }

        boolean reset = true;
        try (SQLitePksFileRepository repo = new SQLitePksFileRepository(
                DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()), reset)) {
            assertEquals(0, count("FILES"), "reset wiped the index");
            repo.loadDirectoryIntoRepository();
            assertEquals(1, count("FILES"), "full rescan after reset");
        }
    }

    @Test
    @DisplayName("add persists across runs instead of wiping the database")
    void addPersistsAcrossRuns() throws Exception {
        createFile(TEST_DIR, "main.md", Map.of(), "main");
        Path otherVault = Paths.get("target", "test-notes", "persistence-virtual");
        Files.createDirectories(otherVault);
        createFile(otherVault, "peer.md", Map.of(), "peer");

        try (SQLitePksFileRepository repo = repo()) {
            repo.loadDirectoryIntoRepository();
            assertFalse(repo.vaultAliasExists("bob"));
            repo.loadVirtualVault(new JavaFileSystem(otherVault.toFile()), "bob");
            assertTrue(repo.vaultAliasExists("bob"));
        }

        try (SQLitePksFileRepository repo = repo()) {
            assertTrue(repo.vaultAliasExists("bob"), "alias survived restart (BUGS.md B2)");
            assertThrows(IllegalArgumentException.class, () ->
                            repo.loadVirtualVault(new JavaFileSystem(otherVault.toFile()), "bob"),
                    "second add of the same alias reports duplicate, not wipe");
        }
    }

    @Test
    @DisplayName("Sidecar wal/shm files delete on clean close")
    void sidecarsDeletedOnCleanClose() throws Exception {
        createFile(TEST_DIR, "keep.md", Map.of(), "content");

        Path wal = Path.of(TEST_DB_PATH + "-wal");
        Path shm = Path.of(TEST_DB_PATH + "-shm");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(
                DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            assertTrue(java.nio.file.Files.exists(wal), "sidecar exists while the connection is open");
        }

        assertFalse(java.nio.file.Files.exists(wal), "wal gone after clean close");
        assertFalse(java.nio.file.Files.exists(shm), "shm gone after clean close");
    }

    private SQLitePksFileRepository repo() {
        return new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(TEST_DIR.toFile()));
    }

    private long count(String table) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long mtimeOf(String filePath) throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT file_last_modified FROM FILES WHERE file_path = ?")) {
            pstmt.setString(1, filePath);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    private String metaVersion() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT value FROM META WHERE key='schema_version'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
