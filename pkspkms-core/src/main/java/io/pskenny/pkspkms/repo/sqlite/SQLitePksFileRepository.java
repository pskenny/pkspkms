package io.pskenny.pkspkms.repo.sqlite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.Blake3Util;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.PathUtil;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.processor.MarkdownProcessor;
import io.pskenny.pkspkms.luabase.LuaBaseProcessor;
import io.pskenny.pkspkms.luabase.NaiveBaseToLuaBaseConverter;
import io.pskenny.pkspkms.repo.RepositoryFileLoader;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.PropertyTypes;
import io.pskenny.pkspkms.repo.EmbedMode;
import io.pskenny.pkspkms.repo.RepositoryException;
import io.pskenny.pkspkms.repo.SqliteRepository;
import io.pskenny.pkspkms.repo.HygieneReports;
import io.pskenny.pkspkms.repo.VaultKind;

import io.pskenny.pkspkms.services.WikilinkFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.*;

public class SQLitePksFileRepository implements SqliteRepository {
    private static final Logger logger = LoggerFactory.getLogger(SQLitePksFileRepository.class);
    private final Connection conn;
    private final String dbPath;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    private final LuaBaseProcessor processor = new LuaBaseProcessor();
    private final NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter = new NaiveBaseToLuaBaseConverter();
    private final MarkdownProcessor markdownProcessor;

    private final SqliteEmbedProcessor sqliteEmbedProcessor;
    private final WikilinkFinder wikilinkFinder = new WikilinkFinder();

    // Broken-links sweep cache (HygieneReports compute — see hygieneCompute):
    // volatile payload swap, lock only around the sweep itself
    private static final Object hygieneLock = new Object();
    private volatile Map<String, Map<String, Object>> hygieneCache = new LinkedHashMap<>();
    private volatile boolean hygieneDirty = true;
    // Test-only observability: how many times the sweep actually ran
    long hygieneComputations = 0;
    private final io.pskenny.pkspkms.repo.query.QueryParser queryParser = new io.pskenny.pkspkms.repo.query.QueryParser();
    private final io.pskenny.pkspkms.repo.query.QueryCompiler queryCompiler = new io.pskenny.pkspkms.repo.query.QueryCompiler();
    private final PkmsFileSystem mainFs;
    private final Map<String, PkmsFileSystem> aliasFilesystems = new HashMap<>();
    private final io.pskenny.pkspkms.repo.query.PropertyTypes propertyTypes;
    private final FilesDao dao;

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    public SQLitePksFileRepository(String dbUrl,
                                   java.util.function.Consumer<java.sql.Connection> luaFunctionRegistrar,
                                   PkmsFileSystem vaultFs) {
        this(dbUrl, luaFunctionRegistrar, vaultFs, false);
    }

    // resetSchema=true is the CLI --reset-db escape hatch: drop+rebuild the
    // (otherwise versioned, persistent) index and force a full rescan.
    public SQLitePksFileRepository(String dbUrl,
                                   java.util.function.Consumer<java.sql.Connection> luaFunctionRegistrar,
                                   PkmsFileSystem vaultFs,
                                   boolean resetSchema) {
        this.mainFs = vaultFs;
        this.dbPath = dbUrl.startsWith("jdbc:sqlite:") ? dbUrl.substring("jdbc:sqlite:".length()) : null;
        try {
            this.conn = DriverManager.getConnection(dbUrl);
            SQLitePragmas.apply(this.conn);
            this.dao = new FilesDao(this.conn);
            this.propertyTypes = readPropertyTypes(vaultFs);

            this.markdownProcessor = new MarkdownProcessor();
            if (resetSchema) {
                SQLiteSchema.reset(this.conn);
            } else {
                SQLiteSchema.init(this.conn);
            }

            // Automatically index tags and type
            createPropertyIndex("tags", "array");
            createPropertyIndex("type", "text");

            if (luaFunctionRegistrar != null) {
                luaFunctionRegistrar.accept(this.conn);
            }
            this.sqliteEmbedProcessor = new SqliteEmbedProcessor(dbUrl, processor,
                    naiveBaseToLuaBaseConverter, this);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to connect to database: " + dbUrl, e);
        }
        restrictDbFilePermissions();
    }

