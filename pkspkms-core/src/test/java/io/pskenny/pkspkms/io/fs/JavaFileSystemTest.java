package io.pskenny.pkspkms.io.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Symlinks in a vault: directory links are never followed (cycles, escapes);
 * file links are indexed only when their target stays inside the vault (B57).
 */
public class JavaFileSystemTest {

    @TempDir
    Path vault;

    private List<String> paths() throws IOException {
        return new JavaFileSystem(vault.toFile()).listFiles(List.of()).stream()
                .map(PkmsEntry::relativePath)
                .collect(Collectors.toList());
    }

    @Test
    void plainFileIsIndexed() throws IOException {
        Files.writeString(vault.resolve("keep.md"), "content");

        assertEquals(List.of("keep.md"), paths());
    }

    @Test
    void siblingVaultContentIsRejectedNotBleedThrough() throws IOException {
        // BUGS.md 11: containment without the trailing separator let a sibling
        // root's files pass the probe and bleed "/v2/…" into the relative path
        Path sibling = vault.resolveSibling(vault.getFileName() + "-2");
        try {
            Files.createDirectories(sibling);
            Files.writeString(sibling.resolve("sibling.md"), "content");

            var fs = new JavaFileSystem(vault.toFile());
            // `..`-relative sibling probe — must be rejected, not bleed through
            String siblingProbe = java.nio.file.Paths.get("..", String.valueOf(vault.getFileName()) + "-2", "sibling.md").toString();
            assertThrows(IOException.class,
                    () -> fs.resolve(siblingProbe),
                    "sibling content must not resolve into this vault");
            assertTrue(fs.listFiles(List.of()).stream()
                    .map(PkmsEntry::relativePath)
                    .noneMatch(p -> p.contains(vault.getFileName() + "-2")),
                    "relativePath derivation must not bleed sibling names");
        } finally {
            deleteQuietly(sibling);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        } catch (IOException ignored) {
        }
    }

    @Test
    void symlinkedFilePointingOutsideIsSkipped() throws IOException {
        Files.writeString(vault.resolve("keep.md"), "control");
        Path outside = vault.resolveSibling("outside-target.md");
        Files.writeString(outside, "secret");
        Files.createSymbolicLink(vault.resolve("leak.md"), outside);

        assertEquals(List.of("keep.md"), paths(), "out-of-root file links are skipped");
        Files.deleteIfExists(outside);
    }

    @Test
    void symlinkedFilePointingInsideResolvesToItsTarget() throws IOException {
        Files.writeString(vault.resolve("real.md"), "content");
        Files.createSymbolicLink(vault.resolve("alias.md"), vault.resolve("real.md"));

        // relativePath canonicalizes: the link indexes under its target's path
        assertEquals(List.of("real.md"), paths());
    }

    @Test
    void symlinkedDirectoriesAreNeverFollowed() throws IOException {
        Files.writeString(vault.resolve("keep.md"), "control");

        Path outsideDir = vault.resolveSibling("outside-vault-dir");
        Files.createDirectories(outsideDir);
        Files.writeString(outsideDir.resolve("secret.md"), "secret");
        Files.createSymbolicLink(vault.resolve("linked-out"), outsideDir);

        Path insideDir = vault.resolve("real-dir");
        Files.createDirectories(insideDir);
        Files.writeString(insideDir.resolve("nested.md"), "nested");
        Files.createSymbolicLink(vault.resolve("linked-in"), insideDir);

        // real-dir itself is real content and stays indexed; both links are skipped
        assertEquals(Set.of("keep.md", "real-dir/nested.md"), Set.copyOf(paths()));
        Files.deleteIfExists(outsideDir.resolve("secret.md"));
        Files.deleteIfExists(outsideDir);
    }

    @Test
    void symlinkCycleTerminates() throws IOException {
        Files.writeString(vault.resolve("keep.md"), "control");
        Path dir = vault.resolve("a");
        Files.createDirectories(dir);
        Files.createSymbolicLink(dir.resolve("loop"), dir);

        // A cycle must not hang or throw — symlinked dirs are simply not followed
        assertEquals(List.of("keep.md"), paths());
        assertTrue(true);
    }
}
