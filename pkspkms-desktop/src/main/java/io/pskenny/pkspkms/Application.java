/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of PKSPKMS.
 * Copyright (C) 2026 Paul Kenny
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.pskenny.pkspkms;

import fi.iki.elonen.NanoHTTPD;
import io.pskenny.pkspkms.desktop.BrokenPipeLogFilter;
import io.pskenny.pkspkms.desktop.LogCapture;
import io.pskenny.pkspkms.desktop.TrayFactory;
import io.pskenny.pkspkms.desktop.TrayManager;
import io.pskenny.pkspkms.io.feed.FeedCollectionFileSystem;
import io.pskenny.pkspkms.io.feed.FeedFetcher;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class Application {
    private final Logger logger = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        System.exit(run(args));
    }

    // Exit-code surface for tests: no args / unknown command / bad query return
    // 1 with a clean log line instead of a stack trace (BUGS.md B18). Do not
    // call with a functional `server` argument list — the server branch blocks.
    static int run(String[] args) {
        try {
            new Application(args);
            return 0;
        } catch (IllegalArgumentException | io.pskenny.pkspkms.repo.query.QueryParseException e) {
            LoggerFactory.getLogger(Application.class).error("Bad arguments: {}", e.getMessage());
            return 1;
        } catch (io.pskenny.pkspkms.repo.RepositoryException e) {
            LoggerFactory.getLogger(Application.class).error("Startup failed: {}", io.pskenny.pkspkms.io.LogUtil.oneLine(e.getMessage()));
            return 1;
        } catch (IOException e) {
            LoggerFactory.getLogger(Application.class).error("Runtime error, could not start PKSPKMS: {}",
                    io.pskenny.pkspkms.io.LogUtil.oneLine(e.getMessage()), e);
            return 1;
        }
    }

    public Application(String[] args) throws IOException {
        Namespace ns = parseArguments(args);
        String command = ns.getString("command");
        if (command == null) {
            throw new IllegalArgumentException("No command given — try 'server' or 'export'. Run with --help for usage.");
        }
        switch(command) {
            // start server
            case "server":
                String directory = ns.getString("directory");
                int port = ns.getInt("port");
                String dbPath = ns.getString("db");
                String bind = ns.getString("bind");
                boolean tray = ns.getBoolean("tray");
                boolean resetDb = ns.getBoolean("reset_db");

                // Bearer auth is opt-in (Option A): --enable-auth is the sole
                // switch; --token without it is a usage error, never silently
                // ignored (that server must not pretend it is protected).
                boolean enableAuth = Boolean.TRUE.equals(ns.getBoolean("enable_auth"));
                if (ns.getString("token") != null && !enableAuth) {
                    throw new IllegalArgumentException("--token requires --enable-auth (auth is opt-in)");
                }

                // CORS allow-list: default closed. Wildcard without auth =
                // every web page reads the vault — rejected at parse time.
                java.util.List<String> corsList = ns.get("cors_origin");
                boolean corsWildcard = corsList != null && corsList.contains("*");
                if (corsWildcard && !enableAuth) {
                    throw new IllegalArgumentException("--cors-origin * requires --enable-auth (any web page would read the vault)");
                }
                java.util.Set<String> corsOrigins = (corsList == null)
                        ? java.util.Set.of()
                        : java.util.Set.copyOf(corsList);

                String apiToken = resolveApiToken(enableAuth, ns.getString("token"), ns.getString("token_file"));
                if (enableAuth) {
                    logger.info("API token written to {}", ns.getString("token_file"));
                } else {
                    logger.info("Auth disabled — bearer token not required (loopback-only server)");
                }

                TrayManager trayManager = null;
                LogCapture logCapture = null;
                CountDownLatch latch = null;

                // setup tray — non-blocking: falls back headless if init fails or stalls
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

                //  Load main directory. Registrar = null: the legacy `lua_eval`
                // SQL function has no runtime caller (Tier 4 collapse) — embed
                // filters evaluate in-process through LuaBaseProcessor.
                PkmsFileSystem vaultFs = new JavaFileSystem(new File(directory));
                SQLitePksFileRepository repository = new SQLitePksFileRepository(
                        "jdbc:sqlite:" + dbPath,
                        null,
                        vaultFs,
                        resetDb);
                Server server = new Server(bind, port, apiToken, corsOrigins, repository);
                BrokenPipeLogFilter.install();
                server.loadRepo();

                // Load virtual friends — per-mount try/catch: one broken vault
                // logs and skips so it cannot take down the whole startup
                List<String> virtualVaults = ns.get("virtual_vault");
                if (virtualVaults != null) {
                    for (String spec : virtualVaults) {
                        try {
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
                            logger.info("Loading virtual vault: @{} -> {}", alias, io.pskenny.pkspkms.io.LogUtil.oneLine(path));
                            PkmsFileSystem aliasFs = new JavaFileSystem(new File(path));
                            server.loadVirtualVault(aliasFs, alias);
                        } catch (IllegalArgumentException | io.pskenny.pkspkms.repo.RepositoryException e) {
                            logger.error("Failed to mount vault '{}', skipping: {}", io.pskenny.pkspkms.io.LogUtil.oneLine(spec), e.getMessage());
                        }
                    }
                }

                // OPML vaults (outliner + xmlUrl subscriptions) and feed vaults
                // (RSS/Atom/podcast) — read-only peers; a dead feed logs and
                // continues so it can never block startup
                // OPML vaults (outliner + xmlUrl subscriptions) and feed vaults
                // (RSS/Atom/podcast) — read-only peers. Sources group by alias:
                // repeated entries with the same alias share one namespace and
                // a dead source warns and skips instead of killing the vault.
                mountSyndicationVaults(ns, server);

                // Embeds render once, over the full corpus of every mounted vault
                server.processEmbeds();

                // Socket read timeout: matches the ServerTest-tested path and
                // keeps slowloris-style clients from stalling request threads
                // (BUGS.md B23)
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
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
                Export.ExportMode mode = Export.ExportMode.valueOf(ns.getString("type").toUpperCase());
                Export.ExportConfig exportConfig = new Export.ExportConfig(
                        ns.getString("directory"),
                        ns.getString("db"),
                        ns.getString("query"),
                        ns.getString("output"),
                        mode,
                        ns.getBoolean("dryrun")
                    );

                PkmsFileSystem exportInputFs = new JavaFileSystem(new File(exportConfig.directory()));
                PkmsFileSystem exportOutputFs = new JavaFileSystem(new File(exportConfig.output()));
                SQLitePksFileRepository exportRepo = new SQLitePksFileRepository("jdbc:sqlite:" + exportConfig.dbPath(), null, exportInputFs, ns.getBoolean("reset_db"));
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
                    SQLitePksFileRepository addRepo = new SQLitePksFileRepository("jdbc:sqlite:" + addDbPath, null, addVaultFs);
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

    // Token bootstrap: `--enable-auth` is the sole auth switch (default off —
    // the server stays loopback-only). When on, explicit --token wins
    // (scripted use, no file touched); otherwise a fresh random token is
    // generated at EVERY start and written 0600 to the token file — a leaked
    // token dies at the next restart. When off nothing is generated or
    // written, and --token alone is rejected as a usage error.
    static String resolveApiToken(boolean enableAuth, String tokenFlag, String tokenFileFlag) throws IOException {
        if (!enableAuth) {
            return null;
        }
        return resolveToken(tokenFlag, tokenFileFlag);
    }

    static String resolveToken(String tokenFlag, String tokenFileFlag) throws IOException {
        if (tokenFlag != null && !tokenFlag.isEmpty()) {
            return tokenFlag;
        }

        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = java.util.HexFormat.of().formatHex(bytes);

        java.nio.file.Path file = java.nio.file.Path.of(tokenFileFlag);
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, token + "\n");
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            java.nio.file.Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        return token;
    }

    // Mounts the --opml-vault/--feed-vault entries. Sources group by alias
    // ('alias:<url-or-file>' — repeatable), and each source fetches through
    // FeedCollectionFileSystem: per-source failures warn and skip, so one
    // dead feed never costs the whole alias. Startup never blocks on a feed.
    private void mountSyndicationVaults(Namespace ns, Server server) {
        Map<String, List<String>> byAlias = new LinkedHashMap<>();
        collectSources(ns, "opml_vault", byAlias);
        collectSources(ns, "feed_vault", byAlias);

        for (Map.Entry<String, List<String>> entry : byAlias.entrySet()) {
            String alias = entry.getKey();
            try {
                FeedCollectionFileSystem vaultFs = new FeedCollectionFileSystem(entry.getValue(), FeedFetcher.loader());
                if (vaultFs.isEmpty()) {
                    logger.error("No sources mounted for vault '@{}', skipping", alias);
                    continue;
                }

                server.loadVirtualVault(vaultFs, alias);
                logger.info("Mounted syndication vault: @{} -> {}", alias, entry.getValue());
            } catch (IOException | IllegalArgumentException | io.pskenny.pkspkms.repo.RepositoryException e) {
                logger.error("Failed to mount vault '@{}', skipping: {}", alias, e.getMessage());
            }
        }
    }

    private void collectSources(Namespace ns, String flag, Map<String, List<String>> byAlias) {
        List<String> specs = ns.get(flag);
        if (specs == null) {
            return;
        }

        for (String spec : specs) {
            String[] parts = spec.split(":", 2);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                logger.error("Invalid vault format '{}', skipping. Expected: alias:<source>", io.pskenny.pkspkms.io.LogUtil.oneLine(spec));
                continue;
            }
            byAlias.computeIfAbsent(parts[0], key -> new ArrayList<>()).add(parts[1]);
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
        serverParser.addArgument("--bind")
                .type(String.class)
                .setDefault("127.0.0.1")
                .help("Address to bind (default 127.0.0.1 — loopback only)");
        serverParser.addArgument("--cors-origin")
                .action(Arguments.append())
                .type(String.class)
                .help("Repeat. Cross-origin allow-list (e.g. moz-extension://UUID). '*' requires --enable-auth");
        serverParser.addArgument("--enable-auth")
                .action(Arguments.storeTrue())
                .setDefault(Boolean.FALSE)
                .help("Require bearer-token auth on every route except /ping and /webui (default: off — the server stays loopback-only)");
        serverParser.addArgument("--token")
                .type(String.class)
                .help("API token (argv — visible in ps; prefer --token-file). Requires --enable-auth");
        serverParser.addArgument("--token-file")
                .type(String.class)
                .setDefault(java.nio.file.Path.of(System.getProperty("user.home"), ".config", "pkspkms", "token").toString())
                .help("Where the fresh per-start API token is written (0600)");
        serverParser.addArgument("--reset-db")
                .action(Arguments.storeTrue())
                .setDefault(Boolean.FALSE)
                .help("Drop and rebuild the index from scratch (forces a full rescan)");
        serverParser.addArgument("--virtual-vault")
                .type(String.class)
                .action(Arguments.append())
                .help("Virtual vault in 'alias:/path/to/vault' format (repeatable)");
        serverParser.addArgument("--opml-vault")
                .type(String.class)
                .action(Arguments.append())
                .help("Virtual vault from an OPML file in 'alias:/path/file.opml' format (repeatable)");
        serverParser.addArgument("--feed-vault")
                .type(String.class)
                .action(Arguments.append())
                .help("Virtual vault from a feed in 'alias:<url-or-file>' format (repeatable)");
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
        exportParser.addArgument("--reset-db")
                .action(Arguments.storeTrue())
                .setDefault(Boolean.FALSE)
                .help("Drop and rebuild the index from scratch (forces a full rescan)");
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
