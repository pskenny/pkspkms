package io.pskenny.pkspkms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ApplicationTokenTest {

    @TempDir
    Path dir;

    private String tokenPath() {
        return dir.resolve("token").toString();
    }

    @Test
    void tokenDiffersEachStart() throws Exception {
        String first = Application.resolveToken(null, tokenPath());
        String second = Application.resolveToken(null, tokenPath());

        assertNotEquals(first, second, "each start generates a fresh token");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(java.nio.file.Path.of(tokenPath()));
        assertTrue(perms.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
                "token file stays owner-only");
    }

    @Test
    void explicitTokenFlagWinsAndDoesNotTouchFile() throws Exception {
        String token = Application.resolveToken("explicit-123", tokenPath());
        assertEquals("explicit-123", token);
        assertTrue(!Files.exists(java.nio.file.Path.of(tokenPath())), "no file written when --token given");
    }
}
