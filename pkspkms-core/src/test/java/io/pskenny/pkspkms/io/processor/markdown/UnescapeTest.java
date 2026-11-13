package io.pskenny.pkspkms.io.processor.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnescapeTest {

    @Test
    void windowsPathsSurvive() {
        assertEquals("C:\\Users\\pk\\notes", Unescape.markdownPunctuation("C:\\Users\\pk\\notes"));
    }

    @Test
    void nonEscapableEscapesSurvive() {
        assertEquals("\\n\\s\\y", Unescape.markdownPunctuation("\\n\\s\\y"));
    }

    @Test
    void markdownEscapesRemoved() {
        assertEquals("*not italic*", Unescape.markdownPunctuation("\\*not italic\\*"));
        assertEquals("a_b", Unescape.markdownPunctuation("a\\_b"));
        assertEquals("[link]", Unescape.markdownPunctuation("\\[link\\]"));
    }

    @Test
    void escapedBackslashCollapses() {
        assertEquals("\\", Unescape.markdownPunctuation("\\\\"));
    }

    @Test
    void noBackslashesUntouched() {
        assertEquals("plain text", Unescape.markdownPunctuation("plain text"));
    }

    @Test
    void nullSafe() {
        assertEquals(null, Unescape.markdownPunctuation(null));
    }
}
