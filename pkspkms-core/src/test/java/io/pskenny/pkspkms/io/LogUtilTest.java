package io.pskenny.pkspkms.io;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogUtilTest {

    @Test
    void stripsCarriageReturn() {
        assertEquals("a b", LogUtil.oneLine("a\rb"));
    }

    @Test
    void stripsLineFeed() {
        assertEquals("a b", LogUtil.oneLine("a\nb"));
    }

    @Test
    void stripsCrlfAndZeroByte() {
        assertEquals("a b c", LogUtil.oneLine("a\r\nb\0c"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", LogUtil.oneLine(null));
    }
}
