package io.pskenny.pkspkms.repo.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.processor.MarkdownProcessor;
import io.pskenny.pkspkms.luabase.LuaBaseProcessor;
import io.pskenny.pkspkms.luabase.NaiveBaseToLuaBaseConverter;
import io.pskenny.pkspkms.luabase.YamlParser;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.services.WikilinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.*;
import java.util.Map;

// computed_props: links/wikilinks extracted from generated embed content.
// Stored for future search indexing. Not yet consumed by any query path.
// WikilinkService is constructed once per processAll() (not per-row) to avoid a full
// SELECT on FILES for every embed row.

/**
 * Processes embed records in the database, generating rendered content
 * for base and luabase embed types.
 */
public class SqliteEmbedProcessor {
    private static final Logger logger = LoggerFactory.getLogger(SqliteEmbedProcessor.class);
    private final String dbUrl;
    private final ObjectMapper jsonMapper;
    private final LuaBaseProcessor processor;
    private final NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter;
    private final MarkdownProcessor markdownProcessor;
    private final PksFileRepository repository;

    public SqliteEmbedProcessor(String dbUrl, ObjectMapper jsonMapper, LuaBaseProcessor processor,
                         NaiveBaseToLuaBaseConverter converter, MarkdownProcessor markdownProcessor,
                         PksFileRepository repository) {
        this.dbUrl = dbUrl;
        this.jsonMapper = jsonMapper;
        this.processor = processor;
        this.naiveBaseToLuaBaseConverter = converter;
        this.markdownProcessor = markdownProcessor;
        this.repository = repository;
    }

    public void processAll() throws SQLException, IOException {
        String selectSql = "SELECT id, original_match, type FROM EMBEDS";
        String updateSql = "UPDATE EMBEDS SET generated_content = ?, computed_props = ? WHERE id = ?";
        long startNs = System.nanoTime();

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            SQLitePragmas.apply(conn);

            conn.setAutoCommit(false);
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql);
                 PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                ResultSet rs = selectStmt.executeQuery();
                WikilinkService wikilinkService = new WikilinkService(dbUrl);
                int generated = 0;
                int errors = 0;

                while (rs.next()) {
                    int id = rs.getInt("id");
                    String text = rs.getString("original_match");
                    String type = rs.getString("type");
                    String generatedText = "";
                    try {
                        generatedText = generateEmbedContent(type, text);
                    } catch (Exception ex) {
                        logger.error("Couldn't generate Markdown text from {}: {}", type, text, ex);
                        errors++;
                    }
                    if ("ERROR".equals(generatedText)) {
                        errors++;
                    } else if (!generatedText.isEmpty()) {
                        generated++;
                    }
                    String properties = jsonMapper.writeValueAsString(markdownProcessor.parseToMap(generatedText, wikilinkService));

                    updateStmt.setString(1, generatedText);
                    updateStmt.setString(2, properties);
                    updateStmt.setInt(3, id);
                    updateStmt.executeUpdate();
                }
                conn.commit();
                logger.info("Generated {} embeds in {}ms (errors: {})",
                        generated, (System.nanoTime() - startNs) / 1_000_000, errors);

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
            return processBaseMap(naiveBaseToLuaBaseConverter.convertToMap(text));
        }
        return "";
    }

    private String processLuaBaseText(String text) {
        try {
            Map<String, Object> spec = new YamlParser().parse(text);
            return processor.process(spec, repository);
        } catch (Exception e) {
            logger.error("Couldn't process Base text: {}", text, e);
        }
        return "ERROR";
    }

    private String processBaseMap(Map<String, Object> spec) {
        try {
            return processor.process(spec, repository);
        } catch (Exception e) {
            logger.error("Couldn't process Base spec: {}", spec);
        }
        return "ERROR";
    }
}
