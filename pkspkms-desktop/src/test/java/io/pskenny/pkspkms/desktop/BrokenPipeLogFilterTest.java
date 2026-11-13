package io.pskenny.pkspkms.desktop;

import org.junit.jupiter.api.Test;

import java.util.logging.LogRecord;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokenPipeLogFilterTest {

    private LogRecord recordWith(Throwable thrown) {
        LogRecord r = new LogRecord(Level.SEVERE, "Could not send response to the client");
        r.setThrown(thrown);
        return r;
    }

    @Test
    void brokenPipeSocketExceptionFiltered() {
        var cause = new java.net.SocketException("Broken pipe");
        assertTrue(BrokenPipeLogFilter.isBrokenPipe(recordWith(cause)));
    }

    @Test
    void wrappedBrokenPipeFiltered() {
        var cause = new RuntimeException("wrap", new java.io.IOException("Broken pipe"));
        assertTrue(BrokenPipeLogFilter.isBrokenPipe(recordWith(cause)));
    }

    @Test
    void connectionResetFiltered() {
        var cause = new java.net.SocketException("Connection reset by peer");
        assertTrue(BrokenPipeLogFilter.isBrokenPipe(recordWith(cause)));
    }

    @Test
    void realIoErrorKept() {
        var cause = new java.io.IOException("Disk quota exceeded");
        assertFalse(BrokenPipeLogFilter.isBrokenPipe(recordWith(cause)));
    }

    @Test
    void noThrowableKept() {
        assertFalse(BrokenPipeLogFilter.isBrokenPipe(new LogRecord(Level.SEVERE, "message only")));
    }

    @Test
    void nullMessageKept() {
        var cause = new RuntimeException() {
            @Override
            public String getMessage() {
                return null;
            }
        };
        LogRecord r = new LogRecord(Level.SEVERE, "client gone");
        r.setThrown(cause);
        assertFalse(BrokenPipeLogFilter.isBrokenPipe(r));
    }

    @Test
    void installDoesNotThrow() {
        BrokenPipeLogFilter.install();
        BrokenPipeLogFilter.install(); // idempotent
    }
}
