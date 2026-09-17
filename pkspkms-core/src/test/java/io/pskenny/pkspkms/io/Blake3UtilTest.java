package io.pskenny.pkspkms.io;

import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class Blake3UtilTest {

    private static final Path TEST_DIR = Path.of("target", "test-blake3");

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(TEST_DIR);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(TEST_DIR)) {
            Files.walk(TEST_DIR)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    @DisplayName("Hash file produces deterministic output")
    void testHashFileDeterministic() throws IOException {
        Path testFile = TEST_DIR.resolve("test.txt");
        Files.writeString(testFile, "Hello, BLAKE3!");

        String hash1 = Blake3Util.hashFile(testFile.toFile());
        String hash2 = Blake3Util.hashFile(testFile.toFile());

        assertEquals(hash1, hash2, "Same file should produce same hash");
        assertEquals(64, hash1.length(), "BLAKE3 hex hash should be 64 characters");
    }

    @Test
    @DisplayName("Hash empty file")
    void testHashEmptyFile() throws IOException {
        Path emptyFile = TEST_DIR.resolve("empty.txt");
        Files.writeString(emptyFile, "");

        String hash = Blake3Util.hashFile(emptyFile.toFile());

        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    @DisplayName("Different files produce different hashes")
    void testHashDifferentFiles() throws IOException {
        Path file1 = TEST_DIR.resolve("file1.txt");
        Path file2 = TEST_DIR.resolve("file2.txt");
        Files.writeString(file1, "Content A");
        Files.writeString(file2, "Content B");

        String hash1 = Blake3Util.hashFile(file1.toFile());
        String hash2 = Blake3Util.hashFile(file2.toFile());

        assertNotEquals(hash1, hash2, "Different files should produce different hashes");
    }

    @Test
    @DisplayName("Same content produces same hash regardless of filename")
    void testHashSameContentDifferentFiles() throws IOException {
        Path file1 = TEST_DIR.resolve("name1.txt");
        Path file2 = TEST_DIR.resolve("name2.txt");
        String content = "Identical content";
        Files.writeString(file1, content);
        Files.writeString(file2, content);

        String hash1 = Blake3Util.hashFile(file1.toFile());
        String hash2 = Blake3Util.hashFile(file2.toFile());

        assertEquals(hash1, hash2, "Same content should produce same hash");
    }

    @Test
    @DisplayName("Hash bytes directly")
    void testHashBytes() {
        byte[] data = "Test data".getBytes();
        String hash = Blake3Util.hashBytes(data);

        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    @DisplayName("Hash string")
    void testHashString() {
        String data = "Test string";
        String hash = Blake3Util.hashString(data);

        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertEquals(Blake3Util.hashBytes(data.getBytes()), hash);
    }
}
