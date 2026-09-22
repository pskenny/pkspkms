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
