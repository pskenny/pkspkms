package io.pskenny.pkspkms.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.parser.MarkdownParser;
import io.pskenny.pkspkms.luabase.LuaBaseProcessor;
import io.pskenny.pkspkms.luabase.NaiveBaseToLuaBaseConverter;
import io.pskenny.pkspkms.repo.sqlite.SQLiteLuaConnector;
import io.pskenny.pkspkms.repo.sqlite.SQLiteSchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.Locale;

/**
 * Repository for PksFile persistence and querying via SQLite.
 * Coordinates file loading, link resolution, embed processing, and search.
 */
public final class SQLitePksFileRepository implements PksFileRepository {
    private static final Logger logger = LoggerFactory.getLogger(SQLitePksFileRepository.class);
    private final Connection conn;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    private final LuaBaseProcessor processor = new LuaBaseProcessor();
    private final NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter = new NaiveBaseToLuaBaseConverter();
    private final MarkdownParser markdownParser;

    private final FileLoader fileLoader;
    private final EmbedProcessor embedProcessor;
    private final WikilinkFinder wikilinkFinder = new WikilinkFinder();

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_TYPE_REF = new TypeReference<>() {};

    public SQLitePksFileRepository(String dbUrl) throws SQLException {
        this.conn = DriverManager.getConnection(dbUrl);
        this.markdownParser = new MarkdownParser(dbUrl);
        SQLiteSchema.init(this.conn);
        SQLiteLuaConnector.registerLuaFunction(this.conn);
        this.fileLoader = new FileLoader(this, dbUrl);
        this.embedProcessor = new EmbedProcessor(dbUrl, jsonMapper, processor,
                naiveBaseToLuaBaseConverter, markdownParser, this);
    }

    public void loadDirectoryIntoRepository(String directory) throws IOException, SQLException {
        fileLoader.load(directory);
        embedProcessor.processAll();
    }

