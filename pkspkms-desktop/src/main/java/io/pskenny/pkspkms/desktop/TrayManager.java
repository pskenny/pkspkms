package io.pskenny.pkspkms.desktop;

import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Manages the dorkbox system tray icon and context menu for PKSPKMS.
 * Does not open any Swing/AWT windows — only the tray icon and its menu.
 */
public final class TrayManager {
    private static final Logger logger = LoggerFactory.getLogger(TrayManager.class);

    private final SystemTray systemTray;
    private final int port;
    private final LogCapture logCapture;

    private MenuItem statusItem;
    private MenuItem portItem;
    private MenuItem vaultItem;
    private MenuItem lastItem;

    public TrayManager(int port, String directory, LogCapture logCapture) {
        this.port = port;
        this.logCapture = logCapture;
        SystemTray.DEBUG = false;
        this.systemTray = SystemTray.get();
        if (systemTray == null) {
            throw new IllegalStateException("System tray is not supported on this platform");
        }
        init(directory);
    }

    private void init(String directory) {
        try (InputStream is = getClass().getResourceAsStream("/brain-icon.png")) {
            if (is != null) {
                systemTray.setImage(is);
            } else {
                logger.warn("Tray icon /brain-icon.png not found on classpath");
            }
        } catch (IOException e) {
            logger.warn("Could not load tray icon", e);
        }

        systemTray.setTooltip("PKSPKMS");

        // Disabled info items
        addDisabled("PKSPKMS");
        statusItem = addDisabled("Status: Starting...");
        portItem = addDisabled("Port: " + port);
        vaultItem = addDisabled("Vault: " + truncate(directory, 40));
        lastItem = addDisabled("Last: Initialising...");
        addSeparator();

        // Enabled actions
        MenuItem openItem = new MenuItem("Open in Browser", e -> openBrowser());
        systemTray.getMenu().add(openItem);
        addSeparator();

        // Quit is wired by caller via shutdown hook, but we provide a visual placeholder
        // that gets enabled when the caller supplies the callback.
    }

    public void setOnQuit(Runnable onQuit) {
        systemTray.getMenu().add(new MenuItem("Quit", e -> {
            logger.info("Tray Quit selected");
            onQuit.run();
        }));
    }

    public void setStatus(String status) {
        if (statusItem != null) {
            statusItem.setText("Status: " + status);
        }
    }

    public void setLastEvent(String event) {
        if (lastItem != null) {
            lastItem.setText("Last: " + truncate(event, 55));
        }
    }

    public void refreshLastFromLog() {
        if (logCapture != null && lastItem != null) {
            lastItem.setText("Last: " + truncate(logCapture.getLastLine(), 55));
        }
    }

    public void shutdown() {
        if (systemTray != null) {
            systemTray.shutdown();
        }
    }

    private MenuItem addDisabled(String label) {
        MenuItem item = new MenuItem(label);
        item.setEnabled(false);
        systemTray.getMenu().add(item);
        return item;
    }

    private void addSeparator() {
        try {
            systemTray.getMenu().add(new dorkbox.systemTray.Separator());
        } catch (Exception | Error e) {
            // Fallback: use a disabled item as a visual separator
            addDisabled("---");
        }
    }

    private void openBrowser() {
        String url = "http://localhost:" + port + "/webui";
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                logger.warn("Desktop browse action not supported; open {} manually", url);
            }
        } catch (Exception e) {
            logger.error("Could not open browser: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