    // Vault index metadata is sensitive: the process umask leaves the database
    // and its WAL sidecars group/world-readable, so every local user could read
    // the whole index. Restricted to the owner (POSIX filesystems only).
    private void restrictDbFilePermissions() {
        if (dbPath == null || dbPath.isEmpty() || ":memory:".equals(dbPath)
                || !java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        java.nio.file.Path path = java.nio.file.Path.of(dbPath);
        for (java.nio.file.Path candidate : java.util.List.of(
                path,
                path.resolveSibling(path.getFileName() + "-wal"),
                path.resolveSibling(path.getFileName() + "-shm"))) {
            try {
                if (java.nio.file.Files.exists(candidate)) {
                    java.nio.file.Files.setPosixFilePermissions(candidate,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                }
            } catch (IOException e) {
                logger.warn("Could not restrict DB file permissions: {}", candidate, e);
            }
        }
    }

    public void loadDirectoryIntoRepository() {
        loadDirectoryIntoRepository(EmbedMode.RENDER_NOW);
    }

    // DEFERRED is for callers that run a global embed pass later: the server
    // mounts every vault first and then calls processEmbeds() once, so
    // rendering after the main vault load would duplicate the pass.
    public void loadDirectoryIntoRepository(EmbedMode mode) {
        markHygieneStale();
        long startNs = System.nanoTime();
        try {
            new RepositoryFileLoader(this, mainFs, null).load();
            // Refresh the on-demand resolver: removed files must stop
            // resolving immediately after a load refresh
            wikilinkFinder.clear();
            if (mode == EmbedMode.RENDER_NOW) {
                sqliteEmbedProcessor.processAll();
            }
            logVaultSummary(startNs);
        } catch (IOException e) {
            throw new RepositoryException("Failed to load directory", e);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to load directory", e);
        }
    }

    // One-line post-load health check. Counts are global (FILES/LINKS/EMBEDS span all vaults).
    private void logVaultSummary(long startNs) {
        long files = countRows("SELECT COUNT(*) FROM FILES");
        long links = countRows("SELECT COUNT(*) FROM LINKS");
        long embeds = countRows("SELECT COUNT(*) FROM EMBEDS");
        long failed = countRows("SELECT COUNT(*) FROM EMBEDS WHERE generated_content = 'ERROR'");
        logger.info("Loaded vault: {} files, {} links, {} embeds ({} failed) in {}ms",
                files, links, embeds, failed, (System.nanoTime() - startNs) / 1_000_000);
    }

    private long countRows(String sql) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            logger.warn("Count query failed: {}", sql, e);
            return -1;
        }
    }

    public void loadVirtualVault(PkmsFileSystem aliasFs, String alias) {
        markHygieneStale();
        try {
            if (vaultAliasExists(alias)) {
                throw new IllegalArgumentException("Vault alias already exists: " + alias);
            }
            int aliasId = addVaultAlias(alias, "", VaultKind.VIRTUAL);
            logger.info("Loading vault {} (id {})", alias, aliasId);
            aliasFilesystems.put(alias, aliasFs);
            new RepositoryFileLoader(this, aliasFs, alias).load();
            wikilinkFinder.clear();
            // No embed rendering here: processAll is global over all vaults and
            // must run once after every vault mounts (see processEmbeds)
        } catch (IOException e) {
            throw new RepositoryException("Failed to load virtual vault: " + alias, e);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to load virtual vault: " + alias, e);
        }
    }

    // Renders base/luabase embeds across every vault. Application calls this
    // once after all vaults mount; each vault load only seeds EMBEDS rows.
    public void processEmbeds() {
        markHygieneStale();
        try {
            sqliteEmbedProcessor.processAll();
        } catch (SQLException | IOException e) {
            throw new RepositoryException("Failed to process embeds", e);
        } catch (Throwable t) {
            // Last-resort guard: embed rendering must never prevent
            // server.start() — a failed embed is data loss, not downtime 
            logger.error("Embed processing failed unexpectedly, continuing startup", t);
        }
    }

    public int addVaultAlias(String alias, String directory, VaultKind kind) {
        try {
            return dao.addVaultAlias(alias, directory, kind);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to add vault alias: " + alias, e);
        }
    }

