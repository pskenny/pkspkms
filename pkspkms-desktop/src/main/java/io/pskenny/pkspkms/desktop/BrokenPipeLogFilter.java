package io.pskenny.pkspkms.desktop;

import java.util.logging.LogRecord;

/**
 * Silences NanoHTTPD's SEVERE "Could not send response" stack traces caused by
 * clients aborting mid-stream (browser navigation, extension page reload).
 * Camouflage kills none of the information: the access log records the path
 * and duration; real transport errors still surface. Install once before
 * {@code server.start()}.
 */
public final class BrokenPipeLogFilter implements java.util.logging.Filter {

    private BrokenPipeLogFilter() {}

    public static void install() {
        java.util.logging.Logger nanoHttpdLogger =
                java.util.logging.Logger.getLogger(fi.iki.elonen.NanoHTTPD.class.getName());
        nanoHttpdLogger.setFilter(new BrokenPipeLogFilter());
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        return !isBrokenPipe(record);
    }

    static boolean isBrokenPipe(LogRecord record) {
        Throwable thrown = record.getThrown();
        while (thrown != null) {
            if (isTransportAbort(thrown)) {
                return true;
            }
            thrown = thrown.getCause();
        }
        return false;
    }

    // Only IO-family throwables with abort-class messages count — a real disk
    // or socket failure (e.g. "Disk quota exceeded") still logs.
    private static boolean isTransportAbort(Throwable t) {
        if (t == null) {
            return false;
        }
        boolean ioFamily = t instanceof java.net.SocketException
                || t instanceof java.io.IOException
                || t instanceof java.nio.channels.ClosedChannelException;
        if (!ioFamily || t.getMessage() == null) {
            return false;
        }
        String message = t.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("broken pipe")
                || message.contains("connection reset")
                || message.contains("stream closed")
                || message.contains("connection closed")
                || message.contains("connection aborted");
    }
}