    void addEmbedsToTable(int fileId, PksFile pksFile, String key, String type) throws SQLException {
        Object val = pksFile.getProperties().get(key);
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

    public int insertFile(String filePath) throws SQLException {
        String upsertSql = "INSERT OR IGNORE INTO FILES (file_path, file_name, file_name_ext) VALUES (?, ?, ?)";
        String selectSql = "SELECT id FROM FILES WHERE file_path = ?";

        java.nio.file.Path path = java.nio.file.Paths.get(filePath);
        java.nio.file.Path fileNamePath = path.getFileName();
        String fileNameExt = fileNamePath != null ? fileNamePath.toString() : "";
        String fileName = fileNameExt.replaceFirst("[.][^.]+$", "");

        try (PreparedStatement pstmt = conn.prepareStatement(upsertSql)) {
            pstmt.setString(1, filePath);
            pstmt.setString(2, fileName);
            pstmt.setString(3, fileNameExt);
            pstmt.executeUpdate();
        }

        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setString(1, filePath);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getInt("id");
            }
        }
        return -1;
    }

    public Integer updatePropertiesByPath(PksFile file) {
        String updateSql = "UPDATE FILES SET properties = ? WHERE file_path = ?";
        String selectSql = "SELECT id FROM FILES WHERE file_path = ?";

        try {
            String properties = jsonMapper.writeValueAsString(file.getProperties());

            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql);
                 PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {

                updateStmt.setString(1, properties);
                updateStmt.setString(2, file.getFilePath());
                int affectedRows = updateStmt.executeUpdate();

                if (affectedRows > 0) {
                    selectStmt.setString(1, file.getFilePath());
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (rs.next()) {
                            return rs.getInt("id");
                        }
                    }
                } else {
                    logger.warn("No record found for path: {}", file.getFilePath());
                }
            }
        } catch (Exception e) {
            logger.error("Error updating properties: {}", e.getMessage(), e);
        }
        return -1;
    }

    public Connection getConnection() {
        return this.conn;
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

        conn.commit();
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

    public List<PksFile> searchRegular(String query)  {
        List<PksFile> results = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                String path = rs.getString("file_path");
                String jsonStr = rs.getString("properties");
                Map<String, Object> propMap;
                try {
                    propMap = jsonMapper.readValue(jsonStr, MAP_TYPE_REF);
                } catch (IOException e) {
                    propMap = new java.util.HashMap<>();
                }
                try {
                    String backlinksJson = rs.getString("backlinks_json");
                    if (backlinksJson != null && !backlinksJson.equals("[]")) {
                        List<String> backlinks = jsonMapper.readValue(backlinksJson, LIST_TYPE_REF);
                        propMap.put("backlinks", backlinks);
                    }
                } catch (SQLException | IOException ignore) { }
                PksFile pksFile = new PksFile(path, propMap);
                results.add(pksFile);
            }
        } catch (SQLException e) {
            logger.error("Couldn't do query: \"{}\"", query, e);
            throw new IllegalStateException(e);
        }
        return results;
    }

    public PksFile getFileWithBacklinks(String filePath) {
        String sql = "SELECT f.*, " +
                "(SELECT json_group_array(src.file_path) " +
                " FROM LINKS fl " +
                " JOIN FILES src ON fl.source_file_id = src.id " +
                " WHERE fl.target_file_id = f.id) AS backlinks_json " +
                "FROM FILES f WHERE f.file_path = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, filePath);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String path = rs.getString("file_path");
                    String jsonStr = rs.getString("properties");
                    Map<String, Object> propMap;
                    try {
                        propMap = jsonMapper.readValue(jsonStr, MAP_TYPE_REF);
                    } catch (IOException e) {
                        propMap = new HashMap<>();
                    }

                    try {
                        String backlinksJson = rs.getString("backlinks_json");
                        if (backlinksJson != null && !backlinksJson.equals("[]")) {
                            List<String> backlinks = jsonMapper.readValue(backlinksJson, LIST_TYPE_REF);
                            propMap.put("backlinks", backlinks);
                        }
                    } catch (SQLException | IOException ignore) { }
                    return new PksFile(path, propMap);
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching file with backlinks: {}", e.getMessage(), e);
        }
        return null;
    }

    public String buildQueryWithBacklinks(String whereClause) {
        String baseSelect = "SELECT f.*, " +
                "(SELECT json_group_array(src.file_path) " +
                " FROM LINKS fl " +
                " JOIN FILES src ON fl.source_file_id = src.id " +
                " WHERE fl.target_file_id = f.id) AS backlinks_json " +
                "FROM FILES f ";

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            String trimmed = whereClause.trim();
            if (!trimmed.toUpperCase(Locale.ROOT).startsWith("WHERE") &&
                    !trimmed.toUpperCase(Locale.ROOT).startsWith("ORDER") &&
                    !trimmed.toUpperCase(Locale.ROOT).startsWith("LIMIT")) {
                baseSelect += " WHERE ";
            }
            baseSelect += " " + whereClause;
        }

        return baseSelect;
    }

    public List<PksFile> searchWithLuaFilter(String luaScript) {
        List<PksFile> results = new ArrayList<>();
        // 1. Updated SQL to fetch all required columns for PksFile mapping
        String sql = "SELECT file_path, properties FROM FILES WHERE lua_eval(?, properties) = 1";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, luaScript);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("file_path");
                    String jsonStr = rs.getString("properties");
                    Map<String, Object> propMap;

                    try {
                        propMap = jsonMapper.readValue(jsonStr, MAP_TYPE_REF);
                    } catch (IOException e) {
                        propMap = new java.util.HashMap<>();
                    }
                    PksFile pksFile = new PksFile(path, propMap);
                    results.add(pksFile);
                }
            }
        } catch (SQLException e) {
            logger.error("Couldn't execute Lua filter query", e);
            throw new IllegalStateException(e);
        }

        return results;
    }

    @Override
    public String resolveWikilink(String wikilink) {
        return wikilinkFinder.resolveWikilink(conn, wikilink);
    }

    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
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
