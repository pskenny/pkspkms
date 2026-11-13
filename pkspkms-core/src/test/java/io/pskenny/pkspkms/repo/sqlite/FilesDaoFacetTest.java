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

package io.pskenny.pkspkms.repo.sqlite;

import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.QueryCompiler;
import io.pskenny.pkspkms.repo.query.QueryParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Facet/related/orphans SQL surface, driven through compiled WHERE fragments —
 * the same fragments row search uses, so property probes and text: FTS probes
 * compose inside GROUP BY counts for free. Runs against a real in-memory v3
 * schema (FILES body column + external-content files_fts + trigger sync).
 */
public class FilesDaoFacetTest {

    private Connection conn;
    private FilesDao dao;
    private final QueryParser parser = new QueryParser();
    private final QueryCompiler compiler = new QueryCompiler();

    // Fixture mtimes: 2025-12-29 and 2025-11-30 (UTC month buckets differ)
    private static final long MTIME_A = 1767000000000L;
    private static final long MTIME_B = 1764500000000L;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE FILES (id INTEGER PRIMARY KEY AUTOINCREMENT, vault_alias_id INTEGER, file_path TEXT NOT NULL UNIQUE, file_name TEXT, file_name_ext TEXT, file_last_modified INTEGER, properties json, blake3 TEXT, body TEXT NOT NULL DEFAULT '');");
            stmt.execute("CREATE TABLE VAULT_ALIASES (id INTEGER PRIMARY KEY AUTOINCREMENT, alias TEXT NOT NULL UNIQUE, directory TEXT NOT NULL, is_virtual INTEGER NOT NULL DEFAULT 0, created_at INTEGER);");
            stmt.execute("CREATE TABLE LINKS (source_file_id INTEGER, target_file_id INTEGER, link_type TEXT, PRIMARY KEY (source_file_id, target_file_id, link_type));");
            stmt.execute("CREATE VIRTUAL TABLE files_fts USING fts5(body, content='FILES', content_rowid='id', tokenize='unicode61');");
            stmt.execute("CREATE TRIGGER files_ai AFTER INSERT ON FILES WHEN new.body IS NOT NULL BEGIN "
                    + "INSERT INTO files_fts(rowid, body) VALUES (new.id, new.body); END;");

            stmt.execute("INSERT INTO VAULT_ALIASES (alias, directory, is_virtual) VALUES ('gwern', '', 1);");
            insert("rust.md", null, MTIME_A, "{\"filePath\": \"rust.md\", \"tags\": [\"rust\", \"code\"], \"type\": \"Note\"}", "Ownership matters in Rust.");
            insert("notes/java.md", 1, MTIME_B, "{\"filePath\": \"notes/java.md\", \"tags\": [\"code\"], \"status\": \"done\"}", "java ownership is manual.");
            insert("solo.md", null, null, "{\"filePath\": \"solo.md\"}", "nothing to see here.");

