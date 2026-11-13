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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PropertyTypesTest {

    // Mirrors the real .obsidian/types.json shape ({"types": {property: ObsidianType}})
    private static final String TYPED_VAULT = """
            {"types": {
              "price": "number",
              "creationDate": "date",
              "Wake-up time": "datetime",
              "Status": "multitext",
              "tags": "tags",
              "aliases": "aliases",
              "Drink": "checkbox",
              "excalidraw-font": "text",
              "Weird": "customtype"
            }}
            """;

    private PropertyTypes parse(String json) {
        InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return PropertyTypes.parse(in);
    }

    @Test
    void parsesRealShape() {
        PropertyTypes types = PropertyTypes.parse(new ByteArrayInputStream(TYPED_VAULT.getBytes(StandardCharsets.UTF_8)));
        assertEquals(PropertyTypes.Kind.NUMBER, types.typeOf("price"));
        assertEquals(PropertyTypes.Kind.DATE, types.typeOf("creationDate"));
        assertEquals(PropertyTypes.Kind.DATETIME, types.typeOf("Wake-up time"));
        assertEquals(PropertyTypes.Kind.MULTITEXT, types.typeOf("Status"));
        assertEquals(PropertyTypes.Kind.MULTITEXT, types.typeOf("tags"));
        assertEquals(PropertyTypes.Kind.MULTITEXT, types.typeOf("aliases"));
        assertEquals(PropertyTypes.Kind.CHECKBOX, types.typeOf("Drink"));
        assertEquals(PropertyTypes.Kind.TEXT, types.typeOf("excalidraw-font"));
        assertEquals(PropertyTypes.Kind.TEXT, types.typeOf("Weird"));
    }

    @Test
    void undeclaredFieldsFallBack() {
        PropertyTypes types = PropertyTypes.parse(new ByteArrayInputStream(TYPED_VAULT.getBytes(StandardCharsets.UTF_8)));
        assertEquals(PropertyTypes.Kind.UNDECLARED, types.typeOf("year"));
        assertEquals(PropertyTypes.Kind.UNDECLARED, types.typeOf(""));
    }

    @Test
    void emptyAndMalformedFallBackToEmpty() {
        assertTrue(PropertyTypes.parse(new ByteArrayInputStream(new byte[0])).typeOf("price") == PropertyTypes.Kind.UNDECLARED);
        assertTrue(PropertyTypes.parse(new ByteArrayInputStream("not json".getBytes(StandardCharsets.UTF_8)))
                .typeOf("price") == PropertyTypes.Kind.UNDECLARED);
        assertTrue(PropertyTypes.parse(new ByteArrayInputStream("{\"noTypesKey\": 1}".getBytes(StandardCharsets.UTF_8)))
                .typeOf("price") == PropertyTypes.Kind.UNDECLARED);
    }

    @Test
    void emptyInstanceIsUndeclared() {
        assertEquals(PropertyTypes.Kind.UNDECLARED, PropertyTypes.empty().typeOf("price"));
    }
}
