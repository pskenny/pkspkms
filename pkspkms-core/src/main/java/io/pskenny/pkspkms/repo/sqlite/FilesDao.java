package io.pskenny.pkspkms.repo.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.VaultKind;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.QueryCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Typed SQL surface for the FILES row graph (loader-facing). Owns the shared
 * JDBC connection privately — the repository layer never sees a raw
 * {@link Connection} (BUGS.md P3: the old interface leaked one and the service
 * layer hand-rolled SQL). Every statement moves here verbatim from its old
 * call site; behavior is pinned by the loader/resolver/export test batteries.
 *
 * Transaction shape note (documented delta from the old code): the old loader
 * wrapped the property update batch and the link/embed phase in one big
 * transaction. Each public mutator here runs in its own transaction — a crash
 * between phases can leave a file's properties updated and its link rows stale
 * for one load cycle; the next load refreshes them. Single-writer SQLite +
 * single-user scale keep the window invisible.
 */
public final class FilesDao implements SqliteFiles {

    private static final Logger logger = LoggerFactory.getLogger(FilesDao.class);

    private final Connection conn;

    public FilesDao(Connection conn) {
        this.conn = conn;
    }

    // Loader step 2: repository-wide (path -> mtime) ledger for the diff pass.
    public Map<String, Long> modifiedTimes() throws SQLException {
        Map<String, Long> byPath = new HashMap<>();
        String sql = "SELECT file_path, file_last_modified FROM FILES";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String path = rs.getString("file_path");
                long mtime = rs.getLong("file_last_modified");
                if (path != null) {
                    byPath.put(path, mtime);
                }
            }
        }
        return byPath;
    }

    /**
     * Loader step 4: remove deleted files together with their graph edges.
     * One transaction — the old code deleted embeds, incoming links, outgoing
     * links and the row in that order.
     */
    public void deleteFilesWithEdges(List<String> paths) throws SQLException {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        boolean previous = conn.getAutoCommit();
        conn.setAutoCommit(false);
        String delEmbeds = "DELETE FROM EMBEDS WHERE file_id IN (SELECT id FROM FILES WHERE file_path = ?)";
        String delLinksSrc = "DELETE FROM LINKS WHERE source_file_id IN (SELECT id FROM FILES WHERE file_path = ?)";
        String delLinksTgt = "DELETE FROM LINKS WHERE target_file_id IN (SELECT id FROM FILES WHERE file_path = ?)";
        String delFile = "DELETE FROM FILES WHERE file_path = ?";

        try (PreparedStatement psEmbeds = conn.prepareStatement(delEmbeds);
             PreparedStatement psLinksSrc = conn.prepareStatement(delLinksSrc);
             PreparedStatement psLinksTgt = conn.prepareStatement(delLinksTgt);
             PreparedStatement psFile = conn.prepareStatement(delFile)) {

            for (String path : paths) {
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
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** Loader step 6: INSERT OR IGNORE the file rows (names derived here). */
    public void insertFiles(List<PksFile> pksFiles, Integer vaultAliasId) throws SQLException {
        boolean previous = conn.getAutoCommit();
        conn.setAutoCommit(false);
        String upsertSql = "INSERT OR IGNORE INTO FILES (file_path, file_name, file_name_ext, vault_alias_id, blake3) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(upsertSql)) {
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
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // Update-phase selector: path -> id (kept verbatim: all rows, one query)
    public Map<String, Integer> idsByPath() throws SQLException {
        Map<String, Integer> byPath = new HashMap<>();
        String selectSql = "SELECT id, file_path FROM FILES";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {
            while (rs.next()) {
                byPath.put(rs.getString("file_path"), rs.getInt("id"));
            }
        }
        return byPath;
    }

    /** Batched property update; rows assert their id. */
    @Override
    public void updateFileProperties(List<FilePropertyRow> rows) throws SQLException {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        boolean previous = conn.getAutoCommit();
        conn.setAutoCommit(false);
        String updateSql = "UPDATE FILES SET properties = ?, blake3 = ?, file_last_modified = ?, body = ? WHERE id = ?";
        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            for (FilePropertyRow row : rows) {
                updateStmt.setString(1, row.propertiesJson());
                if (row.blake3() != null) {
                    updateStmt.setString(2, row.blake3());
                } else {
                    updateStmt.setNull(2, java.sql.Types.VARCHAR);
                }
                updateStmt.setLong(3, row.lastModified());
                // body feeds the FTS5 trigger sync; '' = unindexed (binaries)
                updateStmt.setString(4, row.body() != null ? row.body() : "");
                updateStmt.setInt(5, row.id());
                updateStmt.addBatch();
            }
            updateStmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // Derived column values: name / name_ext / path index for load-time
    // wikilink resolution (alias-scoped — null alias = main vault only).
    @Override
    public SqliteFiles.NameIndex nameIndex(String alias) throws SQLException {
        Map<String, String> byPath = new HashMap<>();
        Map<String, String> byNameExt = new HashMap<>();
        Map<String, String> byName = new HashMap<>();
        String sql = (alias == null || alias.isEmpty())
                ? "SELECT file_path, file_name_ext, file_name FROM FILES WHERE vault_alias_id IS NULL"
                : "SELECT f.file_path, f.file_name_ext, f.file_name FROM FILES f "
                        + "JOIN VAULT_ALIASES a ON f.vault_alias_id = a.id WHERE a.alias = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if (alias != null && !alias.isEmpty()) {
                pstmt.setString(1, alias);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("file_path");
                    String ext = rs.getString("file_name_ext");
                    String name = rs.getString("file_name");
                    if (path != null) byPath.put(path, path);
                    if (ext != null) byNameExt.put(ext, path);
                    if (name != null) byName.put(name, path);
                }
            }
        }
        return new SqliteFiles.NameIndex(byPath, byNameExt, byName);
    }

    /**
     * Deterministic duplicate-name probe (BUGS.md B7): aliasless rows first,
     * then shortest path, then lexicographic.
     */
    public Optional<String> resolveName(String cleanLink) throws SQLException {
        String sql = "SELECT file_path FROM FILES WHERE file_path = ? OR file_name_ext = ? OR file_name = ? "
                + "ORDER BY (vault_alias_id IS NULL) DESC, LENGTH(file_path) ASC, file_path ASC LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, cleanLink);
            pstmt.setString(2, cleanLink);
            pstmt.setString(3, cleanLink);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("file_path"));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Batched parsed properties for exact paths: wikilink-span provenance
     * reads the sources' stored {@code wikilinkSpans} — one round-trip per
     * 512-path chunk instead of one per source.
     */
    public Map<String, Map<String, Object>> propertiesForPaths(List<String> paths) throws SQLException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (paths == null || paths.isEmpty()) {
            return out;
        }
        List<String> pending = new ArrayList<>(new LinkedHashSet<>(paths));
        for (int from = 0; from < pending.size(); from += 512) {
            List<String> chunk = pending.subList(from, Math.min(pending.size(), from + 512));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = "SELECT file_path, properties FROM FILES WHERE file_path IN (" + placeholders + ")";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int index = 1;
                for (String path : chunk) {
                    pstmt.setString(index++, path);
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String path = rs.getString("file_path");
                        String json = rs.getString("properties");
                        Map<String, Object> props = new LinkedHashMap<>();
                        try {
                            Map<String, Object> parsed = JSON.readValue(json, MAP_TYPE);
                            if (parsed != null) {
                                props.putAll(parsed);
                            }
                        } catch (Exception e) {
                            logger.warn("Corrupt properties JSON in provenance path set: {}", path);
                        }
                        out.put(path, props);
                    }
                }
            }
        }
        return out;
    }

    /** Delete-then-insert per type: one row per (source, target, type). */
    public void setLinks(int sourceFileId, List<String> targetFilePaths, String linkType) throws SQLException {
        String insertSql = "INSERT INTO LINKS (source_file_id, target_file_id, link_type) "
                + "SELECT ?, id, ? FROM FILES WHERE file_path = ?";

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

    /** Re-parses must not accumulate EMBEDS generations per file. */
    public void deleteEmbedsFor(int fileId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM EMBEDS WHERE file_id = ?")) {
            pstmt.setInt(1, fileId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Seeds EMBEDS rows from a parsed file's embed lists. Lookup identity is
     * normalized with {@link #normalizeBaseText} (B19); the raw match stays
     * stored so the render pass can re-parse it.
     */
    public void addEmbedsToTable(int fileId, PksFile pksFile, String propertyName, String type) throws SQLException {
        Object val = pksFile.getMutableProperties().get(propertyName);
        if (!(val instanceof List<?> items)) {
            return;
        }
        for (Object item : items) {
            String originalMatch = String.valueOf(item);
            String targetFile = null;
            String targetHeader = null;

            if (originalMatch.startsWith("![[") && originalMatch.endsWith("]]")) {
                String content = originalMatch.substring(3, originalMatch.length() - 2);
                // BUGS.md 21: [[target#heading|alias]] — the alias part never
                // names a file, so strip it before the heading split
                content = content.split("\\|", 2)[0];
                String[] parts = content.split("#", 2);
                targetFile = parts[0];
                if (parts.length > 1) {
                    targetHeader = parts[1];
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO EMBEDS (file_id, type, original_match, target_file, target_header) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, fileId);
                stmt.setString(2, type);
                stmt.setString(3, originalMatch);
                stmt.setString(4, targetFile);
                stmt.setString(5, targetHeader);
                stmt.executeUpdate();
            }
        }
    }

    public boolean vaultAliasExists(String alias) throws SQLException {
        String sql = "SELECT 1 FROM VAULT_ALIASES WHERE alias = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, alias);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Idempotent alias registration: re-mounting the same alias returns the
     * existing row's id (a leftover alias row from an earlier load must not
     * fail the load).
     */
    public int addVaultAlias(String alias, String directory, VaultKind kind) throws SQLException {
        String sql = "INSERT INTO VAULT_ALIASES (alias, directory, is_virtual, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, alias);
            pstmt.setString(2, directory);
            pstmt.setInt(3, kind.isVirtual() ? 1 : 0);
            pstmt.setLong(4, System.currentTimeMillis());
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                // re-mount: same alias already registered — reuse its id
                return aliasId(alias);
            }
            throw e;
        }
        return -1;
    }

    public Integer aliasId(String alias) throws SQLException {
        String sql = "SELECT id FROM VAULT_ALIASES WHERE alias = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, alias);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        }
        return null;
    }

    // BUGS.md 19: lookup identity is whitespace-normalized (B19); the stored
    // original_match stays RAW so processAll can re-parse it.
    public static String normalizeBaseText(String text) {
        if (text == null) {
            return "";
        }
        return text.strip().replaceAll("\\s+", " ");
    }

    /**
     * EMBEDS lookup: compare candidates by normalized identity, return the
     * rendered content or "" — the "ERROR" diagnostic and raw YAML never leak
     * (BUGS.md 9).
     */
    public Optional<String> generatedContent(String type, String originalMatch) throws SQLException {
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
                        return Optional.of(generated);
                    }
                    return Optional.of("");
                }
            }
        }
        return Optional.of("");
    }

    // --- read-only drill-down surface: facets, related, orphans ---

    // High-cardinality text keys are not facetable (they'd return thousands of
    // single-value buckets); filePath is a column, not a property
    public static final Set<String> FACET_EXCLUDED_KEYS =
            Set.of("text", "body", "filePath", "links", "backlinks");

    /**
     * Facet value counts over the query's result set: the row query's compiled
     * WHERE fragment re-aimed at GROUP-BY counting. Multi-value properties
     * (tags arrays) count per element — standard drill-down semantics; a
     * missing property lands in the {@code (missing)} bucket.
     *
     * Special keys: {@code folder} (directory prefix of file_path),
     * {@code modified} (month bucket of file_last_modified), {@code vault}
     * (registry alias). Every other key is a property key counted through
     * json_each (scalar keys degrade to one row per matching note).
     *
     * @return {@code {resultCount, facets: {key: [{value, count}]}}}
     */
    public Map<String, Object> facetCounts(CompiledQuery query, List<String> keys, int limit)
            throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resultCount", countMatching(query));
        Map<String, List<Map<String, Object>>> facets = new LinkedHashMap<>();
        for (String key : keys) {
            if (key == null || key.isBlank() || FACET_EXCLUDED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Facet key not allowed: " + key);
            }
            facets.put(key, facetCountsFor(key, query, limit));
        }
        payload.put("facets", facets);
        return payload;
    }

    private List<Map<String, Object>> facetCountsFor(String key, CompiledQuery query, int limit)
            throws SQLException {
        // Derived-key facets bind ONLY the fragment params (then the LIMIT);
        // property-key facets bind the json path first, fragment params, LIMIT
        String sql;
        List<Object> params;
        switch (key) {
            case "folder" -> {
                params = new ArrayList<>(query.params());
                params.add(limit);
                sql = "SELECT COALESCE(NULLIF(rtrim(FILES.file_path, replace(FILES.file_path, '/', '')), ''), '(root)') AS facet, "
                        + "COUNT(*) AS cnt FROM FILES WHERE " + query.sql()
                        + " GROUP BY facet ORDER BY cnt DESC, facet ASC LIMIT ?";
            }
            case "modified" -> {
                params = new ArrayList<>(query.params());
                params.add(limit);
                sql = "SELECT COALESCE(CAST(strftime('%Y-%m', FILES.file_last_modified / 1000, 'unixepoch') AS TEXT), '(missing)') AS facet, "
                        + "COUNT(*) AS cnt FROM FILES WHERE " + query.sql()
                        + " GROUP BY facet ORDER BY cnt DESC, facet ASC LIMIT ?";
            }
            case "vault" -> {
                params = new ArrayList<>(query.params());
                params.add(limit);
                sql = "SELECT COALESCE(a.alias, '(main)') AS facet, COUNT(*) AS cnt "
                        + "FROM FILES LEFT JOIN VAULT_ALIASES a ON a.id = FILES.vault_alias_id "
                        + "WHERE " + query.sql()
                        + " GROUP BY facet ORDER BY cnt DESC, facet ASC LIMIT ?";
            }
            default -> {
                // tags + arbitrary property keys: element counting. The json
                // path binds FIRST (it precedes the fragment in the SQL text).
                params = new ArrayList<>();
                params.add(QueryCompiler.jsonPath(key));
                params.addAll(query.params());
                params.add(limit);
                sql = "SELECT COALESCE(CAST(je.value AS TEXT), '(missing)') AS facet, COUNT(*) AS cnt "
                        + "FROM FILES LEFT JOIN json_each(FILES.properties, ?) AS je "
                        + "WHERE " + query.sql()
                        + " GROUP BY facet ORDER BY cnt DESC, facet ASC LIMIT ?";
            }
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> bucket = new LinkedHashMap<>();
                    bucket.put("value", rs.getString("facet"));
                    bucket.put("count", rs.getLong("cnt"));
                    buckets.add(bucket);
                }
            }
        }
        return buckets;
    }

    /**
     * The row query's matching-file count — the drill-down headline number
     * facets rides with.
     */
    public long countMatching(CompiledQuery query) throws SQLException {
        String sql = "SELECT COUNT(*) FROM FILES WHERE " + query.sql();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            List<Object> params = query.params();
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** One note's one-hop link neighborhood, DISTINCT per direction, sorted by path. */
    public record RelatedEdges(List<String> outgoing, List<String> incoming) {}

    public RelatedEdges relatedNoteEdges(int fileId) throws SQLException {
        List<String> outgoing = edgeTargets(
                "SELECT DISTINCT f.file_path FROM LINKS l JOIN FILES f ON f.id = l.target_file_id "
                        + "WHERE l.source_file_id = ? ORDER BY f.file_path", fileId);
        List<String> incoming = edgeTargets(
                "SELECT DISTINCT f.file_path FROM LINKS l JOIN FILES f ON f.id = l.source_file_id "
                        + "WHERE l.target_file_id = ? ORDER BY f.file_path", fileId);
        return new RelatedEdges(outgoing, incoming);
    }

    private List<String> edgeTargets(String sql, int fileId) throws SQLException {
        List<String> targets = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, fileId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    targets.add(rs.getString("file_path"));
                }
            }
        }
        return targets;
    }

    /**
     * Notes with no LINKS row in either direction — the revisit queue.
     * Markdown notes only: binaries and cache rows are not notes. Offset
     * + count support the MCP orphans tool's paging shape (T1/B3).
     */
    public List<String> orphanPaths(int limit, int offset) throws SQLException {
        String sql = "SELECT file_path FROM FILES f WHERE LOWER(f.file_name_ext) LIKE '%.md' "
                + "AND NOT EXISTS (SELECT 1 FROM LINKS l WHERE l.source_file_id = f.id) "
                + "AND NOT EXISTS (SELECT 1 FROM LINKS l WHERE l.target_file_id = f.id) "
                + "ORDER BY file_path LIMIT ? OFFSET ?";
        List<String> orphanPaths = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, limit);
            pstmt.setObject(2, Math.max(0, offset));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    orphanPaths.add(rs.getString("file_path"));
                }
            }
        }
        return orphanPaths;
    }

    /** Total orphan count (islands), independent of any limit. */
    public long orphanCount() throws SQLException {
        String sql = "SELECT COUNT(*) FROM FILES f WHERE LOWER(f.file_name_ext) LIKE '%.md' "
                + "AND NOT EXISTS (SELECT 1 FROM LINKS l WHERE l.source_file_id = f.id) "
                + "AND NOT EXISTS (SELECT 1 FROM LINKS l WHERE l.target_file_id = f.id)";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** FILES.id for an exact vault-relative path, or null. */
    public Integer fileIdByPath(String path) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id FROM FILES WHERE file_path = ?")) {
            pstmt.setString(1, path);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt("id") : null;
            }
        }
    }

    /** Every LINKS edge as (sourceId, targetId) pairs. */
    public List<int[]> linkEdges() throws SQLException {
        List<int[]> edges = new ArrayList<>();
        String sql = "SELECT source_file_id, target_file_id FROM LINKS";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                edges.add(new int[]{rs.getInt(1), rs.getInt(2)});
            }
        }
        return edges;
    }

    /**
     * Id sets reachable within {@code hops} link edges (both directions) of
     * {@code fileId}, deduped, capped at {@code cap} ids including the start.
     */
    public List<Integer> neighborhoodIds(int fileId, int hops, int cap) throws SQLException {
        Map<Integer, Set<Integer>> out = new HashMap<>();
        Map<Integer, Set<Integer>> in = new HashMap<>();
        for (int[] edge : linkEdges()) {
            out.computeIfAbsent(edge[0], k -> new HashSet<>()).add(edge[1]);
            in.computeIfAbsent(edge[1], k -> new HashSet<>()).add(edge[0]);
        }
        Set<Integer> result = new LinkedHashSet<>(Set.of(fileId));
        Set<Integer> frontier = Set.of(fileId);
        for (int h = 0; h < Math.max(0, hops); h++) {
            if (frontier.isEmpty()) {
                break;
            }
            Set<Integer> next = new LinkedHashSet<>();
            for (int id : frontier) {
                for (int neighbor : out.getOrDefault(id, Set.of())) {
                    if (result.size() >= cap) {
                        return List.copyOf(result);
                    }
                    if (result.add(neighbor)) {
                        next.add(neighbor);
                    }
                }
                for (int neighbor : in.getOrDefault(id, Set.of())) {
                    if (result.size() >= cap) {
                        return List.copyOf(result);
                    }
                    if (result.add(neighbor)) {
                        next.add(neighbor);
                    }
                }
            }
            frontier = next;
        }
        return List.copyOf(result);
    }

    /** Shared mapper for row-level JSON parsing in read helpers. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * FILES row for an id: parsed properties alongside the id/path.
     */
    public record IdRow(int id, String path, Map<String, Object> properties) {}

    // --- health facts (MCP health tool; reads only) ---

    /**
     * Health counters: C-speed COUNT/MAX scans plus one O(1) FTS probe.
     * Read-only, no caches, no hygiene passes. The DAO returns facts — the
     * ok/degraded judgment lives in the repository's healthSnapshot.
     */
    public Map<String, Object> stats() throws SQLException {
        Map<String, Object> stats = new LinkedHashMap<>();

        List<Map<String, Object>> byAlias = new ArrayList<>();
        long totalFiles = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COALESCE(a.alias, '(main)') AS label, COUNT(f.id) AS cnt "
                             + "FROM FILES f LEFT JOIN VAULT_ALIASES a ON a.id = f.vault_alias_id "
                             + "GROUP BY COALESCE(a.alias, '(main)'), a.id ORDER BY label")) {
            while (rs.next()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("alias", rs.getString("label"));
                entry.put("files", rs.getLong("cnt"));
                totalFiles += rs.getLong("cnt");
                byAlias.add(entry);
            }
        }
        stats.put("byAlias", byAlias);
        stats.put("files", totalFiles);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(file_last_modified) FROM FILES")) {
            rs.next();
            long max = rs.getLong(1);
            stats.put("newestModifiedMs", rs.wasNull() ? 0L : max);
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM FILES WHERE properties IS NULL")) {
            rs.next();
            stats.put("unparsedRows", rs.getLong(1));
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM LINKS")) {
            rs.next();
            stats.put("links", rs.getLong(1));
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) AS total, "
                             + "SUM(CASE WHEN generated_content = 'ERROR' THEN 1 ELSE 0 END) AS errors FROM EMBEDS")) {
            rs.next();
            stats.put("embedSeeds", rs.getLong("total"));
            long errors = rs.getLong("errors");
            stats.put("embedErrors", rs.wasNull() ? 0 : errors);
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 FROM files_fts LIMIT 1")) {
            stats.put("ftsProbe", rs.next() ? "ok" : "empty");
        } catch (SQLException ftsDown) {
            stats.put("ftsProbe", "missing");
        }
        return stats;
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {};

    /**
     * FILES rows for exact ids: parsed properties, no FTS join, no
     * backlink derivation (the caller enriches from edge sets).
     * Sorted by path for deterministic output.
     */
    public List<IdRow> filesByIds(Collection<Integer> ids) throws SQLException {
        List<IdRow> files = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return files;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT id, file_path, properties FROM FILES WHERE id IN (" + placeholders + ") ORDER BY file_path";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int index = 1;
            for (Integer id : ids) {
                pstmt.setInt(index++, id);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("file_path");
                    String json = rs.getString("properties");
                    Map<String, Object> props = new LinkedHashMap<>();
                    try {
                        Map<String, Object> parsed = JSON.readValue(json, MAP_TYPE);
                        if (parsed != null) {
                            props.putAll(parsed);
                        }
                    } catch (Exception e) {
                        logger.warn("Corrupt properties JSON in id set member: {}", path);
                    }
                    files.add(new IdRow(rs.getInt("id"), path, props));
                }
            }
        }
        return files;
    }
}