            // A -> B on both link types: DISTINCT must collapse to one edge
            insertLink(1, 2, "outgoing");
            insertLink(1, 2, "wikilink");
        }
        dao = new FilesDao(conn);
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private void insert(String path, Integer aliasId, Long mtime, String properties, String body) throws SQLException {
        String nameExt = path.substring(path.lastIndexOf('/') + 1);
        String name = nameExt.replaceFirst("[.][^.]+$", "");
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO FILES (file_path, file_name, file_name_ext, vault_alias_id, file_last_modified, properties, body) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ins.setString(1, path);
            ins.setString(2, name);
            ins.setString(3, nameExt);
            if (aliasId == null) {
                ins.setNull(4, java.sql.Types.INTEGER);
            } else {
                ins.setInt(4, aliasId);
            }
            if (mtime == null) {
                ins.setNull(5, java.sql.Types.INTEGER);
            } else {
                ins.setLong(5, mtime);
            }
            ins.setString(6, properties);
            ins.setString(7, body);
            ins.executeUpdate();
        }
    }

    private void insertLink(int source, int target, String type) throws SQLException {
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO LINKS (source_file_id, target_file_id, link_type) VALUES (?, ?, ?)")) {
            ins.setInt(1, source);
            ins.setInt(2, target);
            ins.setString(3, type);
            ins.executeUpdate();
        }
    }

    private CompiledQuery compile(String q) {
        return compiler.compile(parser.parse(q));
    }

    // Facet buckets as value/count strings for compact assertions
    private List<String> buckets(List<Map<String, Object>> facet) {
        return facet.stream()
                .map(entry -> entry.get("value") + "|" + entry.get("count"))
                .toList();
    }

    // --- facets ---

    @Test
    @DisplayName("tags facet counts per element; absent property lands in (missing)")
    void tagsFacetElementCounting() throws SQLException {
        Map<String, Object> payload = dao.facetCounts(compile(""), List.of("tags"), 10);
        List<Map<String, Object>> tags = asFacet(payload, "tags");
        assertEquals(List.of("code|2", "(missing)|1", "rust|1"), buckets(tags),
                "multi-value element counting; (missing) bucket ordered by value asc");
    }

    @Test
    @DisplayName("text: FTS probe composes inside the facet WHERE fragment")
    void facetsComposeWithTextProbe() throws SQLException {
        CompiledQuery probe = compile("text:ownership");
        assertTrue(probe.textProbe(), "fixture driven by a text probe");
        Map<String, Object> payload = dao.facetCounts(probe, List.of("tags"), 10);
        assertEquals(2L, payload.get("resultCount"), "both bodies mention ownership");
        assertEquals(List.of("code|2", "rust|1"), buckets(asFacet(payload, "tags")),
                "solo.md (no tags) is outside the result set, so no (missing) bucket");
    }

    @Test
    @DisplayName("folder facet derives directory prefixes; root files collapse to (root)")
    void folderFacet() throws SQLException {
        Map<String, Object> payload = dao.facetCounts(compile(""), List.of("folder"), 10);
        assertEquals(List.of("(root)|2", "notes/|1"), buckets(asFacet(payload, "folder")));
    }

    @Test
    @DisplayName("modified facet buckets by month; absent mtime lands in (missing)")
    void modifiedFacetMonthBuckets() throws SQLException {
        Map<String, Object> payload = dao.facetCounts(compile(""), List.of("modified"), 10);
        assertEquals(List.of("(missing)|1", "2025-11|1", "2025-12|1"), buckets(asFacet(payload, "modified")),
                "equal counts order by value asc: '(' sorts before digits");
    }

    @Test
    @DisplayName("vault facet resolves aliases; main-vault rows land in (main)")
    void vaultFacet() throws SQLException {
        Map<String, Object> payload = dao.facetCounts(compile(""), List.of("vault"), 10);
        assertEquals(List.of("(main)|2", "gwern|1"), buckets(asFacet(payload, "vault")));
    }

    @Test
    @DisplayName("arbitrary property keys degrade cleanly: scalar counting + (missing)")
    void arbitraryPropertyKeyFacet() throws SQLException {
        Map<String, Object> payload = dao.facetCounts(compile(""), List.of("status"), 10);
        assertEquals(List.of("(missing)|2", "done|1"), buckets(asFacet(payload, "status")),
                "(missing) has the higher count, so count-desc puts it first");
    }

    @Test
    @DisplayName("limit trims buckets to the top-N by count")
    void facetLimit() throws SQLException {
        Map<String, Object> payload = dao.facetCounts(compile(""), List.of("tags"), 1);
        assertEquals(List.of("code|2"), buckets(asFacet(payload, "tags")));
    }

    @Test
    @DisplayName("excluded keys and whitespace-only keys are rejected")
    void excludedKeysRejected() {
        for (String bad : List.of("text", "body", "filePath", "links", "backlinks", " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> dao.facetCounts(compile(""), List.of(bad), 10),
                    "facet key must be rejected: " + bad);
        }
    }

    // --- related / orphans ---

    @Test
    @DisplayName("related returns outgoing + incoming paths; DISTINCT collapses double rows")
    void relatedEdgesBothDirections() throws SQLException {
        FilesDao.RelatedEdges a = dao.relatedNoteEdges(1);
        assertEquals(List.of("notes/java.md"), a.outgoing());
        assertTrue(a.incoming().isEmpty());

        FilesDao.RelatedEdges b = dao.relatedNoteEdges(2);
        assertTrue(b.outgoing().isEmpty());
        assertEquals(List.of("rust.md"), b.incoming());
    }

    @Test
    @DisplayName("orphan paths: no LINKS row in either direction; binaries excluded")
    void orphanPaths() throws SQLException {
        // A binary (not a note) with no edges must stay out of the revisit queue
        insert("img.png", null, null, "{\"filePath\": \"img.png\"}", "");
        assertEquals(List.of("solo.md"), dao.orphanPaths(10, 0));
    }

    @Test
    @DisplayName("orphan limit caps the list; offset pages it; count totals")
    void orphanLimit() throws SQLException {
        // LIMIT 0 returns none — the cap is honored before volume
        assertEquals(List.of(), dao.orphanPaths(0, 0));
        // offset pages deterministically
        assertEquals(List.of(), dao.orphanPaths(10, 1), "offset 1 skips the only note");
        assertEquals(1, dao.orphanCount());
    }

    @Test
    @DisplayName("fileIdByPath hits exact paths only")
    void fileIdByPath() throws SQLException {
        assertEquals(1, dao.fileIdByPath("rust.md"));
        assertEquals(null, dao.fileIdByPath("rust.mdx"));
    }

    // alias (frontmatter `aliases`) is plain queryable data now; its
    // resolution-tier SQL probes are retired with the tier itself

    private List<Map<String, Object>> asFacet(Map<String, Object> payload, String key) {
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> facets =
                (Map<String, List<Map<String, Object>>>) payload.get("facets");
        return facets.get(key);
    }
}
