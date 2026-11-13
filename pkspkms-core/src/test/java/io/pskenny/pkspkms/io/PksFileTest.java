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

package io.pskenny.pkspkms.io;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

public class PksFileTest {

    @Test
    void copyConstructorDeepCopiesLists() {
        PksFile source = new PksFile("a.md", java.util.Map.of("links", List.of("x")));
        PksFile copy = new PksFile(source, "b.md");

        copy.getMutableProperties().put("links", new java.util.ArrayList<>(List.of("y")));

        assertNotSame(source.getMutableProperties().get("links"), copy.getMutableProperties().get("links"),
                "copied list must be independent (P3 shallow-copy aliasing)");
        assertEquals(List.of("x"), source.getAsList("links"), "source must be untouched");
        assertEquals("b.md", copy.getFilePath());
        assertEquals("a.md", source.getFilePath());
    }
}
