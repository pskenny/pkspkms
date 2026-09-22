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
