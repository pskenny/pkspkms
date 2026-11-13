/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of PKSPKMS.
 * Copyright (C) 2026 Paul Kenny
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.pskenny.pkspkms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ApplicationTokenTest {

    @TempDir
    Path dir;

    private String tokenPath() {
        return dir.resolve("token").toString();
    }

    // --- the auth gate (BUGS: bearer auth now opt-in via --enable-auth) ---

    @Test
    void disabledAuthYieldsNullTokenAndNoFile() throws Exception {
        assertNull(Application.resolveApiToken(false, null, tokenPath()));
        assertTrue(!Files.exists(java.nio.file.Path.of(tokenPath())), "no token file without the flag");
    }

    @Test
    void disabledAuthIgnoresExplicitToken() throws Exception {
        assertNull(Application.resolveApiToken(false, "abc", tokenPath()));
        assertTrue(!Files.exists(java.nio.file.Path.of(tokenPath())));
    }

    @Test
    void enabledAuthRotatesPerStart() throws Exception {
        String first = Application.resolveApiToken(true, null, tokenPath());
        String second = Application.resolveApiToken(true, null, tokenPath());

        assertNotEquals(first, second, "each start generates a fresh token");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(java.nio.file.Path.of(tokenPath()));
        assertTrue(perms.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
                "token file stays owner-only");
    }

    @Test
    void enabledAuthExplicitTokenWins() throws Exception {
        String token = Application.resolveApiToken(true, "explicit-123", tokenPath());
        assertEquals("explicit-123", token);
        assertTrue(!Files.exists(java.nio.file.Path.of(tokenPath())), "no file written when --token given");
    }

    // --- kept pins on the underlying resolver ---

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
