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
import io.pskenny.pkspkms.repo.RepositoryException;
import io.pskenny.pkspkms.repo.SqliteRepository;

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
    private final ObjectMapper jsonMapper = new ObjectMapper();

    private final LuaBaseProcessor processor = new LuaBaseProcessor();
    private final NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter = new NaiveBaseToLuaBaseConverter();
    private final MarkdownProcessor markdownProcessor;

    private final SqliteEmbedProcessor sqliteEmbedProcessor;
    private final WikilinkFinder wikilinkFinder = new WikilinkFinder();
    private final io.pskenny.pkspkms.repo.query.QueryParser queryParser = new io.pskenny.pkspkms.repo.query.QueryParser();
    private final io.pskenny.pkspkms.repo.query.QueryCompiler queryCompiler = new io.pskenny.pkspkms.repo.query.QueryCompiler();
    private final PkmsFileSystem mainFs;
    private final Map<String, PkmsFileSystem> aliasFilesystems = new HashMap<>();
    private final io.pskenny.pkspkms.repo.query.PropertyTypes propertyTypes;

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    public SQLitePksFileRepository(String dbUrl,
                                   java.util.function.Consumer<java.sql.Connection> luaFunctionRegistrar,
                                   PkmsFileSystem vaultFs) {
        this.mainFs = vaultFs;
        try {
            this.conn = DriverManager.getConnection(dbUrl);
            SQLitePragmas.apply(this.conn);
            this.propertyTypes = readPropertyTypes(vaultFs);

            this.markdownProcessor = new MarkdownProcessor();
            SQLiteSchema.init(this.conn);

            // Automatically index tags and type
            createPropertyIndex("tags", "array");
            createPropertyIndex("type", "text");

            if (luaFunctionRegistrar != null) {
                luaFunctionRegistrar.accept(this.conn);
            }
            this.sqliteEmbedProcessor = new SqliteEmbedProcessor(dbUrl, jsonMapper, processor,
                    naiveBaseToLuaBaseConverter, markdownProcessor, this);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to connect to database: " + dbUrl, e);
        }
    }

    public void loadDirectoryIntoRepository() {
        long startNs = System.nanoTime();
        try {
            new RepositoryFileLoader(this, mainFs, null).load();
            sqliteEmbedProcessor.processAll();
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
        try {
            if (vaultAliasExists(alias)) {
                throw new IllegalArgumentException("Vault alias already exists: " + alias);
            }
            int aliasId = addVaultAlias(alias, "", true);
            logger.info("Loading vault {} (id {})", alias, aliasId);
            aliasFilesystems.put(alias, aliasFs);
            new RepositoryFileLoader(this, aliasFs, alias).load();
            sqliteEmbedProcessor.processAll();
        } catch (IOException e) {
            throw new RepositoryException("Failed to load virtual vault: " + alias, e);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to load virtual vault: " + alias, e);
        }
    }

    public int addVaultAlias(String alias, String directory, boolean isVirtual) {
        String sql = "INSERT INTO VAULT_ALIASES (alias, directory, is_virtual, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, alias);
            pstmt.setString(2, directory);
            pstmt.setInt(3, isVirtual ? 1 : 0);
            pstmt.setLong(4, System.currentTimeMillis());
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to add vault alias: " + alias, e);
        }
        return -1;
    }

    public boolean vaultAliasExists(String alias) {
        String sql = "SELECT 1 FROM VAULT_ALIASES WHERE alias = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, alias);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RepositoryException("Failed to check vault alias: " + alias, e);
        }
    }

    public Map<String, Object> cacheFile(String address, String location, String cacheDirectory) {
        try {
            String cacheRelPath = cacheDirectory + "/" + address + "/" + location;

            // Check if file already exists in cache
            if (!mainFs.exists(cacheRelPath)) {
                // File not in cache - check if address is a virtual vault alias
                PkmsFileSystem aliasFs = aliasFilesystems.get(address);
                if (aliasFs != null && aliasFs.exists(location)) {
                    // Copy from alias fs to main fs cache
                    try (InputStream in = aliasFs.openInput(location)) {
                        mainFs.copyFrom(in, cacheRelPath);
                    }
                } else {
                    // Not a known alias or alias doesn't have the file
                    throw new IOException("File not found: " + address + "/" + location);
                }
            }

            // Hash the cached file
            String blake3Hash;
            try (InputStream in = mainFs.openInput(cacheRelPath)) {
                blake3Hash = Blake3Util.hashStream(in);
            }

            String upsertSql = "INSERT OR REPLACE INTO FILES (file_path, file_name, file_name_ext, blake3, vault_alias_id) VALUES (?, ?, ?, ?, NULL)";
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

    public void addEmbedsToTable(int fileId, PksFile pksFile, String key, String type) throws SQLException {
        Object val = pksFile.getMutableProperties().get(key);
        if (val instanceof List<?> items) {
            for (Object item : items) {
                String originalMatch = String.valueOf(item);
                String targetFile = null;
                String targetHeader = null;

                if (originalMatch.startsWith("![[") && originalMatch.endsWith("]]")) {
                    String content = originalMatch.substring(3, originalMatch.length() - 2);
                    String[] parts = content.split("#", 2);
                    targetFile = parts[0];
                    if (parts.length > 1) {
                        targetHeader = parts[1];
                    }
                }

                this.insertEmbed(fileId, type, originalMatch, targetFile, targetHeader);
            }
        }
    }

    public Connection getConnection() {
        return this.conn;
    }

    public ObjectMapper getJsonMapper() {
        return jsonMapper;
    }

    public void setLinks(int sourceFileId, List<String> targetFilePaths, String linkType) throws SQLException {
        String insertSql = "INSERT INTO LINKS (source_file_id, target_file_id, link_type) " +
                "SELECT ?, id, ? FROM FILES WHERE file_path = ?";

        try (PreparedStatement delStmt = conn.prepareStatement(
                "DELETE FROM LINKS WHERE source_file_id = ? AND link_type = ?")) {
            delStmt.setInt(1, sourceFileId);
            delStmt.setString(2, linkType);
            delStmt.executeUpdate();
        }

        try (PreparedStatement insStmt = conn.prepareStatement(insertSql)) {
            for (String path : new java.util.LinkedHashSet<>(targetFilePaths)) {
                insStmt.setInt(1, sourceFileId);
                insStmt.setString(2, linkType);
                insStmt.setString(3, path);
                insStmt.addBatch();
            }
            insStmt.executeBatch();
        }
    }

    public int insertEmbed(int fileId, String type, String originalMatch, String targetFile, String targetHeader) throws SQLException {
        String sql = "INSERT INTO EMBEDS (file_id, type, original_match, target_file, target_header) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setInt(1, fileId);
            pstmt.setString(2, type);
            pstmt.setString(3, originalMatch);
            pstmt.setString(4, targetFile);
            pstmt.setString(5, targetHeader);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
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

    // Reverse lookup over LINKS; DISTINCT collapses the outgoing/wikilink double rows (B29).
    // Query-time derivation: always fresh, no materialization (B7).
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
        String sqlQuery = "SELECT * FROM FILES WHERE " + query.sql();
            try (PreparedStatement pstmt = conn.prepareStatement(sqlQuery)) {
            List<Object> params = query.params();
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {

                while (rs.next()) {
                    String path = rs.getString("file_path");
                    Map<String, Object> propMap = parseProperties(path, rs.getString("properties"));
                    int id = rs.getInt("id");
                    if (!rs.wasNull()) {
                        List<String> backlinks = backlinksOf(id);
                        if (!backlinks.isEmpty()) {
                            propMap.put("backlinks", backlinks);
                        }
                    }
                    PksFile pksFile = new PksFile(path, propMap);
                    consumer.accept(pksFile);
                }
            }
        } catch (SQLException e) {
            logger.error("Couldn't do query: \"{}\"", query.sql(), e);
            throw new RepositoryException("Couldn't do query: " + query.sql(), e);
        }
    }

    public List<PksFile> searchWithLuaFilter(String luaScript) {
        List<PksFile> results = new ArrayList<>();

        // Pre-validation: compile the Lua script once to catch syntax errors early
        // and avoid expensive database queries that would crash on every row.
        try {
            processor.validateLuaFilter(luaScript);
        } catch (RuntimeException e) {
            logger.error("Invalid Lua filter syntax, skipping query: {}", luaScript);
            return results;
        }

        String sql = "SELECT file_path, properties FROM FILES WHERE lua_eval(?, properties) = 1";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, luaScript);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("file_path");
                    Map<String, Object> propMap = parseProperties(path, rs.getString("properties"));
                    PksFile pksFile = new PksFile(path, propMap);
                    results.add(pksFile);
                }
            }
        } catch (SQLException e) {
            logger.error("Couldn't execute Lua filter query, " + luaScript);
            throw new RepositoryException("Couldn't execute Lua filter query", e);
        }

        return results;
    }

    @Override
    public String resolveWikilink(String wikilink) {
        return wikilinkFinder.resolveWikilink(conn, wikilink);
    }

    @Override
    public void createPropertyIndex(String propertyKey, String type) {
        // Sanitize key name to prevent SQL injection and allow only valid identifier chars
        String cleanKey = propertyKey.replaceAll("[^a-zA-Z0-9_]", "");

        // Create SQLite Expression Index on json_extract() output
        String sql = "CREATE INDEX IF NOT EXISTS idx_files_prop_" + cleanKey +
                     " ON FILES(json_extract(properties, '$." + propertyKey + "'));";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RepositoryException("Failed to create property index: " + propertyKey, e);
        }
    }

    public void close() {
        if (conn != null) {
            try {
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

    private String getGeneratedContent(String type, String originalMatch) {
        String sql = "SELECT generated_content FROM EMBEDS WHERE type = ? AND original_match = ? LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, type);
            stmt.setString(2, originalMatch);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String generated = rs.getString("generated_content");
                    return generated != null ? generated : originalMatch;
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to look up {} generated content", type, e);
        }
        return originalMatch;
    }
}
