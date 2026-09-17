package io.pskenny.pkspkms;

import io.pskenny.pkspkms.desktop.LogCapture;
import io.pskenny.pkspkms.desktop.TrayFactory;
import io.pskenny.pkspkms.desktop.TrayManager;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
import io.pskenny.pkspkms.repo.sqlite.SQLiteLuaConnector;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Subparsers;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class Application {
    private final Logger logger = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        try {
            new Application(args);
        } catch (IllegalArgumentException | io.pskenny.pkspkms.repo.query.QueryParseException e) {
            LoggerFactory.getLogger(Application.class).error("Bad query: {}", e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            LoggerFactory.getLogger(Application.class).error("Runtime error, could start PKSPKMS: {}",
                    e.getMessage(), e);
            System.exit(1);
        }
    }

    public Application(String[] args) throws IOException {
        Namespace ns = parseArguments(args);
        String command = ns.getString("command");
        switch(command) {
            // start server
            case "server":
                String directory = ns.getString("directory");
                int port = ns.getInt("port");
                String dbPath = ns.getString("db");
                boolean tray = ns.getBoolean("tray");

                TrayManager trayManager = null;
                LogCapture logCapture = null;
                CountDownLatch latch = null;

                // setup tray — non-blocking: falls back headless if init fails or stalls (B55)
                if (tray) {
                    logCapture = new LogCapture();
                    try {
                        trayManager = TrayFactory.createOrFallback(port, directory, logCapture);
                        if (trayManager != null) {
                            latch = new CountDownLatch(1);
                            trayManager.setOnQuit(latch::countDown);
                            trayManager.setStatus("Loading vault...");
                        }
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

                //  Load main directory
                PkmsFileSystem vaultFs = new JavaFileSystem(new File(directory));
                SQLitePksFileRepository repository = new SQLitePksFileRepository(
                        "jdbc:sqlite:" + dbPath,
                        SQLiteLuaConnector.luaFunctionRegistrar(),
                        vaultFs);
                Server server = new Server(port, repository);
                server.loadRepo();

                // Load virtual friends
                List<String> virtualVaults = ns.get("virtual_vault");
                if (virtualVaults != null) {
                    for (String spec : virtualVaults) {
                        String[] parts = spec.split(":", 2);
                        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                            throw new IllegalArgumentException("Invalid --virtual-vault format: " + spec + ". Expected: alias:/path/to/vault");
                        }
                        String alias = parts[0];
                        String path = parts[1];
                        Path vDirPath = Paths.get(path);
                        if (!Files.exists(vDirPath) || !Files.isDirectory(vDirPath)) {
                            throw new IllegalArgumentException("Directory does not exist: " + path);
                        }
                        logger.info("Loading virtual vault: @{} -> {}", alias, path);
                        PkmsFileSystem aliasFs = new JavaFileSystem(new File(path));
                        server.loadVirtualVault(aliasFs, alias);
                    }
                }

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
                    TrayFactory.shutdownQuietly(trayManager, 5);
                    if (logCapture != null) {
                        logCapture.restore();
                    }
                    server.stop();
                }
                break;
            // export command
            case "export":
                Export.ExportConfig exportConfig = new Export.ExportConfig(
                        ns.getString("directory"),
                        ns.getString("db"),
                        ns.getString("query"),
                        ns.getString("output"),
                        ns.getString("type"),
                        ns.getBoolean("dryrun")
                    );

                PkmsFileSystem exportInputFs = new JavaFileSystem(new File(exportConfig.directory()));
                PkmsFileSystem exportOutputFs = new JavaFileSystem(new File(exportConfig.output()));
                SQLitePksFileRepository exportRepo = new SQLitePksFileRepository("jdbc:sqlite:" + exportConfig.dbPath(), SQLiteLuaConnector.luaFunctionRegistrar(), exportInputFs);
                exportRepo.loadDirectoryIntoRepository();

                new Export(exportConfig, exportRepo, exportInputFs, exportOutputFs).export();
                exportRepo.close();
                break;
            case "add":
                String addCommand = ns.getString("add_command");
                if ("directory".equals(addCommand)) {
                    String addDbPath = ns.getString("add_db");
                    String addDirectory = ns.getString("add_directory");
                    String addAlias = ns.getString("add_alias");

                    if (addDirectory == null || addAlias == null) {
                        throw new IllegalArgumentException("--directory and --alias are required for 'add directory' command");
                    }

                    Path dirPath = Paths.get(addDirectory);
                    if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                        throw new IllegalArgumentException("Directory does not exist: " + addDirectory);
                    }

                    PkmsFileSystem addVaultFs = new JavaFileSystem(new File(addDirectory));
                    SQLitePksFileRepository addRepo = new SQLitePksFileRepository("jdbc:sqlite:" + addDbPath, SQLiteLuaConnector.luaFunctionRegistrar(), addVaultFs);
                    try {
                        if (addRepo.vaultAliasExists(addAlias)) {
                            throw new IllegalArgumentException("Vault alias already exists: " + addAlias);
                        }
                        addRepo.loadVirtualVault(addVaultFs, addAlias);
                        logger.info("Virtual vault added: @{} -> {}", addAlias, addDirectory);
                    } finally {
                        addRepo.close();
                    }
                } else {
                    throw new IllegalArgumentException("Unknown add command: " + addCommand);
                }
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
        addAddSubparser(subparsers);

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
        serverParser.addArgument("--virtual-vault")
                .type(String.class)
                .action(Arguments.append())
                .help("Virtual vault in 'alias:/path/to/vault' format (repeatable)");
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
        exportParser.addArgument("--dryrun")
                .type(Boolean.class)
                .required(false)
                .action(Arguments.storeTrue())
                .setDefault(Boolean.FALSE)
                .help("Don't write any changes to disk.");
    }

    private void addAddSubparser(Subparsers subparsers) {
        ArgumentParser addParser = subparsers.addParser("add")
                .help("Add a virtual vault");
        Subparsers addSubparsers = addParser.addSubparsers()
                .dest("add_command");
        
        ArgumentParser addDirParser = addSubparsers.addParser("directory")
                .help("Add a directory as a virtual vault");
        addDirParser.addArgument("--directory")
                .type(String.class)
                .required(true)
                .help("Directory to add as virtual vault");
        addDirParser.addArgument("--alias")
                .type(String.class)
                .required(true)
                .help("Alias for the virtual vault");
    }
}
