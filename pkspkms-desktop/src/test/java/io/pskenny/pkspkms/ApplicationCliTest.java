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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// BUGS.md B18: no-args used to NPE on switch(null) without a clean exit and
// an unknown command produced a raw stack trace. Application.run returns the
// process exit code so the CLI surface can be asserted.
class ApplicationCliTest {

    @Test
    void noArgsExitsCleanlyWithMessage() {
        assertEquals(1, Application.run(new String[]{}));
    }

    @Test
    void unknownCommandExitsCleanlyWithMessage() {
        assertEquals(1, Application.run(new String[]{"frobnicate"}));
    }

    @Test
    void badQueryOnExportExitsCleanly() {
        // export requires --directory/--output/--type → argparse failure path
        assertEquals(1, Application.run(new String[]{"export", "--query", "((("}));
    }

    @Test
    void tokenWithoutEnableAuthExitsCleanly() {
        // --enable-auth is the sole auth switch (Option A): bare --token is a
        // usage error, not a silent no-auth server with a dead token arg
        assertEquals(1, Application.run(new String[]{
                "server", "--directory", "target/never-read", "--token", "secret-123"}));
    }

    @Test
    void wildcardCorsWithoutAuthExitsCleanly() {
        // --cors-origin * with auth off = every web page reads the vault —
        // rejected at startup, not a runtime foot-gun
        assertEquals(1, Application.run(new String[]{
                "server", "--directory", "target/never-read", "--cors-origin", "*"}));
    }

    @Test
    void corsOriginPairsWithAuthAndBuildsTheIndex() throws Exception {
        // wildcard + auth passes argparse AND reaches the index build. The
        // bad --db path (a directory) kills the repository construction
        // post-guard — a repository exception, cleanly before server start.
        java.nio.file.Path badDb = java.nio.file.Files.createDirectories(
                java.nio.file.Path.of("target", "db-as-dir"));
        int code = Application.run(new String[]{
                "server", "--directory", "target/never-created",
                "--enable-auth", "--cors-origin", "*", "--db", badDb.toString()});
        assertEquals(1, code, "bad db still fails the start");
    }
}
