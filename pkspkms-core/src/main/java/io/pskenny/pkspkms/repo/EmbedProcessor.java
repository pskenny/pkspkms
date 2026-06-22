package io.pskenny.pkspkms.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.parser.MarkdownParser;
import io.pskenny.pkspkms.luabase.LuaBaseProcessor;
import io.pskenny.pkspkms.luabase.NaiveBaseToLuaBaseConverter;
import io.pskenny.pkspkms.luabase.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.*;
import java.util.Map;

/**
 * Processes embed records in the database, generating rendered content
 * for base and luabase embed types.
 */
final class EmbedProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EmbedProcessor.class);

    private final String dbUrl;
    private final ObjectMapper jsonMapper;
    private final LuaBaseProcessor processor;
    private final NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter;
    private final MarkdownParser markdownParser;
    private final SQLitePksFileRepository repository;

    EmbedProcessor(String dbUrl, ObjectMapper jsonMapper, LuaBaseProcessor processor,
                   NaiveBaseToLuaBaseConverter converter, MarkdownParser markdownParser,
                   SQLitePksFileRepository repository) {
        this.dbUrl = dbUrl;
        this.jsonMapper = jsonMapper;
        this.processor = processor;
        this.naiveBaseToLuaBaseConverter = converter;
        this.markdownParser = markdownParser;
        this.repository = repository;
    }

    void processAll() throws SQLException, IOException {
        String selectSql = "SELECT id, original_match, type FROM EMBEDS";
        String updateSql = "UPDATE EMBEDS SET generated_content = ?, computed_props = ? WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            conn.setAutoCommit(false);
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql);
                 PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {

                ResultSet rs = selectStmt.executeQuery();

                while (rs.next()) {
                    int id = rs.getInt("id");
                    String text = rs.getString("original_match");
                    String type = rs.getString("type");
                    String generatedText = "";
                    try {
                        generatedText = generateEmbedContent(type, text);
                    } catch (Exception ex) {
                       logger.error("Couldn't generate Markdown text from {}: {}", type, text);
                    }
                    String properties = jsonMapper.writeValueAsString(markdownParser.parseToMap(generatedText));

                    updateStmt.setString(1, generatedText);
                    updateStmt.setString(2, properties);
                    updateStmt.setInt(3, id);
                    updateStmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | IOException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private String generateEmbedContent(String type, String text) {
        if ("luabase".equalsIgnoreCase(type)) {
            return processLuaBaseText(text);
        }
        if ("base".equalsIgnoreCase(type)) {
            return processLuaBaseText(naiveBaseToLuaBaseConverter.convert(text));
        }
        return "";
    }

    private String processLuaBaseText(String text) {
        try {
            Map<String, Object> spec = new YamlParser().parse(text);
            return processor.process(spec, repository);
        } catch (Exception e) {
            logger.error("Couldn't process Base text: {}", text);
        }
        return "ERROR";
    }
}
