package io.pskenny.pkspkms.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LogCaptureTest {

    @Test
    @DisplayName("LogCapture captures the last printed line")
    void testCapturesLastLine() {
        LogCapture capture = new LogCapture();
        try {
            System.err.println("First line");
            System.err.println("Second line");
            assertEquals("Second line", capture.getLastLine());
        } finally {
            capture.restore();
        }
    }

    @Test
    @DisplayName("LogCapture truncates very long lines")
    void testTruncatesLongLines() {
        LogCapture capture = new LogCapture();
        try {
            String longLine = "A".repeat(100);
            System.err.println(longLine);
            String result = capture.getLastLine();
            assertTrue(result.endsWith("..."), "Long line should be truncated");
            assertEquals(60, result.length(), "Truncated length should be 60");
        } finally {
            capture.restore();
        }
    }
}
