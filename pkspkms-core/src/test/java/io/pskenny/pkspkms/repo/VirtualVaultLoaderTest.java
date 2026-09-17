package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.Blake3Util;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.createFile;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.QueryCompiler;
import io.pskenny.pkspkms.repo.query.QueryParser;
import static org.junit.jupiter.api.Assertions.*;

public class VirtualVaultLoaderTest {

    private static final Path MAIN_DIR = Path.of("target", "test-notes", "main-vault");
    private static final Path VIRTUAL_DIR = Path.of("target", "test-notes", "virtual-vault");
    private static final String TEST_DB_PATH = "test_virtual_vault.db";
    private static final String DB_URL = "jdbc:sqlite:" + TEST_DB_PATH;

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(Path.of(TEST_DB_PATH));
        Files.createDirectories(MAIN_DIR);
        Files.createDirectories(VIRTUAL_DIR);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(Path.of(TEST_DB_PATH));

        for (Path dir : new Path[]{MAIN_DIR, VIRTUAL_DIR}) {
            if (Files.exists(dir)) {
                try (Stream<Path> pathStream = Files.walk(dir)) {
                    pathStream
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                }
            }
        }
    }


    private static CompiledQuery Q(String q) {
        return new QueryCompiler().compile(new QueryParser().parse(q));
    }

    @Test
    @DisplayName("Load virtual vault with alias prefix")
    void testLoadVirtualVaultWithAlias() throws Exception {
        createFile(VIRTUAL_DIR, "virtual-note.md", Map.of("tags", "virtual"), "Virtual content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            var results = repository.searchRegular(Q("filePath:@gwern/virtual-note.md"));

            assertEquals(1, results.size(), "Virtual vault file should be found with @alias prefix");
        }
    }

    @Test
    @DisplayName("Virtual vault files are prefixed with @alias/")
    void testVirtualVaultFilesPrefixed() throws Exception {
        createFile(VIRTUAL_DIR, "test.md", Map.of(), "Content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "test");

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT file_path FROM FILES WHERE file_path LIKE '@test/%'")) {
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next(), "Should have files with @test/ prefix");
                assertTrue(rs.getString("file_path").startsWith("@test/"));
            }
        }
    }

    @Test
    @DisplayName("Virtual vault files have BLAKE3 hash computed")
    void testVirtualVaultBlake3Computed() throws Exception {
        createFile(VIRTUAL_DIR, "hash-test.md", Map.of(), "Content for hashing");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT blake3 FROM FILES WHERE file_path LIKE '@gwern/%'")) {
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next(), "Should have virtual vault files");
                String blake3 = rs.getString("blake3");
                assertNotNull(blake3, "BLAKE3 hash should not be null");
                assertEquals(64, blake3.length(), "BLAKE3 hex hash should be 64 characters");
            }
        }
    }

    @Test
    @DisplayName("Vault alias record is created")
    void testVaultAliasRecordCreated() throws Exception {
        createFile(VIRTUAL_DIR, "note.md", Map.of(), "Content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            assertTrue(repository.vaultAliasExists("gwern"), "Vault alias should exist");

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT alias, directory, is_virtual FROM VAULT_ALIASES WHERE alias = 'gwern'")) {
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next());
                assertEquals("gwern", rs.getString("alias"));
                assertEquals("", rs.getString("directory"));
                assertEquals(1, rs.getInt("is_virtual"));
            }
        }
    }

    @Test
    @DisplayName("Load multiple virtual vaults")
    void testLoadMultipleVirtualVaults() throws Exception {
        createFile(VIRTUAL_DIR, "gwern-note.md", Map.of("tags", "gwern"), "Gwern content");

        Path virtualDir2 = Path.of("target", "test-notes", "virtual-vault-2");
        Files.createDirectories(virtualDir2);
        createFile(virtualDir2, "confluence-note.md", Map.of("tags", "confluence"), "Confluence content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");
            repository.loadVirtualVault(new JavaFileSystem(virtualDir2.toFile()), "confluence");

            var gwernResults = repository.searchRegular(Q("filePath:@gwern/gwern-note.md"));
            assertEquals(1, gwernResults.size(), "Gwern vault file should be found");

            var confResults = repository.searchRegular(Q("filePath:@confluence/confluence-note.md"));
            assertEquals(1, confResults.size(), "Confluence vault file should be found");
        }

        Files.walk(virtualDir2)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    @Test
    @DisplayName("Main vault and virtual vault coexist")
    void testMainVaultAndVirtualVaultCoexist() throws Exception {
        createFile(MAIN_DIR, "main-note.md", Map.of("tags", "main"), "Main content");
        createFile(VIRTUAL_DIR, "virtual-note.md", Map.of("tags", "virtual"), "Virtual content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            var mainResults = repository.searchRegular(Q("filePath:main-note.md"));
            assertEquals(1, mainResults.size(), "Main vault file should be found");

            var virtualResults = repository.searchRegular(Q("filePath:@gwern/virtual-note.md"));
            assertEquals(1, virtualResults.size(), "Virtual vault file should be found");
        }
    }

    @Test
    @DisplayName("Duplicate alias throws error")
    void testDuplicateAliasThrowsError() throws Exception {
        createFile(VIRTUAL_DIR, "note.md", Map.of(), "Content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            assertThrows(IllegalArgumentException.class, () -> {
                repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");
            }, "Loading same alias twice should throw exception");
        }
    }

    @Test
    @DisplayName("Main vault files have null vault_alias_id")
    void testMainVaultFilesHaveNullAliasId() throws Exception {
        createFile(MAIN_DIR, "main.md", Map.of(), "Main content");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT vault_alias_id FROM FILES WHERE file_path = 'main.md'")) {
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next());
                assertNull(rs.getObject("vault_alias_id"), "Main vault files should have null vault_alias_id");
            }
        }
    }

    @Test
    @DisplayName("Main vault files have BLAKE3 hash")
    void testMainVaultFilesHaveBlake3() throws Exception {
        createFile(MAIN_DIR, "hash-main.md", Map.of(), "Main content for hashing");

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT blake3 FROM FILES WHERE file_path = 'hash-main.md'")) {
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next());
                String blake3 = rs.getString("blake3");
                assertNotNull(blake3, "Main vault files should also have BLAKE3 hash");
                assertEquals(64, blake3.length());
            }
        }
    }

    @Test
    @DisplayName("Main vault binary files are indexed via streaming hash")
    void testMainVaultBinaryFileStreamHashed() throws Exception {
        byte[] blob = new byte[1024 * 1024];
        new Random(42).nextBytes(blob);
        Files.write(MAIN_DIR.resolve("blob.bin"), blob);

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT blake3, file_name, file_name_ext FROM FILES WHERE file_path = 'blob.bin'")) {
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next(), "Binary file should be indexed");
                assertEquals(Blake3Util.hashBytes(blob), rs.getString("blake3"), "Streaming hash must equal bytes hash");
                assertEquals("blob", rs.getString("file_name"));
                assertEquals("blob.bin", rs.getString("file_name_ext"));
            }
        }
    }

    @Test
    @DisplayName("Parallel load indexes all files")
    void testParallelLoadIndexesAllFiles() throws Exception {
        for (int i = 0; i < 50; i++) {
            createFile(MAIN_DIR, "note-" + i + ".md", Map.of("idx", i), "content " + i);
        }
        Files.write(MAIN_DIR.resolve("blob.bin"), new byte[4096]);

        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM FILES")) {
                rs.next();
                assertEquals(51, rs.getInt(1), "All files must survive parallel parsing");
            }
        }
    }
}