    @Override
    public void createPropertyIndex(String propertyKey, String type) {
        // BUGS.md D3: only plain identifiers may become expression indexes —
        // the old sanitize/index-name + raw-splice mix meant a key containing
        // a quote could still break the SQL literal
        if (!propertyKey.matches("[A-Za-z0-9_]+")) {
            throw new RepositoryException("Property index keys must be [A-Za-z0-9_]+: " + propertyKey);
        }
        String indexName = propertyKey;
        // Create SQLite Expression Index on json_extract() output
        String sql = "CREATE INDEX IF NOT EXISTS idx_files_prop_" + indexName
                + " ON FILES(json_extract(properties, '$." + propertyKey + "'));";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to create property index: " + propertyKey, e);
        }
    }

    @Override
    public Map<String, Map<String, Object>> propertiesForPaths(List<String> paths) {
        try {
            return dao.propertiesForPaths(paths);
        } catch (SQLException e) {
            throw new RepositoryException("Properties lookup failed (provenance)", e);
        }
    }

    @Override
    public Map<String, Object> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        String sql = "SELECT a.alias, f.file_path, f.blake3 FROM VAULT_ALIASES a "
                + "LEFT JOIN FILES f ON f.vault_alias_id = a.id ORDER BY a.alias, f.file_path";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String alias = rs.getString("alias");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.computeIfAbsent(alias, k -> new ArrayList<>());
                String filePath = rs.getString("file_path");
                if (filePath == null) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filePath", filePath);
                entry.put("blake3", rs.getString("blake3"));
                files.add(entry);
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to build vault manifest", e);
        }
        return manifest;
    }

    /**
     * Blake3 of an indexed file, empty when the file has no FILES row (e.g.
     * cacheFile upserts) — used by export freshness checks.
     */
    @Override
    public java.util.Optional<String> blake3Of(String filePath) {
        String sql = "SELECT blake3 FROM FILES WHERE file_path = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, filePath);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String hash = rs.getString("blake3");
                    return (hash != null && !hash.isEmpty())
                            ? java.util.Optional.of(hash)
                            : java.util.Optional.empty();
                }
            }
        } catch (SQLException e) {
            logger.warn("blake3Of lookup failed for {}: {}", filePath, e.getMessage());
        }
        return java.util.Optional.empty();
    }

    @Override
    public boolean vaultAliasExists(String alias) {
        try {
            return dao.vaultAliasExists(alias);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to check vault alias: " + alias, e);
        }
    }

    public Map<String, Object> cacheFile(String address, String location, String cacheDirectory) {
        markHygieneStale();
        try {
            // Cache layout mirrors the pkspkms:// address (with @); the alias
            // itself registers bare, so the lookup strips it
            String alias = address.startsWith("@") ? address.substring(1) : address;
            String cacheRelPath = cacheDirectory + "/" + address + "/" + location;

            // Validate the alias BEFORE the exists() fast path (BUGS.md H7):
            // the fast path used to serve/hash + upsert rows for in-vault
            // files with no alias check at all.
            PkmsFileSystem aliasFs = aliasFilesystems.get(alias);
            if (aliasFs == null) {
                throw new IOException("Unknown virtual vault alias: @" + alias);
            }
            if (!aliasFs.exists(location)) {
                throw new IOException("File not found: " + address + "/" + location);
            }

            // Check if file already exists in cache — a hit skips the COPY
            // only, never the validation above
            if (!mainFs.exists(cacheRelPath)) {
                // Copy from alias fs to main fs cache
                try (InputStream in = aliasFs.openInput(location)) {
                    mainFs.copyFrom(in, cacheRelPath);
                }
            }

            // Hash the cached file
            String blake3Hash;
            try (InputStream in = mainFs.openInput(cacheRelPath)) {
                blake3Hash = Blake3Util.hashStream(in);
            }

            // On-conflict preserves the rowid: INSERT OR REPLACE would delete +
            // reinsert the row, shifting its id and orphaning EMBEDS/LINKS
            // references (and the FTS index keyed by rowid)
            String upsertSql = "INSERT INTO FILES (file_path, file_name, file_name_ext, blake3, vault_alias_id) VALUES (?, ?, ?, ?, NULL) "
                    + "ON CONFLICT(file_path) DO UPDATE SET blake3 = excluded.blake3";
            String selectSql = "SELECT id, blake3 FROM FILES WHERE file_path = ?";

            String fileNameExt = PathUtil.baseName(location);
            String fileName = fileNameExt.replaceFirst("[.][^.]+$", "");

            try (PreparedStatement pstmt = conn.prepareStatement(upsertSql)) {
                pstmt.setString(1, cacheRelPath);
                pstmt.setString(2, fileName);
                pstmt.setString(3, fileNameExt);
                pstmt.setString(4, blake3Hash);
                pstmt.executeUpdate();
            }

            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, cacheRelPath);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "success");
                        result.put("file", cacheRelPath);
                        result.put("blake3", rs.getString("blake3"));
                        return result;
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("file", cacheRelPath);
            result.put("blake3", blake3Hash);
            return result;
        } catch (IOException e) {
            throw new RepositoryException("Failed to cache file: " + address + "/" + location, e);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to cache file: " + address + "/" + location, e);
        }
    }

    // B19: EMBEDS lookup identity is normalized — the comparison collapses
    // leading/trailing runs and internal whitespace, so a slightly differently
    // indented view still renders. The stored original_match itself stays RAW
    // (YAML-parseable) because processAll re-renders from it.
    static String normalizeBaseText(String text) {
        return FilesDao.normalizeBaseText(text);
    }

    // Test seam only: suites pin schema/loader behavior through direct SQL.
    // Production code must go through the typed SqliteFiles surface; the
    // SqliteRepository interface no longer exposes a raw connection (P3).
    @Deprecated
    public Connection getConnection() {
        return this.conn;
    }

    @Override
    public ObjectMapper getJsonMapper() {
        return jsonMapper;
    }

    @Override
    public SqliteFiles files() {
        return dao;
    }

    // NULL JSON = row not parsed yet (e.g. cacheFile upserts). JSON 'null' literal or
    // corrupt JSON = degrade to empty map, but log so the damage is visible.
    private Map<String, Object> parseProperties(String path, String json) {
        if (json == null) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> map = jsonMapper.readValue(json, MAP_TYPE_REF);
            return (map != null) ? map : new HashMap<>();
        } catch (IOException e) {
            logger.error("Corrupt properties JSON for file: {}", path, e);
            return new HashMap<>();
        }
    }

    // Declared property types from .obsidian/types.json (Obsidian Bases);
    // absent or malformed file falls back to empty (heuristic comparisons).
    private io.pskenny.pkspkms.repo.query.PropertyTypes readPropertyTypes(PkmsFileSystem fs) {
        try (InputStream in = fs.openInput(".obsidian/types.json")) {
            return io.pskenny.pkspkms.repo.query.PropertyTypes.parse(in);
        } catch (IOException e) {
            return io.pskenny.pkspkms.repo.query.PropertyTypes.empty();
        }
    }

    // Reverse lookup over LINKS; DISTINCT collapses the outgoing/wikilink double rows (11).
    // Query-time derivation: always fresh, no materialization.
    private List<String> backlinksOf(int fileId) throws SQLException {
        List<String> backlinks = new ArrayList<>();
        String sql = "SELECT DISTINCT f.file_path FROM LINKS l JOIN FILES f ON f.id = l.source_file_id "
                + "WHERE l.target_file_id = ? ORDER BY f.file_path";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, fileId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    backlinks.add(rs.getString("file_path"));
                }
            }
        }
        return backlinks;
    }

    /**
     * Batched backlink derivation for a whole result page: one round-trip per
     * 512-id chunk instead of one per row (the old per-hit query was the only
     * scaling cliff — 10k hits = 10k round-trips). Result ordering inside a
     * target's backlink list matches {@link #backlinksOf} exactly.
     */
    private Map<Integer, List<String>> backlinksOfAll(Collection<Integer> ids) throws SQLException {
        Map<Integer, List<String>> byTarget = new HashMap<>();
        List<Integer> pending = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int from = 0; from < pending.size(); from += 512) {
            List<Integer> chunk = pending.subList(from, Math.min(pending.size(), from + 512));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = "SELECT DISTINCT l.target_file_id, f.file_path FROM LINKS l JOIN FILES f ON f.id = l.source_file_id "
                    + "WHERE l.target_file_id IN (" + placeholders + ") ORDER BY f.file_path";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int i = 1;
                for (Integer id : chunk) {
                    pstmt.setInt(i++, id);
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        byTarget.computeIfAbsent(rs.getInt("target_file_id"), k -> new ArrayList<>())
                                .add(rs.getString("file_path"));
                    }
                }
            }
        }
        return byTarget;
    }

    @Override
    public io.pskenny.pkspkms.repo.query.PropertyTypes getPropertyTypes() {
        return propertyTypes;
    }

    public List<PksFile> searchRegular(CompiledQuery query)  {
        List<PksFile> results = new ArrayList<>();
        searchRegular(query, results::add);
        return results;
    }

    @Override
    public void searchRegular(CompiledQuery query, java.util.function.Consumer<PksFile> consumer) {
        // property-only queries keep byte-identical SQL; a text: probe adds the
        // FTS join (snippet + bm25 ordering)
        String select = query.textProbe()
                ? "SELECT FILES.*, snippet(files_fts, 0, '==', '==', '…', 12) AS textsnippet FROM FILES "
                        + "LEFT JOIN files_fts ON files_fts.rowid = FILES.id WHERE "
                : "SELECT * FROM FILES WHERE ";
        String sqlQuery = select + query.sql()
                + (query.textProbe() ? " ORDER BY files_fts.rowid IS NULL, bm25(files_fts)" : "");
        // one pass to read, one batched LINKS pass for backlinks — the
        // per-row backlinksOf(id) probe used to be the only N+1 scaling cliff
        record Row(String path, int id, Map<String, Object> props) {}
        List<Row> buffered = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(sqlQuery)) {
            List<Object> params = query.params();
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("file_path");
                    Map<String, Object> propMap = parseProperties(path, rs.getString("properties"));
                    if (query.textProbe()) {
                        String snippet = rs.getString("textsnippet");
                        if (snippet != null) {
                            propMap.put("textSnippet", snippet);
                        }
                    }
                    buffered.add(new Row(path, rs.getInt("id"), propMap));
                    // PksFile assembled after the batch pass (backlinks merge)
                }
            }
            Map<Integer, List<String>> backlinksByTarget;
            try {
                backlinksByTarget = backlinksOfAll(buffered.stream().filter(r -> r.id() > 0)
                        .map(r -> (Integer) r.id()).toList());
            } catch (SQLException b) {
                throw new RepositoryException("Couldn't batch backlinks for: " + query.sql(), b);
            }
            for (Row row : buffered) {
                List<String> backlinks = backlinksByTarget.get(row.id());
                if (backlinks != null && !backlinks.isEmpty()) {
                    row.props().put("backlinks", backlinks);
                }
                consumer.accept(new PksFile(row.path(), row.props()));
            }
        } catch (SQLException e) {
            logger.error("Couldn't do query: \"{}\"", query.sql(), e);
            throw new RepositoryException("Couldn't do query: " + query.sql(), e);
        }
    }

    @Override
    public List<PksFile> loadCorpus() {
        List<PksFile> corpus = new ArrayList<>();
        String sql = "SELECT file_path, properties FROM FILES";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String path = rs.getString("file_path");
                corpus.add(new PksFile(path, parseProperties(path, rs.getString("properties"))));
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to load corpus", e);
        }
        return corpus;
    }

    @Override
    public String resolveWikilink(String wikilink) {
        return wikilinkFinder.resolveWikilink(dao, wikilink);
    }

    // Resolved target for readFile: which filesystem + path inside it
    private record ResolvedPath(PkmsFileSystem fs, String relativePath) {}

    /**
     * Vault-relative or @alias/-prefixed path -> filesystem + inner path.
     * Rejects empty paths, backslashes, null bytes, absolute paths, empty /
     * dot / dot-dot segments, and .obsidian/.trash anywhere in the path —
     * MCP/tool callers hand this untrusted strings. JavaFileSystem still
     * canonically re-checks containment on openInput (defense in depth).
     */
    private ResolvedPath resolveReadPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("Empty file path");
        }
        if (filePath.indexOf('\0') >= 0 || filePath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid file path: " + filePath);
        }
        if (filePath.startsWith("/")) {
            throw new IllegalArgumentException("Absolute paths are not readable: " + filePath);
        }

        String alias = null;
        String relative = filePath;
        if (filePath.startsWith("@")) {
            int slash = filePath.indexOf('/');
            if (slash <= 1) {
                throw new IllegalArgumentException("Invalid virtual-vault path: " + filePath);
            }
            alias = filePath.substring(1, slash);
            relative = filePath.substring(slash + 1);
        }

        for (String segment : relative.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Invalid file path: " + filePath);
            }
            if (".obsidian".equals(segment) || ".trash".equals(segment)) {
                throw new IllegalArgumentException("Path not readable: " + filePath);
            }
        }

        PkmsFileSystem fs = alias == null ? mainFs : aliasFilesystems.get(alias);
        if (fs == null) {
            throw new IllegalArgumentException("Unknown virtual vault alias: @" + alias);
        }
        return new ResolvedPath(fs, relative);
    }

    @Override
    public String readFile(String filePath) {
        ResolvedPath resolved = resolveReadPath(filePath);
        try (InputStream in = resolved.fs().openInput(resolved.relativePath())) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RepositoryException("Failed to read file: " + filePath, e);
        }
    }

    /** Base64 gate for MCP binary reads: bytes cheaper than this get encoded. */
    public static final long BINARY_FILE_BASE64_CAP = 5L * 1024 * 1024;

    /**
     * Read-only binary surface for MCP: {@code stat} = size/mtime/ext without
     * content; {@code base64} = bounded Base64 encode (rejected over
     * {@link #BINARY_FILE_BASE64_CAP}). Same path rules as readFile.
     */
    @Override
    public Map<String, Object> binaryFile(String filePath, String mode) {
        ResolvedPath resolved = resolveReadPath(filePath);
        long size;
        long mtime;
        try {
            size = resolved.fs().size(resolved.relativePath());
            mtime = resolved.fs().resolve(resolved.relativePath()).lastModified();
        } catch (IOException e) {
            throw new RepositoryException("Failed to stat file: " + filePath, e);
        }

        String fileExt = filePath;
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            fileExt = filePath.substring(lastSlash + 1);
        }
        int lastDot = fileExt.lastIndexOf('.');
        fileExt = lastDot > 0 ? fileExt.substring(lastDot + 1) : "";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", filePath);
        payload.put("size", size);
        if (mtime > 0) {
            payload.put("modified", java.time.Instant.ofEpochMilli(mtime).toString());
        }
        if (!fileExt.isEmpty()) {
            payload.put("ext", fileExt);
        }

        if (!"base64".equals(mode)) {
            if (!"stat".equals(mode)) {
                throw new IllegalArgumentException("Invalid mode: " + mode + " (use stat or base64)");
            }
            return payload;
        }

        if (size > BINARY_FILE_BASE64_CAP) {
            throw new IllegalArgumentException("File is " + size + " bytes — base64 capped at 5 MB; use mode=stat");
        }
        byte[] bytes;
        try (InputStream in = resolved.fs().openInput(resolved.relativePath())) {
            bytes = in.readNBytes((int) BINARY_FILE_BASE64_CAP);
            if (bytes.length != size) {
                throw new IllegalStateException("File changed while reading: " + filePath);
            }
        } catch (IOException e) {
            throw new RepositoryException("Failed to read file: " + filePath, e);
        }
        payload.put("encoding", "base64");
        payload.put("data", java.util.Base64.getEncoder().encodeToString(bytes));
        return payload;
    }

    // --- read-only drill-down facade (links FILES SQL to the query layer) ---

    @Override
    public Map<String, Object> facets(CompiledQuery query, List<String> keys, int limit) {
        try {
            return dao.facetCounts(query, keys, limit);
        } catch (SQLException e) {
            throw new RepositoryException("Facet query failed: " + query.sql(), e);
        }
    }

    /** Exact path hit, else wikilink-shaped resolution (incl. aliases). */
    private record NoteId(int id, String path) {}

    private NoteId resolveNoteId(String pathOrWikilink) throws SQLException {
        Integer id = dao.fileIdByPath(pathOrWikilink);
        String path = pathOrWikilink;
        if (id == null) {
            String resolved = resolveWikilink(pathOrWikilink);
            if (resolved != null && !resolved.isEmpty()) {
                path = resolved;
                id = dao.fileIdByPath(resolved);
            }
        }
        if (id == null) {
            throw new IllegalArgumentException("No note matches: " + pathOrWikilink);
        }
        return new NoteId(id, path);
    }

    @Override
    public Map<String, Object> related(String pathOrWikilink) {
        try {
            NoteId note = resolveNoteId(pathOrWikilink);
            FilesDao.RelatedEdges edges = dao.relatedNoteEdges(note.id());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("path", note.path());
            payload.put("outgoing", edges.outgoing());
            payload.put("incoming", edges.incoming());
            return payload;
        } catch (SQLException e) {
            throw new RepositoryException("Related lookup failed: " + pathOrWikilink, e);
        }
    }

    @Override
    public List<String> orphanPaths(int limit, int offset) {
        try {
            return dao.orphanPaths(limit, offset);
        } catch (SQLException e) {
            throw new RepositoryException("Orphan lookup failed", e);
        }
    }

    @Override
    public long orphanCount() {
        try {
            return dao.orphanCount();
        } catch (SQLException e) {
            throw new RepositoryException("Orphan count failed", e);
        }
    }

    @Override
    public Map<String, Object> graphHops(String pathOrWikilink, int hops) {
        try {
            NoteId note = resolveNoteId(pathOrWikilink);
            int clamp = Math.max(1, Math.min(2, hops));
            List<Integer> ids = dao.neighborhoodIds(note.id(), clamp, 200);
            List<FilesDao.IdRow> rows = dao.filesByIds(ids);
            Map<Integer, List<Integer>> outgoing = new HashMap<>();
            Map<Integer, List<Integer>> incoming = new HashMap<>();
            for (int[] edge : dao.linkEdges()) {
                outgoing.computeIfAbsent(edge[0], k -> new ArrayList<>()).add(edge[1]);
                incoming.computeIfAbsent(edge[1], k -> new ArrayList<>()).add(edge[0]);
            }
            Map<Integer, String> pathById = new LinkedHashMap<>();
            for (FilesDao.IdRow row : rows) {
                pathById.put(row.id(), row.path());
            }
            List<Map<String, Object>> files = new ArrayList<>();
            for (FilesDao.IdRow row : rows) {
                // graph-shape reduction with edge-derived links/backlinks
                Map<String, Object> props = new LinkedHashMap<>(row.properties());
                List<Integer> outIds = outgoing.get(row.id());
                if (outIds != null && !outIds.isEmpty()) {
                    props.put("links", outIds.stream().map(pathById::get)
                            .filter(Objects::nonNull).distinct().sorted().toList());
                }
                List<Integer> inIds = incoming.get(row.id());
                if (inIds != null && !inIds.isEmpty()) {
                    props.put("backlinks", inIds.stream().map(pathById::get)
                            .filter(Objects::nonNull).distinct().sorted().toList());
                }
                props.put("filePath", row.path());
                files.add(props);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("path", note.path());
            payload.put("hops", clamp);
            payload.put("files", files);
            payload.put("rows_returned", files.size());
            return payload;
        } catch (SQLException e) {
            throw new RepositoryException("Graph hops failed: " + pathOrWikilink, e);
        }
    }

    @Override
    public Map<String, Object> brokenLinks(String sort, int limit, int minRefs, String aliasScope) {
        return HygieneReports.capBuckets(hygieneCompute(aliasScope), sort, limit, minRefs);
    }

    /**
     * Whole-vault sweep, cached per aliasScope until the index changes —
     * breakage analysis calls repeat within a session (tooling, agents, UI)
     * and the sweep is vault-scale. The cached compute is arg-independent
     * (sort/limit/minRefs ride {@link HygieneReports#capBuckets}).
     */
    private Map<String, Object> hygieneCompute(String aliasScope) {
        Map<String, Map<String, Object>> cache = hygieneCache;
        if (!hygieneDirty && cache.containsKey(aliasScope)) {
            return cache.get(aliasScope);
        }
        synchronized (hygieneLock) {
            cache = hygieneCache;
            if (!hygieneDirty && cache.containsKey(aliasScope)) {
                return cache.get(aliasScope);
            }
            CompiledQuery probe = queryCompiler.compile(queryParser.parse(""));
            List<PksFile> rows = searchRegular(probe);
            Map<String, Object> compute = HygieneReports.compute(rows, aliasScope,
                    this::resolveWikilink, paths -> {
                        try {
                            return dao.propertiesForPaths(paths);
                        } catch (SQLException e) {
                            throw new RepositoryException("Broken-links provenance lookup failed", e);
                        }
                    });
            hygieneComputations++;
            Map<String, Map<String, Object>> next = new LinkedHashMap<>(cache);
            if (next.size() >= 4) {
                // Evict the eldest scope only (D3): clearing everything
                // cold-started every scope in play on one flip
                next.remove(next.keySet().iterator().next());
            }
            next.put(aliasScope, compute);
            hygieneCache = next;
            hygieneDirty = false;
            return next.get(aliasScope);
        }
    }

    /** Sweep cache invalidation: any FILES/LINKS/EMBEDS-mutating path calls this first. */
    private void markHygieneStale() {
        hygieneDirty = true;
    }


    // MCP health tool facts: stats ride FilesDao; the ok/degraded judgment
    // lives in the repository (facts vs judgment split).
    @Override
    public Map<String, Object> healthSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> stats;
        try {
            stats = dao.stats();
        } catch (SQLException e) {
            logger.warn("Health snapshot failed", e);
            return Map.of("status", "degraded", "reason", "stats query failed");
        }
        long embedErrors = (long) stats.getOrDefault("embedErrors", 0L);
        long unparsed = (long) stats.getOrDefault("unparsedRows", 0L);
        String ftsProbe = String.valueOf(stats.getOrDefault("ftsProbe", "missing"));
        boolean ftsMissing = !"ok".equals(ftsProbe) && !"empty".equals(ftsProbe);

        snapshot.put("status", (embedErrors > 0 || unparsed > 0 || ftsMissing) ? "degraded" : "ok");
        snapshot.put("server", Map.of(
                "name", io.pskenny.pkspkms.mcp.McpDispatcher.SERVER_NAME,
                "version", io.pskenny.pkspkms.mcp.McpDispatcher.SERVER_VERSION,
                "schemaVersion", SQLiteSchema.SCHEMA_VERSION));
        snapshot.put("vaults", stats.getOrDefault("byAlias", List.of()));
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("links", stats.getOrDefault("links", 0L));
        index.put("embedSeeds", stats.getOrDefault("embedSeeds", 0L));
        index.put("embedErrors", embedErrors);
        index.put("unparsedRows", unparsed);
        long newest = (long) stats.getOrDefault("newestModifiedMs", 0L);
        if (newest > 0) {
            index.put("newestNoteModified", java.time.Instant.ofEpochMilli(newest).toString());
        }
        snapshot.put("index", index);
        snapshot.put("fts", Map.of("probe", ftsProbe));
        return snapshot;
    }

    public void close() {
        if (conn != null) {
            try {
                // A clean last-connection close is SQLite's trigger to delete
                // the -wal/-shm sidecars; a final TRUNCATE checkpoint makes
                // the handoff deterministic even after heavy batches. A
                // failed checkpoint must never mask the close itself.
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
                } catch (SQLException e) {
                    logger.debug("Final wal checkpoint skipped: {}", e.getMessage());
                }
                if (!conn.isClosed()) conn.close();
            } catch (SQLException e) {
                throw new RepositoryException("Failed to close database connection", e);
            }
        }
    }

    public String getMarkdownFromLuaBase(String text) {
        return getGeneratedContent("luabase", text);
    }

    public String getMarkdownFromBase(String text) {
        return getGeneratedContent("base", text);
    }

    // B9: the export-facing lookup never leaks the "ERROR" diagnostic or raw
    // YAML — only rendered content or the empty string. The empty fallback
    // replaces the old originalMatch round-trip (B19's leak channel).
    //
    // B19: the stored original_match keeps its raw (YAML-parseable) shape so
    // processAll can re-render it; candidates are matched by the normalized
    // comparison instead — EMBEDS is small, a per-type scan is cheap.
    private String getGeneratedContent(String type, String originalMatch) {
        String wanted = normalizeBaseText(originalMatch);
        String sql = "SELECT generated_content, original_match FROM EMBEDS WHERE type = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, type);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (!normalizeBaseText(rs.getString("original_match")).equals(wanted)) {
                        continue;
                    }
                    String generated = rs.getString("generated_content");
                    if (generated != null && !generated.isEmpty() && !"ERROR".equals(generated)) {
                        return generated;
                    }
                    return "";
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to look up {} generated content", type, e);
        }
        return "";
    }
}
