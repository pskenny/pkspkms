package io.pskenny.pkspkms.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Creates the system tray off the startup critical path. dorkbox initialization
 * can block indefinitely (desktop-detection subprocess spawn), so construction
 * runs in a worker thread with a timeout and fails headless instead of hanging
 * the application.
 */
public final class TrayFactory {

    private static final Logger logger = LoggerFactory.getLogger(TrayFactory.class);

    /** Startup delay tolerated before giving up on the tray. */
    private static final long TRAY_TIMEOUT_SECONDS = 5;

    private TrayFactory() {}

    /**
     * Builds the tray on a worker thread. Returns the tray, or null when it
     * fails or takes longer than the timeout — callers continue headless.
     */
    public static TrayManager createOrFallback(Supplier<TrayManager> builder, long timeoutSeconds) {
        TrayManager[] holder = new TrayManager[1];
        Throwable[] failure = new Throwable[1];
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                holder[0] = builder.get();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        }, "tray-init");
        worker.setDaemon(true);
        worker.start();

        try {
            if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warn("Tray did not initialize within {}s, continuing without it", timeoutSeconds);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (failure[0] != null) {
            logger.warn("System tray unavailable, continuing without tray: {}", String.valueOf(failure[0].getMessage()));
            return null;
        }
        return holder[0];
    }

    /**
     * Convenience overload with the default timeout.
     */
    public static TrayManager createOrFallback(int port, String directory, LogCapture logCapture) {
        return createOrFallback(() -> new TrayManager(port, directory, logCapture), TRAY_TIMEOUT_SECONDS);
    }

    /** Teardown with the same non-blocking guarantee: never waits longer than the timeout. */
    public static void shutdownQuietly(TrayManager trayManager, long timeoutSeconds) {
        if (trayManager == null) {
            return;
        }
        Thread worker = new Thread(trayManager::shutdown, "tray-shutdown");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(timeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
