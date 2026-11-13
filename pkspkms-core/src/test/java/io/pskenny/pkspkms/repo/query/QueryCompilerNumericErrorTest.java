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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed-property numeric garbage parses as a query error (BUGS.md M7a):
 * `price:abc` against a NUMBER-declared type raised a raw
 * NumberFormatException (HTTP 500) instead of the documented 400 path.
 */
public class QueryCompilerNumericErrorTest {

    private static final PropertyTypes TYPES = PropertyTypes.of(Map.of("price", "number"));

    @Test
    void garbageFieldEqIsAParseError() {
        var e = assertThrows(QueryParseException.class, () -> compile("price:abc"));
        assertTrue(e.getMessage().contains("not a number"), e.getMessage());
    }

    @Test
    void garbageRangeBoundIsAParseError() {
        var e = assertThrows(QueryParseException.class, () -> compile("price:[abc TO 5]"));
        assertTrue(e.getMessage().contains("not a number"), e.getMessage());
        var hi = assertThrows(QueryParseException.class, () -> compile("price:[1 TO xyz]"));
        assertTrue(hi.getMessage().contains("not a number"), hi.getMessage());
    }

    @Test
    void numericUtfIsStillAccepted() {
        CompiledQuery compiled = compile("price:4.50");
        assertTrue(compiled.sql().contains("json_extract"), compiled.sql());
        assertEquals(4, compiled.params().size());
    }

    private static CompiledQuery compile(String query) {
        return new QueryCompiler().compile(new QueryParser().parse(query), TYPES);
    }
}
