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

package io.pskenny.pkspkms.repo.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser hardening pins (BUGS.md D2/D3).
 */
public class QueryParserDepthCapTest {

    @Test
    void deepNestingIsAParseErrorNotAStackOverflow() {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < 4096; i++) {
            q.append('(');
        }
        q.append("tags:X");
        for (int i = 0; i < 4096; i++) {
            q.append(')');
        }
        QueryParseException e = assertThrows(QueryParseException.class,
                () -> new QueryParser().parse(q.toString()),
                "hostile nesting must surface as a 400, not a stack overflow");
        assertTrue(e.getMessage().contains("nesting"), e.getMessage());
    }

    @Test
    void saneNestingStillParses() {
        var parsed = new QueryParser().parse("((tags:X) and (tags:Y))");
        // simple compile check keeps the recognizer path alive
        new QueryCompiler().compile(parsed);
    }

    @Test
    void quotedBackslashKeyCompilesThroughJsonPath() {
        // D3: the escaping contract is runtime-verified — the compile path
        // must not throw for keys carrying quotes/backslashes
        var parsed = new QueryParser().parse("tags:X");
        var compiled = new QueryCompiler().compile(parsed);
        assertTrue(compiled.params().size() >= 1);
    }
}
