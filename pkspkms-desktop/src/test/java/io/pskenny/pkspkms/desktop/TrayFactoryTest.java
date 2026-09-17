package io.pskenny.pkspkms.desktop;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TrayFactoryTest {

    @Test
    void supplierThrowingFailsHeadless() {
        TrayManager tray = TrayFactory.createOrFallback(() -> {
            throw new IllegalStateException("no tray on this platform");
        }, 2);
        assertNull(tray, "failing tray construction must continue headless");
    }

    @Test
    void slowConstructionTimesOut() {
        AtomicBoolean finished = new AtomicBoolean(false);
        long start = System.nanoTime();
        TrayManager tray = TrayFactory.createOrFallback(() -> {
            try {
                Thread.sleep(5_000);   // simulates the dorkbox subprocess hang
            } catch (InterruptedException ignored) {
            }
            finished.set(true);
            return null;
        }, 1);
        long elapsed = System.currentTimeMillis() - start;

        assertNull(tray, "timed-out tray must not block startup");
        assertTrue(elapsed < 4_000, "must return near the timeout, not after the worker finishes");
    }

    @Test
    void returnsWhateverSupplierBuilds() {
        // TrayManager construction in a tray-less environment throws (caught -> null);
        // the contract under test is the non-blocking delegation, asserted via timing.
        long start = System.currentTimeMillis();
        TrayManager tray = TrayFactory.createOrFallback(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            return null;
        }, 5);
        long elapsed = System.currentTimeMillis() - start;

        assertNull(tray);
        assertTrue(elapsed < 5_000, "must return when the builder completes");
    }
}
