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

package io.pskenny.pkspkms.repo.query;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

// FTS5 text: probes — compile shape, runtime semantics on a real FTS5 index,
// snippet column, and NOT-parity with bodyless rows. Uses the v3 schema shape
// (FILES.body column + external-content files_fts + trigger sync).
public class FTSSearchTest {

    private Connection conn;
    private final QueryParser parser = new QueryParser();
    private final QueryCompiler compiler = new QueryCompiler();

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement stmt = conn.createStatement()) {
            // v3 FILES shape (minimal columns for search + FTS)
            stmt.execute("CREATE TABLE FILES (id INTEGER PRIMARY KEY AUTOINCREMENT, file_path TEXT UNIQUE, properties json, body TEXT);");
            stmt.execute("CREATE VIRTUAL TABLE files_fts USING fts5(body, content='FILES', content_rowid='id', tokenize='unicode61');");
            stmt.execute("CREATE TRIGGER files_ai AFTER INSERT ON FILES WHEN new.body IS NOT NULL BEGIN "
                    + "INSERT INTO files_fts(rowid, body) VALUES (new.id, new.body); END;");
            stmt.execute("CREATE TRIGGER files_ad AFTER DELETE ON FILES WHEN old.body IS NOT NULL BEGIN "
                    + "INSERT INTO files_fts(files_fts, rowid, body) VALUES('delete', old.id, old.body); END;");
            stmt.execute("CREATE TRIGGER files_au AFTER UPDATE ON FILES BEGIN "
                    + "INSERT INTO files_fts(files_fts, rowid, body) VALUES('delete', old.id, old.body); "
                    + "INSERT INTO files_fts(rowid, body) VALUES (new.id, new.body); END;");

            // fixture: three notes, one binary (NULL body)
            insert("rust.md", "{\"filePath\": \"rust.md\", \"tags\": [\"rust\", \"code\"]}", "Ownership matters.\nRust avoids data races.\nMore rust notes.");
            insert("java.md", "{\"filePath\": \"java.md\", \"tags\": [\"code\"]}", "Java ownership is manual.");
            insert("empty.md", "{\"filePath\": \"empty.md\"}", "");
            // Production shape: binaries carry '' (v3 NOT NULL DEFAULT), not NULL —
            // the text:* probe must exclude them (bodylessRows assertion below)
            insert("photo.png", "null", "");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private void insert(String path, String propertiesJson, String body) throws SQLException {
        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO FILES (file_path, properties, body) VALUES (?, ?, ?)")) {
            ins.setString(1, path);
            ins.setString(2, propertiesJson);
            if (body == null) {
                ins.setNull(3, java.sql.Types.VARCHAR);
            } else {
                ins.setString(3, body);
            }
            ins.executeUpdate();
        }
    }

    private List<String> query(String q) throws SQLException {
        var compiled = compiler.compile(parser.parse(q));
        // property-only SQL must stay free of the FTS join
        if (!q.contains("text:")) {
            assertFalse(compiled.textProbe(), "textProbe must be off for: " + q);
        }
        List<Object> params = compiled.params();
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM FILES WHERE " + compiled.sql())) {
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                List<String> paths = new ArrayList<>();
                while (rs.next()) {
                    paths.add(rs.getString("file_path"));
                }
                return paths;
            }
        }
    }

    private Map<String, String> queryWithSnippets(String q) throws SQLException {
        var compiled = compiler.compile(parser.parse(q));
        assertTrue(compiled.textProbe(), "text probe expected");
        List<Object> params = compiled.params();
        // mirror searchRegular: FTS join + snippet column when a text probe exists
        String sql = "SELECT FILES.*, snippet(files_fts, 0, '==', '==', '…', 12) AS textsnippet "
                + "FROM FILES LEFT JOIN files_fts ON files_fts.rowid = FILES.id WHERE "
                + compiled.sql() + " ORDER BY files_fts.rowid IS NULL, bm25(files_fts)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                Map<String, String> map = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    map.put(rs.getString("file_path"), rs.getString("textsnippet"));
                }
                return map;
            }
        }
    }

    @Test
    void debugPrint() throws SQLException {
        try {
            query("tags:code AND text:ownership");
        } catch (Exception e) {
            System.out.println("query failed: " + e.getMessage());
        }
        var c1 = compiler.compile(parser.parse("tags:code AND text:ownership"));
        System.out.println("SQL1=" + c1.sql());
    }

    // --- semantics ---

    @Test
    void singleTokenMatchesWordOccurrences() throws SQLException {
        assertEquals(List.of("rust.md"), query("text:rust"));
    }

    @Test
    void tokenIsWholeWord() throws SQLException {
        // unicode61 tokens whole words: 'own' does not match 'ownership'
        assertTrue(query("text:own").isEmpty(), "no partial-word matches");
    }

    @Test
    void multiWordQueryImplicitAnd() throws SQLException {
        // unquoted multi-token text: lexes as two clauses; AND-semantics fall out
        assertEquals(List.of("rust.md"), query("text:rust AND text:ownership"));
        assertEquals(List.of("java.md"), query("text:java AND text:ownership"));
    }

    @Test
    void quotedPhraseMatchesPhrase() throws SQLException {
        assertEquals(List.of("rust.md"), query("text:\"Rust avoids data races\""));
        assertTrue(query("text:\"avoids races\"").isEmpty(), "phrase order preserved");
    }

    @Test
    void suffixWildcardMatchesPrefix() throws SQLException {
        assertEquals(List.of("rust.md", "java.md"), query("text:own*"));
    }

    @Test
    void interiorWildcardRejectedAtParse() {
        assertQueryFails("text:ru*st");
        assertQueryFails("text:ru?st");
    }

    @Test
    void rangesRejectedAtParse() {
        assertQueryFails("text:[1 TO 3]");
    }

    @Test
    void bodylessRowsNeverMatchButNotMatchesThem() throws SQLException {
        // binary/empty rows are invisible to text probes
        assertFalse(query("text:rust").contains("photo.png"));
        assertFalse(query("text:rust").contains("empty.md"));
        // NOT parity (B56): file-absent rows match under NOT
        assertTrue(query("not text:rust").contains("photo.png"));
        assertTrue(query("not text:rust").contains("empty.md"));
        assertFalse(query("not text:rust").contains("rust.md"));
    }

    @Test
    void composesWithPropertyClauses() throws SQLException {
        // fixture truth: both rust.md and java.md are tagged code AND contain
        // the word "ownership" in their bodies
        assertEquals(List.of("rust.md", "java.md"), query("tags:code AND text:ownership"));
        assertEquals(List.of("java.md"), query("tags:code AND text:java"));
        // NOT composition: code-tagged minus ownership → empty
        assertTrue(query("tags:code AND not text:ownership").isEmpty());
    }

    @Test
    void bodyExistsMatchesAnythingIndexed() throws SQLException {
        // text:* = "has body text": `FILES.body <> ''` — in production binaries
        // hold '' (NOT NULL DEFAULT), so the old `IS NOT NULL` probe counted
        // every .mp4/.png row; empty markdown bodies are excluded with them
        var hits = query("text:*");
        assertTrue(hits.contains("rust.md"), hits.toString());
        assertTrue(hits.contains("java.md"), hits.toString());
        assertFalse(hits.contains("photo.png"), "'' binary body never matches");
        assertFalse(hits.contains("empty.md"), "'' empty body never matches");
    }

    @Test
    void updateKeepsIndexInSync() throws SQLException {
        assertEquals(List.of("rust.md", "java.md"), query("text:ownership"));

        // edit: ownership removed from rust.md body
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE FILES SET body = 'The old subject moved away.' WHERE file_path = 'rust.md'");
        }
        var afterEdit = query("text:ownership");
        assertFalse(afterEdit.contains("rust.md"), afterEdit.toString());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE FILES SET body = 'Ownership moved back to rust.' WHERE file_path = 'rust.md'");
        }
        var afterRestore = query("text:ownership");
        assertTrue(afterRestore.contains("rust.md"), afterRestore.toString());
    }

    @Test
    void deleteRemovesFromIndex() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM FILES WHERE file_path = 'rust.md'");
        }
        assertFalse(query("text:rust").contains("rust.md"));
    }

    @Test
    void snippetsEmittedForTextMatches() throws SQLException {
        var snippets = queryWithSnippets("text:ownership");
        assertEquals(2, snippets.size());
        assertTrue(snippets.get("rust.md").contains("Ownership"), snippets.get("rust.md"));
        assertTrue(snippets.get("java.md").contains("ownership"), snippets.get("java.md"));
    }

    private void assertQueryFails(String q) {        try {
            compiler.compile(parser.parse(q));
            throw new AssertionError("expected QueryParseException for: " + q);
        } catch (QueryParseException expected) {
            // pass
        }
    }
}
