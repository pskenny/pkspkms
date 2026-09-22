package io.pskenny.pkspkms.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.fs.ObsidianIgnores;
import io.pskenny.pkspkms.io.fs.PkmsEntry;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.processor.Processors;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

public final class RepositoryFileLoader {
    private final SqliteRepository repository;
    private final PkmsFileSystem fs;
    private final Processors processors;
    private final String alias;

    public RepositoryFileLoader(SqliteRepository repository, PkmsFileSystem fs, String alias) {
        this.repository = repository;
        this.fs = fs;
        this.processors = new Processors();
        this.alias = alias;
    }

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RepositoryFileLoader.class);

    // Parse/hash has no shared mutable state (per-call Yaml/Blake3/regex instances);
    // DB phases below stay serial on the single-writer connection. 2x cores covers
    // CPU-bound hashing and I/O-bound vaults alike.
    private static final int PARSE_THREADS =
            Math.min(2 * Runtime.getRuntime().availableProcessors(), 16);

    // System dirs are never indexed (.pkspkms-cache/.pkspkms-cache hold the
    // plugin's cached copies, dot-named and plain); everything else follows
    // Obsidian's Excluded files list
    private static final List<String> ALWAYS_EXCLUDED =
            List.of(".trash", ".obsidian", ".pkspkms-cache", "pkspkms-cache");

    public void load() throws IOException, SQLException {
        // 1. Walk the filesystem to collect all candidate file entries.
        // Defensive copy: fs.listFiles may return an immutable list.
        List<PkmsEntry> diskEntries = new ArrayList<>(fs.listFiles(ALWAYS_EXCLUDED));
        Predicate<String> ignores = ObsidianIgnores.from(fs);
        diskEntries.removeIf(entry -> ignores.test(entry.relativePath()));
        logger.debug("Walked {} files (excluding: {} + userIgnoreFilters)", diskEntries.size(), ALWAYS_EXCLUDED);

        // 2. Load all existing file paths and their last modified times from the database
        java.util.Map<String, Long> dbModifiedTimes = new java.util.HashMap<>();
        String querySql = "SELECT file_path, file_last_modified FROM FILES";
        try (java.sql.Statement stmt = repository.getConnection().createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(querySql)) {
            while (rs.next()) {
                String path = rs.getString("file_path");
                long mtime = rs.getLong("file_last_modified");
                if (path != null) {
                    dbModifiedTimes.put(path, mtime);
                }
            }
        }

        // 3. Determine new/modified files to parse, and active paths in current vault
        String prefix = (alias != null && !alias.isEmpty()) ? "@" + alias + "/" : "";
        java.util.List<PkmsEntry> filesToParse = new java.util.ArrayList<>();
        java.util.Set<String> activeDbPaths = new java.util.HashSet<>();

        for (PkmsEntry entry : diskEntries) {
            String relativeStr = entry.relativePath();
            String dbPath = prefix + relativeStr;
            activeDbPaths.add(dbPath);

            Long dbModified = dbModifiedTimes.get(dbPath);
            long diskModified = entry.lastModified();

            if (dbModified == null || dbModified != diskModified) {
                filesToParse.add(entry);
            }
        }

        // 4. Determine and remove any deleted files belonging to the current vault
        java.util.List<String> deletedDbPaths = new java.util.ArrayList<>();
        for (String dbPath : dbModifiedTimes.keySet()) {
            boolean belongsToCurrentVault = (alias != null && !alias.isEmpty())
                    ? dbPath.startsWith(prefix)
                    : !dbPath.startsWith("@");

            if (belongsToCurrentVault && !activeDbPaths.contains(dbPath)) {
                deletedDbPaths.add(dbPath);
            }
        }

        if (!deletedDbPaths.isEmpty()) {
            Connection conn = repository.getConnection();
            conn.setAutoCommit(false);
            String delEmbeds = "DELETE FROM EMBEDS WHERE file_id IN (SELECT id FROM FILES WHERE file_path = ?)";
            String delLinksSrc = "DELETE FROM LINKS WHERE source_file_id IN (SELECT id FROM FILES WHERE file_path = ?)";
            String delLinksTgt = "DELETE FROM LINKS WHERE target_file_id IN (SELECT id FROM FILES WHERE file_path = ?)";
            String delFile = "DELETE FROM FILES WHERE file_path = ?";

            try (java.sql.PreparedStatement psEmbeds = conn.prepareStatement(delEmbeds);
                 java.sql.PreparedStatement psLinksSrc = conn.prepareStatement(delLinksSrc);
                 java.sql.PreparedStatement psLinksTgt = conn.prepareStatement(delLinksTgt);
                 java.sql.PreparedStatement psFile = conn.prepareStatement(delFile)) {

                for (String path : deletedDbPaths) {
                    psEmbeds.setString(1, path);
                    psEmbeds.addBatch();

                    psLinksSrc.setString(1, path);
                    psLinksSrc.addBatch();

                    psLinksTgt.setString(1, path);
                    psLinksTgt.addBatch();

                    psFile.setString(1, path);
                    psFile.addBatch();
                }

                psEmbeds.executeBatch();
                psLinksSrc.executeBatch();
                psLinksTgt.executeBatch();
                psFile.executeBatch();

                conn.commit();
                logger.info("Removed {} deleted files from database", deletedDbPaths.size());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        logger.info("{} new/modified, {} unchanged, {} deleted",
                filesToParse.size(), diskEntries.size() - filesToParse.size(), deletedDbPaths.size());

        // 5. If there are no new or modified files, we are completely done!
        if (filesToParse.isEmpty()) {
            return;
        }

        // 6. Parse and hash the modified/new files in parallel
        List<PksFile> pksFiles = new ArrayList<>(filesToParse.size());
        long parseStartNs = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(PARSE_THREADS);
        try {
            List<Future<PksFile>> futures = new ArrayList<>(filesToParse.size());
            for (PkmsEntry entry : filesToParse) {
                futures.add(pool.submit(() -> processors.initialReadOnlyParse(entry, fs)));
            }
            for (Future<PksFile> future : futures) {
                try {
                    PksFile pksFile = future.get();
                    if (pksFile != null) {
                        pksFiles.add(pksFile);
                    }
                } catch (ExecutionException e) {
                    logger.error("Parse task failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while parsing", e);
                }
            }
        } finally {
            pool.shutdown();
        }
        logger.info("Parsed {} files in {}ms ({} threads)",
                pksFiles.size(), (System.nanoTime() - parseStartNs) / 1_000_000, PARSE_THREADS);

        if (alias != null && !alias.isEmpty()) {
            List<PksFile> prefixedFiles = new java.util.ArrayList<>();
            for (PksFile pksFile : pksFiles) {
                String newFilePath = prefix + pksFile.getFilePath();
                PksFile prefixedFile = new PksFile(pksFile, newFilePath);
                prefixedFiles.add(prefixedFile);
            }
            pksFiles = prefixedFiles;
        }

        insertFiles(pksFiles, alias);
        resolveWikilinks(pksFiles);
        updatePropertiesAndLinks(pksFiles, alias);
    }

    private void insertFiles(List<PksFile> pksFiles, String alias) throws SQLException {
        Connection initConn = repository.getConnection();
        initConn.setAutoCommit(false);
        String upsertSql = "INSERT OR IGNORE INTO FILES (file_path, file_name, file_name_ext, vault_alias_id, blake3) VALUES (?, ?, ?, ?, ?)";
        try (java.sql.PreparedStatement pstmt = initConn.prepareStatement(upsertSql)) {
            Integer vaultAliasId = null;
            if (alias != null && !alias.isEmpty()) {
                vaultAliasId = getVaultAliasId(alias);
            }

            for (PksFile file : pksFiles) {
                String filePath = file.getFilePath();
                if (filePath == null) continue;

                // Derive file name and extension
                String fileNameExt = filePath;
                int lastSlash = filePath.lastIndexOf('/');
                if (lastSlash != -1) {
                    fileNameExt = filePath.substring(lastSlash + 1);
                }
                String fileName = fileNameExt;
                int lastDot = fileNameExt.lastIndexOf('.');
                if (lastDot != -1) {
                    fileName = fileNameExt.substring(0, lastDot);
                }

                pstmt.setString(1, filePath);
                pstmt.setString(2, fileName);
                pstmt.setString(3, fileNameExt);

                if (vaultAliasId != null) {
                    pstmt.setInt(4, vaultAliasId);
                } else {
                    pstmt.setNull(4, java.sql.Types.INTEGER);
                }

                String hash = file.getHash();
                if (hash != null) {
                    pstmt.setString(5, hash);
                } else {
                    pstmt.setNull(5, java.sql.Types.VARCHAR);
                }

                pstmt.addBatch();
            }
            pstmt.executeBatch();
            initConn.commit();
        } catch (SQLException e) {
            initConn.rollback();
            throw e;
        } finally {
            initConn.setAutoCommit(true);
        }
    }

    private Integer getVaultAliasId(String alias) throws SQLException {
        // TODO hit a cache
        String sql = "SELECT id FROM VAULT_ALIASES WHERE alias = ?";
        try (java.sql.PreparedStatement pstmt = repository.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, alias);
            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        }
        return null;
    }

    private void resolveWikilinks(List<PksFile> pksFiles) throws SQLException {
        java.util.Map<String, String> filePathMap = new java.util.HashMap<>();
        java.util.Map<String, String> fileNameExtMap = new java.util.HashMap<>();
        java.util.Map<String, String> fileNameMap = new java.util.HashMap<>();

        // A. Populate from already-existing files in the database in a single roundtrip
        String sql = "SELECT file_path, file_name_ext, file_name FROM FILES";
        try (java.sql.Statement stmt = repository.getConnection().createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String path = rs.getString("file_path");
                String ext = rs.getString("file_name_ext");
                String name = rs.getString("file_name");
                if (path != null) filePathMap.put(path, path);
                if (ext != null) fileNameExtMap.put(ext, path);
                if (name != null) fileNameMap.put(name, path);
            }
        }

        // B. Merge with newly parsed files in memory
        for (PksFile file : pksFiles) {
            String path = file.getFilePath();
            if (path == null) continue;

            String nameExt = path;
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash != -1) {
                nameExt = path.substring(lastSlash + 1);
            }
            String name = nameExt;
            int lastDot = nameExt.lastIndexOf('.');
            if (lastDot != -1) {
                name = nameExt.substring(0, lastDot);
            }

            filePathMap.put(path, path);
            fileNameExtMap.put(nameExt, path);
            fileNameMap.put(name, path);
        }

        // C. Perform lightning-fast in-memory resolutions
        for (PksFile pksFile : pksFiles) {
            List<String> resolved = new java.util.ArrayList<>();
            for (Object obj : pksFile.getAsList("wikilinks")) {
                if (obj == null) continue;
                String wikilink = obj.toString();
                String cleanLink = wikilink.split("\\|")[0]   // drop display alias
                        .split("#")[0]                        // drop heading/block ref
                        .replace("[[", "")
                        .replace("]]", "");

                String target = filePathMap.get(cleanLink);
                if (target == null) target = fileNameExtMap.get(cleanLink);
                if (target == null) target = fileNameMap.get(cleanLink);

                if (target != null) {
                    resolved.add(target);
                }
            }

            List<String> distinctResolved = resolved.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (distinctResolved.isEmpty()) {
                continue;
            }
            pksFile.addToProperty("links", distinctResolved);
        }
    }

    private void updatePropertiesAndLinks(List<PksFile> pksFiles, String alias) throws SQLException {
        Connection conn = repository.getConnection();
        conn.setAutoCommit(false);

        // A. Load all path -> id mappings in one query
        java.util.Map<String, Integer> pathIdMap = new java.util.HashMap<>();
        String selectSql = "SELECT id, file_path FROM FILES";
        try (java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(selectSql)) {
            while (rs.next()) {
                pathIdMap.put(rs.getString("file_path"), rs.getInt("id"));
            }
        }

        String updateSql = "UPDATE FILES SET properties = ?, blake3 = ?, file_last_modified = ? WHERE id = ?";

        try (java.sql.PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            for (PksFile pksFile : pksFiles) {
                Integer id = pathIdMap.get(pksFile.getFilePath());
                if (id == null) continue;

                // Deduplicate links before persisting so JSON properties and LINKS table stay consistent
                List<String> links = pksFile.<String>getAsList("links").stream().distinct().toList();
                if (!links.isEmpty()) {
                    if (alias != null && !alias.isEmpty()) {
                        String prefix = "@" + alias + "/";
                        links = links.stream()
                                .map(link -> link.contains("/") ? link : prefix + link)
                                .toList();
                        pksFile.getMutableProperties().put("links", links);
                    } else {
                        pksFile.getMutableProperties().put("links", links);
                    }
                }

                String propertiesJson;
                try {
                    propertiesJson = repository.getJsonMapper().writeValueAsString(pksFile.getMutableProperties());
                } catch (JsonProcessingException e) {
                    // Fail fast: persisting "{}" would silently falsify this file's properties
                    throw new SQLException("Failed to serialize properties for: " + pksFile.getFilePath(), e);
                }

                updateStmt.setString(1, propertiesJson);
                if (pksFile.getHash() != null) {
                    updateStmt.setString(2, pksFile.getHash());
                } else {
                    updateStmt.setNull(2, java.sql.Types.VARCHAR);
                }
                updateStmt.setLong(3, pksFile.getLastModified());
                updateStmt.setInt(4, id);
                updateStmt.addBatch();
            }
            updateStmt.executeBatch();

            // B. Batch set links and embeds
            for (PksFile pksFile : pksFiles) {
                Integer id = pathIdMap.get(pksFile.getFilePath());
                if (id == null) continue;

                List<String> links = pksFile.<String>getAsList("links").stream().distinct().toList();
                if (!links.isEmpty()) {
                    repository.setLinks(id, links, "outgoing");
                    repository.setLinks(id, links, "wikilink");
                }

                repository.addEmbedsToTable(id, pksFile, "bases", "base");
                repository.addEmbedsToTable(id, pksFile, "luabases", "luabase");
                repository.addEmbedsToTable(id, pksFile, "embeds", "embed");
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }
}