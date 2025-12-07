package io.github.pskenny.repo;

import io.github.pskenny.io.PksFile;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SQLitePksFileRepositoryTest {

    private static final String TEST_DB_PATH = "test.db";
    private static SQLitePksFileRepository repository;
    private static Path dbPath;

    @BeforeAll
    public static void setupClass() throws IOException {
        dbPath = Path.of(TEST_DB_PATH);
        if (Files.exists(dbPath)) {
            Files.delete(dbPath);
        }
    }

    @BeforeEach
    public void setup() {
        repository = new SQLitePksFileRepository(TEST_DB_PATH);
        repository.init();
    }

    @AfterEach
    public void teardown() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + TEST_DB_PATH)) {
            conn.createStatement().execute("DROP TABLE files");
        }
    }

    @AfterAll
    public static void teardownClass() throws IOException {
        Files.deleteIfExists(dbPath);
    }

    @Test
    public void testInitCreatesTable() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + TEST_DB_PATH)) {
            assertNotNull(conn.getMetaData().getTables(null, null, "files", null));
        }
    }

    @Test
    public void testSaveAndFindByFileName() throws SQLException {
        Map<String, Object> properties = new HashMap<>();
        properties.put("key", "value");
        PksFile file = new PksFile("testfile.pks", properties);
        repository.save(file);
        Optional<PksFile> foundFile = repository.findByFileName("testfile.pks");
        assertTrue(foundFile.isPresent());
        assertEquals("testfile.pks", foundFile.get().getFilePath());
        assertEquals("value", foundFile.get().getProperties().get("key"));
    }

    @Test
    public void testSaveDuplicateFileReturnsNull() throws SQLException {
        Map<String, Object> properties = new HashMap<>();
        PksFile file1 = new PksFile("duplicate.pks", properties);
        PksFile file2 = new PksFile("duplicate.pks", properties);
        repository.save(file1);
        PksFile result = repository.save(file2);
        assertNull(result);
    }

    @Test
    public void testUpdate() throws SQLException {
        Map<String, Object> originalProps = new HashMap<>();
        originalProps.put("key", "value");
        PksFile file = new PksFile("updatefile.pks", originalProps);
        repository.save(file);
        Map<String, Object> updatedProps = new HashMap<>();
        updatedProps.put("key", "newValue");
        PksFile updatedFile = new PksFile("updatefile.pks", updatedProps);
        repository.update(updatedFile);
        Optional<PksFile> foundFile = repository.findByFileName("updatefile.pks");
        assertTrue(foundFile.isPresent());
        assertEquals("newValue", foundFile.get().getProperties().get("key"));
    }

    @Test
    public void testUpdateNonExistentFileThrowsException() {
        Map<String, Object> properties = new HashMap<>();
        PksFile file = new PksFile("nonexistent.pks", properties);
        assertThrows(SQLException.class, () -> {
            repository.update(file);
        });
    }

    @Test
    public void testFindAll() throws SQLException {
        repository.save(new PksFile("file1.pks", new HashMap<>()));
        repository.save(new PksFile("file2.pks", new HashMap<>()));
        List<PksFile> files = repository.findAll();
        assertEquals(2, files.size());
    }

    @Test
    public void testFindByTag() throws SQLException {
        Map<String, Object> props1 = new HashMap<>();
        props1.put("tags", List.of("java", "junit"));
        PksFile file1 = new PksFile("file1.pks", props1);
        repository.save(file1);
        Map<String, Object> props2 = new HashMap<>();
        props2.put("tags", List.of("python", "django"));
        PksFile file2 = new PksFile("file2.pks", props2);
        repository.save(file2);
        List<PksFile> foundByTag = repository.findByTag("java");
        assertEquals(1, foundByTag.size());
        assertEquals("file1.pks", foundByTag.get(0).getFilePath());
    }

    @Test
    public void testFindByTagNoMatch() throws SQLException {
        Map<String, Object> props1 = new HashMap<>();
        props1.put("tags", List.of("java"));
        PksFile file1 = new PksFile("file1.pks", props1);
        repository.save(file1);
        List<PksFile> foundByTag = repository.findByTag("nonexistent");
        assertTrue(foundByTag.isEmpty());
    }

    @Test
    public void testFindByFileNameNotFound() throws SQLException {
        Optional<PksFile> foundFile = repository.findByFileName("nonexistent.pks");
        assertFalse(foundFile.isPresent());
    }
}