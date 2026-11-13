package io.pskenny.pkspkms.repo.sqlite;

import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.test.FileUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Broken-links sweep cache lifecycle: repeats ride the per-scope cache, a
 * load (or any FILES/LINKS-mutating path) re-arms it. The compute counter is
 * package-visible, test-only observability — the payload contract itself is
 * pinned by the McpDispatcher battery.
 */
public class HygieneCacheTest {

    @Test
    @DisplayName("two calls → one sweep; scope change → its own sweep; load re-arms")
    void cacheLifetime() throws Exception {
        Path vault = Files.createTempDirectory("hygiene");
        FileUtil.createFile(vault, "dangly.md", Map.of(), "points at [[Ghost]]");
        PkmsFileSystem fs = new JavaFileSystem(vault.toFile());
        SQLitePksFileRepository repo = new SQLitePksFileRepository(
                "jdbc:sqlite:" + vault.resolve("h.db"), null, fs);
        try {
            repo.loadDirectoryIntoRepository();
            repo.brokenLinks("refcount", 500, 0, ""); // prime
            long first = repo.hygieneComputations;
            repo.brokenLinks("refcount", 500, 0, "");
            assertEquals(first, repo.hygieneComputations, "second call rides the cache");

            repo.brokenLinks("refcount", 500, 0, "@gwern");
            assertEquals(first + 1, repo.hygieneComputations, "a new scope computes its own sweep");

            repo.loadDirectoryIntoRepository();
            repo.brokenLinks("refcount", 500, 0, "");
            assertEquals(first + 2, repo.hygieneComputations, "a load invalidates");
        } finally {
            if (repo != null) {
                repo.close();
            }
        }
        // safely read-only, packed only at request time; stray pact clean-up
        Files.deleteIfExists(vault.resolve("h.db"));
        Files.deleteIfExists(vault.resolve("h.db-wal"));
        Files.deleteIfExists(vault.resolve("h.db-shm"));
    }
}
