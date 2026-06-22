package io.pskenny.pkspkms;

import io.pskenny.pkspkms.desktop.LogCapture;
import io.pskenny.pkspkms.desktop.TrayManager;
import io.pskenny.pkspkms.repo.SQLitePksFileRepository;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Subparsers;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;

public final class Application {
    private final Logger logger = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        try {
            new Application(args);
        } catch (IllegalArgumentException e) {
            System.exit(1);
        } catch (IOException | SQLException e) {
            LoggerFactory.getLogger(Application.class).error("Runtime error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    public Application(String[] args) throws IOException, SQLException {
        Namespace ns = parseArguments(args);

        String command = ns.getString("command");
        switch(command) {
            case "server":
                String directory = ns.getString("directory");
                int port = ns.getInt("port");
                String dbPath = ns.getString("db");
                boolean tray = ns.getBoolean("tray");

                TrayManager trayManager = null;
                LogCapture logCapture = null;
                CountDownLatch latch = null;

                if (tray) {
                    logCapture = new LogCapture();
                    try {
                        trayManager = new TrayManager(port, directory, logCapture);
                        latch = new CountDownLatch(1);
                        trayManager.setOnQuit(latch::countDown);
                        trayManager.setStatus("Loading vault...");
                    } catch (Exception | Error e) {
                        logger.warn("System tray unavailable, continuing without tray: {}", e.getMessage());
                        if (trayManager != null) {
                            trayManager.shutdown();
                        }
                        trayManager = null;
                        logCapture.restore();
                        logCapture = null;
                    }
                }

                SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + dbPath);
                Server server = new Server(port, repository, directory);
                server.loadRepo(directory);
                server.start();
                logger.info("Server started on http://localhost:{}", port);

                if (trayManager != null) {
                    trayManager.setStatus("Running");
                    trayManager.setLastEvent("Server started on port " + port);
                }

                try {
                    if (latch != null) {
                        latch.await();
                    } else {
                        while (server.wasStarted()) {
                            Thread.sleep(1000);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("Server interrupted, shutting down");
                } finally {
                    if (trayManager != null) {
                        trayManager.shutdown();
                    }
                    if (logCapture != null) {
                        logCapture.restore();
                    }
                    server.stop();
                }
                break;
            case "export":
                Export.ExportConfig exportConfig = new Export.ExportConfig(
                        ns.getString("directory"),
                        ns.getString("db"),
                        ns.getString("query"),
                        ns.getString("output"),
                        ns.getString("type"),
                        ns.getString("options"),
                        ns.getBoolean("dryrun"),
                        ns.getBoolean("load")
                    );

                SQLitePksFileRepository exportRepo = new SQLitePksFileRepository("jdbc:sqlite:" + exportConfig.dbPath());
                exportRepo.loadDirectoryIntoRepository(exportConfig.directory());
                new Export(exportConfig, exportRepo).export();
                exportRepo.close();
                break;
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private Namespace parseArguments(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("pkspkms").build()
                .description("PKSPKMS program");

        Subparsers subparsers = parser.addSubparsers()
                .dest("command");
        addServerSubparser(subparsers);
        addExportSubparser(subparsers);

        Namespace ns = null;
        try {
            ns = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            throw new IllegalArgumentException("Couldn't parse input: " + e.getMessage(), e);
        }
        return ns;
    }

    private void addServerSubparser(Subparsers subparsers) {
        ArgumentParser serverParser = subparsers.addParser("server")
                .help("Run the pkspkms server");
        serverParser.addArgument("--port")
                .type(Integer.class)
                .setDefault(3000)
                .help("Port number for the server");
        serverParser.addArgument("--directory")
                .type(String.class)
                .required(true)
                .help("Directory to serve");
        serverParser.addArgument("--db")
                .type(String.class)
                .setDefault("pkspkms.db")
                .help("Path to the SQLite database file");
        serverParser.addArgument("--tray")
                .action(Arguments.storeTrue())
                .setDefault(Boolean.FALSE)
                .help("Show a system tray icon (desktop environments only)");
    }

    private void addExportSubparser(Subparsers subparsers) {
        ArgumentParser exportParser = subparsers.addParser("export")
                .help("Export PKSPKMS data");
        exportParser.addArgument("--directory")
                .type(String.class)
                .required(true)
                .help("Source directory");
        exportParser.addArgument("--db")
                .type(String.class)
                .setDefault("pkspkms.db")
                .help("Path to the SQLite database file");
        exportParser.addArgument("--query")
                .type(String.class)
                .setDefault("")
                .help("Query string for export");
        exportParser.addArgument("--output")
                .type(String.class)
                .required(true)
                .help("Output directory path");
        exportParser.addArgument("--type")
                .type(String.class)
                .choices("markdown", "copy")
                .required(true)
                .help("Type of export (markdown or copy)");
        exportParser.addArgument("--options")
                .type(String.class)
                .help("Comma-separated options (e.g. \"copyLinkedFiles,other\" )");
        exportParser.addArgument("--dryrun")
                .type(Boolean.class)
                .required(false)
                .action(Arguments.storeTrue())
                .setDefault(Boolean.FALSE)
                .help("Don't write any changes to disk.");
        exportParser.addArgument("--load")
                .action(Arguments.storeTrue())
                .setDefault(Boolean.FALSE)
                .help("Load data from previously saved (serialised) files instead of regenerating.");
    }
}
