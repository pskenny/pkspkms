package io.pskenny.pkspkms.desktop;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight tee of {@code System.err} that keeps the most recent non-empty line.
 * Used by the system tray to display the last log event.
 */
public final class LogCapture {
    private final AtomicReference<String> lastLine = new AtomicReference<>("Initialising...");
    private final PrintStream originalErr;

    public LogCapture() {
        this.originalErr = System.err;
        System.setErr(new TeePrintStream(originalErr, lastLine));
    }

    public String getLastLine() {
        return lastLine.get();
    }

    public void restore() {
        System.setErr(originalErr);
    }

    private static final class TeePrintStream extends PrintStream {
        private final AtomicReference<String> lastLine;

        TeePrintStream(OutputStream out, AtomicReference<String> lastLine) {
            super(out, true);
            this.lastLine = lastLine;
        }

        @Override
        public void println(String x) {
            super.println(x);
            if (x != null && !x.isBlank()) {
                // Truncate very long lines for tray display
                String trimmed = x.length() > 60 ? x.substring(0, 57) + "..." : x;
                lastLine.set(trimmed);
            }
        }

        @Override
        public void println(Object x) {
            super.println(x);
            if (x != null) {
                String s = x.toString();
                if (!s.isBlank()) {
                    String trimmed = s.length() > 60 ? s.substring(0, 57) + "..." : s;
                    lastLine.set(trimmed);
                }
            }
        }
    }
}
