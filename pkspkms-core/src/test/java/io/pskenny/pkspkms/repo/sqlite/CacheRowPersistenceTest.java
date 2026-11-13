package io.pskenny.pkspkms.repo.sqlite;

import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cache-row persistence across reloads (BUGS.md H1): FILES rows written by
 * {@link SQLitePksFileRepository#cacheFile} for paths under
 * {@code .pkspkms-cache} must survive a follow-up vault load — the walk
 * never sees those directories (always-excluded), so absence from
 * {@code activeDbPaths} must not classify them deleted.
 */
public class CacheRowPersistenceTest {

    @TempDir
    Path vault;

    @TempDir
    Path peer;

    private static final String CACHE_DIR = ".pkspkms-cache";

    @Test
    void cacheRowSurvivesReload() throws Exception {
        Path db = vault.resolve("index.db");
        String dbUrl = "jdbc:sqlite:" + db;

        Files.writeString(peer.resolve("note.md"), "# Note\ncopied content");
        Files.writeString(vault.resolve("README.md"), "# Vault\nmain vault");

        try (SQLitePksFileRepository repo = new SQLitePksFileRepository(
                dbUrl, null, new JavaFileSystem(vault.toFile()), false)) {

            repo.loadVirtualVault(new JavaFileSystem(peer.toFile()), "gwern");
            repo.loadDirectoryIntoRepository();

            String cachePath = repo.cacheFile("@gwern", "note.md", CACHE_DIR)
                    .get("file").toString();
            assertEquals(CACHE_DIR + "/@gwern/note.md", cachePath);
            assertTrue(repo.blake3Of(cachePath).isPresent(), "cache row hashed after first cache");

            int firstId = rowIdFor(db, cachePath);

            // A plain reload (no changes to the vault) must KEEP the row
            repo.loadDirectoryIntoRepository();

            assertEquals(firstId, rowIdFor(db, cachePath), "rowid must be stable across reload");
            assertTrue(repo.blake3Of(cachePath).isPresent(),
                    "the cached file's FILES row must survive reload (BUGS.md H1)");
        }
    }

    private static int rowIdFor(Path db, String path) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery(
                     "SELECT id FROM FILES WHERE file_path = '" + path.replace("'", "''") + "'")) {
            if (!rs.next()) {
                throw new AssertionError("row missing for " + path);
            }
            return rs.getInt(1);
        }
    }
}
