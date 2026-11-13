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

package io.pskenny.pkspkms.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static io.pskenny.pkspkms.test.FileUtil.createFile;
import io.pskenny.pkspkms.test.VaultBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatcher-level MCP tests against a real repository. HTTP-level tests
 * (auth, 405, 202) live in ServerTest; this class pins the JSON-RPC semantics.
 */
public class McpDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SQLitePksFileRepository repository;
    private McpDispatcher dispatcher;
    private Path vaultDir;
    private Path virtualDir;

    @BeforeEach
    void setup() throws IOException {
        vaultDir = tempDir.resolve("vault");
        virtualDir = tempDir.resolve("virtual");
        Files.createDirectories(vaultDir);
        Files.createDirectories(virtualDir.resolve("sub"));

        VaultBuilder vault = VaultBuilder.at(vaultDir);
        vault
                .note("test.md", Map.of("tags", "test"), "- [ ] tag side task\n[[test2]] body")
                .note("test2.md", Map.of("tags", "test", "status", "done"), "");
        // alias fixture: frontmatter aliases stay parsed as queryable data
        // (the resolution tier is retired — nothing targets them here either)
        vault.note("finance.md", Map.of("aliases", java.util.List.of("Finances", "Budget")), "finance note");
        // task fixture: open + done + a fenced demo that must not extract
        vault.md("tasks.md", Map.of(), """
                - [ ] open task
                - [x] done task
                ```markdown
                - [ ] demo task
                ```
                """);
        // backlinks fixture: hub target (aliased) with canonical, alias, and
        // fence-masked referrers
        vault.note("hub.md", Map.of("aliases", java.util.List.of("HubAlias")), "hub note");
        vault.note("refA.md", Map.of(), "- [ ] real task\nsee [[hub|Hub_doc]] now");
        vault.note("refB.md", Map.of(), "via [[HubAlias]] alias\n[markdown](hub.md) ignored");
        vault.note("refC.md", Map.of(), "[[hub]] real\n```markdown\n[[hub]] demo inside fence\n```\n");
        vault.note("refD.md", Map.of(), "- [ ] refD task\ninline `[[hub]]` ghost stays\nreal [[hub]] here");
        // dead-markdown fixture: unresolvable link dest + missing image dest,
        // one alive markdown link to prove resolution skims the dead ones
        vault.note("deadmd.md", Map.of(),
                "[missing](dead.md)\n![x](phantom.svg)\n[alive](hub.md)\n");
        // views fixture: base + luabase fences — the embed spans report
        vault.md("views.md", Map.of(), """
                raw intro
                ```base
                filters:
                  and: [Tags.views]
                ```
                ```luabase
                properties = {}
                ```
                tail
                """);
        // sections fixture: frontmatter-carrying note with a fenced block
        // containing a fake heading — the fence must not register boundaries
        vault.md("sections.md", Map.of("tags", "sections"), """
                body under the stamp
                ```markdown
                # Fake Heading
                ```
                tail under the stamp
                """);
        // broken-links fixture: near-miss cluster, a second bucket, a fenced
        // non-target, and refB's [[HubAlias]] alias-target dangler (below)
        vault.note("dangly.md", Map.of(), "points at [[Ghost]] and [[ghost]] and [[Ghost2]]");
        vault.note("dangly2.md", Map.of(), "also [[Ghost]] plus no dangling [markdown](nowhere.md)\n\n```markdown\n[[FencedGhost]]\n```");
        // fm-link fixture (v7): frontmatter wikilink-shaped values join the
        // graph — one resolved, one dangling (wiki side owns it), one rotting
        // anchor (anchor-rot side owns it), one display split. Export leaves
        // the region literal (its own pin lives in ExportTest).
        createFile(vaultDir, "fmlink.md", Map.of(
                        "parent", "rf [[hub]]",
                        "ghostref", "gg [[Ghost]]",
                        "relative", "aa [[hub#No Such Heading]] bb"),
                "plain body");
        // anchor-rot fixture: existing target with a wrong anchor, a matching
        // anchor (must NOT rot), and no anchored danglers (wiki section owns those)
        vault.note("anchorrot.md", Map.of(),
                "rot [[hub#Wrong Heading]]\n"
                + "fact [[hub#This is the body content for: hub.md]] ok\n");
        Files.writeString(vaultDir.resolve(".obsidian-not-secret"), "decoy");

        PkmsFileSystem fs = new JavaFileSystem(vaultDir.toFile());
        repository = new SQLitePksFileRepository(
                "jdbc:sqlite:" + tempDir.resolve("test.db"), null, fs);
        repository.loadDirectoryIntoRepository();

        createFile(virtualDir, "virtual-note.md", Map.of("tags", "virtual"), "virtual content");
        repository.loadVirtualVault(new JavaFileSystem(virtualDir.toFile()), "gwern");

        dispatcher = new McpDispatcher(repository);
    }

    private JsonNode dispatch(String body) throws IOException {
        String response = dispatcher.handle(body);
        return response == null ? null : MAPPER.readTree(response);
    }

    private JsonNode callTool(int id, String tool, Map<String, Object> args) throws IOException {
        return dispatch(String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":%s,\"method\":\"tools/call\",\"params\":{\"name\":\"%s\",\"arguments\":%s}}",
                id, tool, MAPPER.writeValueAsString(args)));
    }

    @Test
    @DisplayName("initialize echoes a supported client protocol version")
    void initializeEchoesSupportedVersion() throws IOException {
        JsonNode response = dispatch("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}
                """);
        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals(1, response.get("id").asInt());
        assertEquals("2025-06-18", response.get("result").get("protocolVersion").asText());
        assertTrue(response.get("result").get("capabilities").has("tools"), "tools capability advertised");
        assertTrue(response.get("result").get("capabilities").has("resources"), "resources capability advertised");
        assertEquals("pkspkms", response.get("result").get("serverInfo").get("name").asText());
    }

    @Test
    @DisplayName("initialize negotiates the latest version for an unknown request")
    void initializeNegotiatesLatestForUnknownVersion() throws IOException {
        JsonNode response = dispatch("""
                {"jsonrpc":"2.0","id":"abc","method":"initialize","params":{"protocolVersion":"1999-01-01"}}
                """);
        assertEquals("abc", response.get("id").asText(), "string ids echo as strings");
        assertEquals(McpDispatcher.LATEST_PROTOCOL_VERSION, response.get("result").get("protocolVersion").asText());
    }

    @Test
    @DisplayName("T1 flex-1: read_file section slices by heading; fences don't fake boundaries")
    void readFileSection() throws IOException {
        // test.md body (post-frontmatter): "# This is the body content for: test.md"
        // heading line then "- [ ] tag side task\n[[test2]] body"
        JsonNode slice = callTool(60, "read_file",
                Map.of("path", "test.md", "section", "This is the body content for: test.md"));
        assertFalse(slice.get("result").get("isError").asBoolean());
        String body = slice.get("result").get("content").get(0).get("text").asText();
        assertTrue(body.startsWith("# This is the body content for: test.md"), body);
        assertTrue(body.contains("[[test2]] body"), body);

        // case-insensitive, first match wins — not-found errors with hints
        JsonNode hint = callTool(61, "read_file",
                Map.of("path", "test.md", "section", "no such heading here"));
        assertTrue(hint.get("result").get("isError").asBoolean());
        assertTrue(hint.get("result").get("content").toString().contains("headings:"),
                "not-found names candidates from the note's outline");

        // head caps apply after slicing
        JsonNode capped = callTool(62, "read_file",
                Map.of("path", "test.md", "section", "This is the body content for: test.md",
                        "head_lines", 1));
        String cappedBody = capped.get("result").get("content").get(0).get("text").asText();
        assertTrue(cappedBody.startsWith("# This is the body content for: test.md"), cappedBody);
        assertTrue(cappedBody.contains("… [truncated"), "caps apply after slicing with the visible marker");

        // fenced fake headings are not boundaries: sections.md carries a
        // ```markdown block with "# Fake Heading" inside
        JsonNode fenceSlice = callTool(63, "read_file",
                Map.of("path", "sections.md", "section", "This is the body content for: sections.md"));
        assertFalse(fenceSlice.get("result").get("isError").asBoolean());
        String fenceBody = fenceSlice.get("result").get("content").get(0).get("text").asText();
        assertTrue(fenceBody.contains("```markdown") && fenceBody.contains("Fake Heading"),
                "fence preserved inside the slice: " + fenceBody);

        JsonNode fake = callTool(64, "read_file",
                Map.of("path", "sections.md", "section", "Fake Heading"));
        assertTrue(fake.get("result").get("isError").asBoolean(),
                "a heading inside a fence never opens a section");
    }

    @Test
    @DisplayName("v6: embeds reports embedded views; tasks carry line facts after re-extract")
    void embedsToolAndTaskLines() throws IOException {
        JsonNode payload = payloadOf(70, "embeds", Map.of());
        JsonNode embeds = payload.get("embeds");
        assertTrue(embeds.size() >= 1, payload.toString());
        // views.md carries two embeds: one base + one luabase, line-precise;
        // markdown fences (sections.md) and content are not embeds
        java.util.Map<String, String> byPathKind = new java.util.LinkedHashMap<>();
        for (JsonNode e : embeds) {
            byPathKind.put(e.get("filePath").asText() + "/" + e.get("kind").asText(),
                    e.get("line").asText());
        }
        assertEquals(java.util.Set.of("views.md/base", "views.md/luabase"),
                byPathKind.keySet(), "markdown fences are not embeds");
        assertEquals("2", byPathKind.get("views.md/base"), byPathKind.toString());
        assertEquals("6", byPathKind.get("views.md/luabase"), byPathKind.toString());
        assertEquals(2, payload.get("count").asInt());
        assertFalse(payload.get("has_more").asBoolean(), payload.toString());

        // tasks gain line facts when the index carried spans (v6 lever stamps
        // at re-extract; the test harness re-extracted at load)
        JsonNode tasks = payloadOf(71, "tasks", Map.of());
        JsonNode sideTask = null;
        for (JsonNode task : tasks.get("tasks")) {
            if ("test.md".equals(task.get("file").asText())
                    && "tag side task".equals(task.get("text").asText())) {
                sideTask = task;
                break;
            }
        }
        assertTrue(sideTask != null && sideTask.has("line") && sideTask.has("offset"),
                "span facts join: " + (sideTask == null ? "missing" : sideTask.toString()));
    }

    @Test
    @DisplayName("tools/list exposes the vault tools in stable order")
    void toolsListExposesExpectedTools() throws IOException {
        JsonNode response = dispatch("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """);
        JsonNode names = response.get("result").get("tools");
        assertEquals(java.util.List.of("search", "read_file", "graph", "manifest", "health", "resolve_wikilink",
                        "facets", "related", "backlinks", "outline", "orphans", "broken_links",
                        "tasks", "search_and_read", "recent", "graph_hops", "binary_file", "embeds"),
                java.util.stream.StreamSupport.stream(names.spliterator(), false)
                        .map(t -> t.get("name").asText()).toList());
        for (JsonNode tool : names) {
            assertTrue(tool.has("description"), tool.get("name").asText() + " must have a description");
            assertTrue(tool.has("inputSchema"), tool.get("name").asText() + " must have an inputSchema");
            assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean(),
                    tool.get("name").asText() + " must be annotated read-only");
        }
    }    @Test
    @DisplayName("ping answers an empty result")
    void pingAnswersEmptyResult() throws IOException {
        JsonNode response = dispatch("""
                {"jsonrpc":"2.0","id":3,"method":"ping"}
                """);
        assertTrue(response.get("result").isEmpty(), "ping result should be empty");
    }

    @Test
    @DisplayName("Notifications are accepted and produce no response")
    void notificationsProduceNoResponse() throws IOException {
        assertNull(dispatch("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """), "notification must map to no body (HTTP 202 at the transport)");
    }

    @Test
    @DisplayName("Unparseable body is a parse error (-32700) with null id")
    void parseError() throws IOException {
        JsonNode response = dispatch("not json at all");
        assertEquals(-32700, response.get("error").get("code").asInt());
        assertTrue(response.get("id").isNull());
    }

    @Test
    @DisplayName("Non-object bodies and missing methods are invalid requests (-32600)")
    void invalidRequests() throws IOException {
        assertEquals(-32600, dispatch("[]").get("error").get("code").asInt());
        assertEquals(-32600, dispatch("""
                {"jsonrpc":"2.0","id":4}
                """).get("error").get("code").asInt());
    }

    @Test
    @DisplayName("Unknown methods are method-not-found (-32601)")
    void unknownMethod() throws IOException {
        JsonNode response = dispatch("""
                {"jsonrpc":"2.0","id":5,"method":"resources/read","params":{}}
                """);
        assertEquals(-32601, response.get("error").get("code").asInt());
    }

    @Test
    @DisplayName("Unknown tools are invalid params (-32602)")
    void unknownTool() throws IOException {
        JsonNode response = dispatch("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"delete_everything","arguments":{}}}
                """);
        assertEquals(-32602, response.get("error").get("code").asInt());
        assertTrue(response.get("error").get("message").asText().contains("delete_everything"));
    }

    @Test
    @DisplayName("search runs the Lucene-style query and returns vault JSON")
    void searchReturnsVaultJson() throws IOException {
        JsonNode response = callTool(7, "search", Map.of("query", "tags:test"));
        assertFalse(response.get("result").get("isError").asBoolean());
        JsonNode payload = MAPPER.readTree(response.get("result").get("content").get(0).get("text").asText());
        assertEquals(2, payload.get("rows_returned").asInt(), "both tagged notes match");
        assertTrue(payload.get("files").get(0).has("filePath"));
    }

    @Test
    @DisplayName("search surfaces malformed queries as tool errors, not crashes")
    void searchMalformedQuery() throws IOException {
        JsonNode response = callTool(8, "search", Map.of("query", "tags:(("));
        assertTrue(response.get("result").get("isError").asBoolean());
        assertTrue(response.get("result").get("content").get(0).get("text").asText().contains("Bad query"));
    }

    @Test
    @DisplayName("read_file returns raw note content")
    void readFileReturnsContent() throws IOException {
        JsonNode response = callTool(9, "read_file", Map.of("path", "test.md"));
        assertFalse(response.get("result").get("isError").asBoolean());
        String text = response.get("result").get("content").get(0).get("text").asText();
        assertTrue(text.contains("[[test2]] body"), "raw markdown content expected");
    }

    @Test
    @DisplayName("read_file reads virtual vaults via @alias/ prefix")
    void readFileVirtualAlias() throws IOException {
        JsonNode response = callTool(10, "read_file", Map.of("path", "@gwern/virtual-note.md"));
        assertFalse(response.get("result").get("isError").asBoolean());
        assertTrue(response.get("result").get("content").get(0).get("text").asText().contains("virtual content"));
    }

    @Test
    @DisplayName("read_file rejects traversal, .obsidian/.trash, and unknown aliases")
    void readFileRejectsBadPaths() throws IOException {
        for (String bad : new String[]{"../test.db", "sub/../../../etc/passwd", ".obsidian/app.json",
                "@gwern/../test.db", "@nonsense/x.md"}) {
            JsonNode response = callTool(11, "read_file", Map.of("path", bad));
            assertTrue(response.get("result").get("isError").asBoolean(), bad + " must be rejected");
        }
    }

    @Test
    @DisplayName("graph reduces hits to graph keys only (links omitted when empty)")
    void graphReturnsGraphShape() throws IOException {
        JsonNode response = callTool(12, "graph", Map.of("query", "tags:test"));
        assertFalse(response.get("result").get("isError").asBoolean());
        JsonNode payload = MAPPER.readTree(response.get("result").get("content").get(0).get("text").asText());
        for (JsonNode file : payload.get("files")) {
            assertTrue(
                    java.util.Set.of("filePath", "links", "backlinks", "tags")
                            .containsAll(file.properties().stream().map(Map.Entry::getKey)
                                    .collect(java.util.stream.Collectors.toSet())),
                    "graph keys only, got: " + file.fieldNames());
        }
        assertTrue(payload.get("rows_returned").asInt() >= 1);
    }

    @Test
    @DisplayName("manifest groups virtual-vault files per alias")
    void manifestGroupsByAlias() throws IOException {
        JsonNode response = callTool(13, "manifest", Map.of());
        JsonNode payload = MAPPER.readTree(response.get("result").get("content").get(0).get("text").asText());
        assertTrue(payload.has("gwern"));
        assertEquals("@gwern/virtual-note.md", payload.get("gwern").get(0).get("filePath").asText());
    }

    @Test
    @DisplayName("resolve_wikilink resolves by file name and reports misses as errors")
    void resolveWikilink() throws IOException {
        JsonNode hit = callTool(14, "resolve_wikilink", Map.of("wikilink", "test2"));
        assertFalse(hit.get("result").get("isError").asBoolean());
        assertEquals("test2.md", hit.get("result").get("content").get(0).get("text").asText());

        JsonNode miss = callTool(15, "resolve_wikilink", Map.of("wikilink", "does-not-exist"));
        assertTrue(miss.get("result").get("isError").asBoolean());
    }

    @Test
    @DisplayName("aliases are data: resolve_wikilink and related no longer resolve them")
    void resolveWikilinkThroughAlias() throws IOException {
        JsonNode hit = callTool(16, "resolve_wikilink", Map.of("wikilink", "Finances"));
        assertTrue(hit.get("result").get("isError").asBoolean(),
                "frontmatter aliases lost the resolution tier; only names/paths resolve");

        JsonNode rel = callTool(17, "related", Map.of("path", "Budget"));
        assertFalse(rel.get("result").get("isError").asBoolean());
        JsonNode relPayload = MAPPER.readTree(rel.get("result").get("content").get(0).get("text").asText());
        assertFalse(relPayload.get("exists").asBoolean(),
                "alias input zero-results instead of resolving");
    }

    // --- search<->facets parity (bug-report class: scalar vs list-form values) ---

    @Test
    @DisplayName("exact-value search hits equal the facet bucket")
    void mcpFacetSearchParity() throws IOException {
        // fixture truth: test.md + test2.md carry tags:test (scalar "test"
        // frontmatter — list-form equivalence is pinned at the SQL layer by
        // QueryLanguageTest.semanticScalarOrArray)
        JsonNode searchRes = payloadOf(18, "search", Map.of("query", "tags:test"));
        int searchCount = searchRes.get("totalSize").asInt();

        JsonNode facets = payloadOf(19, "facets", Map.of(
                "query", "tags:test", "keys", java.util.List.of("tags")));
        int bucketCount = 0;
        for (JsonNode bucket : facets.get("facets").get("tags")) {
            if ("test".equals(bucket.get("value").asText())) {
                bucketCount = bucket.get("count").asInt();
            }
        }
        assertEquals(searchCount, bucketCount,
                "search hit count must equal the value's facet bucket");
    }

    @Test
    @DisplayName("list-form and scalar-form property values both match exact queries")
    void listAndScalarFormsMatch() throws IOException {
        // fixture truth: test.md/test2.md carry frontmatter tags lists, both tags:test
        JsonNode spaced = payloadOf(20, "search", Map.of("query", "tags: test"));
        JsonNode quoted = payloadOf(21, "search", Map.of("query", "tags:\"test\""));
        assertEquals(spaced.get("totalSize").asInt(), quoted.get("totalSize").asInt(),
                "spacing/quoting must not change match semantics");
        assertTrue(spaced.get("totalSize").asInt() >= 2, spaced.toString());
    }

    // --- read-only drill-down tools (facets / related / orphans / tasks) ---

    private JsonNode payloadOf(int id, String tool, Map<String, Object> args) throws IOException {
        JsonNode response = callTool(id, tool, args);
        assertFalse(response.get("result").get("isError").asBoolean(),
                response.get("result").toString());
        return MAPPER.readTree(response.get("result").get("content").get(0).get("text").asText());
    }

    @Test
    @DisplayName("facets returns per-value counts over a filtered result set")
    void facetsReturnCounts() throws IOException {
        JsonNode payload = payloadOf(16, "facets", Map.of(
                "query", "tags:test", "keys", java.util.List.of("tags", "type"), "limit", 5));
        assertEquals(2, payload.get("resultCount").asInt(), "both tags:test notes");
        JsonNode tags = payload.get("facets").get("tags");
        assertEquals(1, tags.size(), "the result set carries a single tag value");
        assertEquals("test", tags.get(0).get("value").asText());
        assertEquals(2, tags.get(0).get("count").asInt());
    }

    @Test
    @DisplayName("facets with no query covers the whole vault, keys default")
    void facetsWholeVault() throws IOException {
        JsonNode payload = payloadOf(17, "facets", Map.of());
        assertTrue(payload.has("facets"));
        JsonNode tags = payload.get("facets").get("tags");
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        tags.forEach(e -> values.add(e.get("value").asText()));
        assertTrue(values.containsAll(java.util.Set.of("test", "virtual")), values.toString());
        JsonNode folder = payload.get("facets").get("folder");
        java.util.Set<String> folders = new java.util.LinkedHashSet<>();
        folder.forEach(e -> folders.add(e.get("value").asText()));
        assertTrue(folders.contains("@gwern/"), "virtual-vault files derive their folder, " + folders);
    }

    @Test
    @DisplayName("facets rejects excluded keys as tool errors")
    void facetsRejectsExcludedKeys() throws IOException {
        for (String bad : java.util.List.of("filePath", "links", "text")) {
            JsonNode response = callTool(18, "facets",
                    Map.of("keys", java.util.List.of(bad)));
            assertTrue(response.get("result").get("isError").asBoolean(), bad + " must be rejected");
            assertTrue(response.get("result").get("content").get(0).get("text").asText().contains(bad));
        }
    }

    @Test
    @DisplayName("related returns one-hop neighborhood by path or wikilink")
    void relatedInOutgoingIncoming() throws IOException {
        JsonNode fromTest = payloadOf(19, "related", Map.of("path", "test.md"));
        assertEquals("test.md", fromTest.get("path").asText());
        assertEquals("test2.md", fromTest.get("outgoing").get(0).asText());
        assertTrue(fromTest.get("incoming").isEmpty());

        JsonNode fromTest2 = payloadOf(20, "related", Map.of("path", "test2.md"));
        assertEquals(1, fromTest2.get("incoming").size(), "test.md links here");
        assertTrue(fromTest2.get("outgoing").isEmpty());

        // wikilink-shaped input resolves to the same note
        JsonNode byWikilink = payloadOf(21, "related", Map.of("path", "test2"));
        assertEquals("test2.md", byWikilink.get("path").asText());
    }

    @Test
    @DisplayName("related reports unresolved inputs as zero-result with exists:false")
    void relatedUnresolved() throws IOException {
        JsonNode response = callTool(22, "related", Map.of("path", "not-a-note"));
        assertFalse(response.get("result").get("isError").asBoolean());
        JsonNode payload = MAPPER.readTree(response.get("result").get("content").get(0).get("text").asText());
        assertEquals("not-a-note", payload.get("path").asText());
        assertFalse(payload.get("exists").asBoolean(), "exists flags absence");
        assertEquals(0, payload.get("outgoing").size());
        assertEquals(0, payload.get("incoming").size());
    }

    @Test
    @DisplayName("orphans list notes with no link edges at all")
    void orphansList() throws IOException {
        JsonNode payload = payloadOf(23, "orphans", Map.of());
        java.util.List<String> paths = new java.util.ArrayList<>();
        payload.get("orphans").forEach(e -> paths.add(e.asText()));
        assertEquals(java.util.List.of("@gwern/virtual-note.md", "dangly.md", "dangly2.md",
                        "finance.md", "sections.md", "tasks.md", "views.md"),
                paths, "linked notes (test.md, test2.md, refs, hub) are excluded");
    }

    @Test
    @DisplayName("tasks lists open checkboxes with their note; done items stay out")
    void tasksOpenOnly() throws IOException {
        JsonNode payload = payloadOf(24, "tasks", Map.of());
        java.util.List<String> texts = new java.util.ArrayList<>();
        payload.get("tasks").forEach(e -> texts.add(e.get("file").asText() + " :: " + e.get("text").asText()));
        assertTrue(texts.contains("tasks.md :: open task"), texts.toString());
        assertTrue(texts.contains("test.md :: tag side task"), texts.toString());
        assertTrue(texts.stream().noneMatch(t -> t.contains("done task")), "done checkboxes excluded");
        assertTrue(texts.stream().noneMatch(t -> t.contains("demo task")), "fenced demos excluded");
    }

    @Test
    @DisplayName("tasks composes a user query with the open-task probe")
    void tasksWithQuery() throws IOException {
        JsonNode payload = payloadOf(25, "tasks", Map.of("query", "tags:test"));
        assertEquals(1, payload.get("tasks").size(), "only test.md carries tasks and tags:test");
        assertEquals("tag side task", payload.get("tasks").get(0).get("text").asText());
    }

    // --- backlinks: inbound edges + provenance (design A) ---

    @Test
    @DisplayName("backlinks returns canonical and fenced-referrer provenance")
    void backlinksProvenance() throws IOException {
        JsonNode payload = payloadOf(28, "backlinks", Map.of("path", "hub.md", "context_chars", 40));
        assertEquals("hub.md", payload.get("path").asText());
        assertEquals(7, payload.get("inbound_count").asInt(),
                "refB markdown + anchorrot [[hub#Wrong Heading]] + deadmd [alive](hub.md) + fmlink's frontmatter link (per-note dedupe: two spans, one edge)");

        java.util.List<String> sources = new java.util.ArrayList<>();
        Map<String, String> viaBySource = new java.util.LinkedHashMap<>();
        java.util.List<String> contexts = new java.util.ArrayList<>();
        for (JsonNode link : payload.get("links")) {
            sources.add(link.get("source").asText());
            viaBySource.put(link.get("source").asText(), link.get("via").asText());
            contexts.add(link.get("context").asText());
        }
        assertEquals(java.util.Set.of("refA.md", "refC.md", "refD.md", "anchorrot.md", "fmlink.md"),
                new java.util.HashSet<>(sources), sources.toString());
        assertEquals("canonical", viaBySource.get("refA.md"), "[[hub|Hub_doc]] resolves canonically");
        assertEquals("canonical", viaBySource.get("refD.md"), "real [[hub]] is canonical");
    }

    @Test
    @DisplayName("backlinks line numbers and context caps are honored")
    void backlinksLineAndCaps() throws IOException {
        JsonNode payload = payloadOf(29, "backlinks", Map.of("path", "hub.md", "context_chars", 10, "limit", 20));
        for (JsonNode link : payload.get("links")) {
            assertTrue(link.get("line").asInt() >= 1, "1-based line numbers");
            assertTrue(link.get("context").asText().length() <= 40, "short cap respected");
        }
        JsonNode capped = payloadOf(30, "backlinks", Map.of("path", "hub.md", "limit", 2));
        assertEquals(7, capped.get("inbound_count").asInt(), "count is pre-cap");
        assertEquals(2, capped.get("links").size(), "links capped");

        JsonNode unresolved = callTool(31, "backlinks", Map.of("path", "ghost"));
        assertFalse(unresolved.get("result").get("isError").asBoolean(), "missing target is a zero-result");
        JsonNode miss = MAPPER.readTree(unresolved.get("result").get("content").get(0).get("text").asText());
        assertFalse(miss.get("exists").asBoolean());
        assertEquals(0, miss.get("inbound_count").asInt());
    }

    @Test
    @DisplayName("backlinks zero-results alias input and vault-scopes its sources")
    void backlinksAliasAndVaultScoping() throws IOException {
        JsonNode viaAlias = callTool(32, "backlinks", Map.of("path", "HubAlias"));
        assertFalse(viaAlias.get("result").get("isError").asBoolean(), "zero-result, not throw");
        JsonNode viaPayload = MAPPER.readTree(viaAlias.get("result").get("content").get(0).get("text").asText());
        assertFalse(viaPayload.get("exists").asBoolean(),
                "alias-addressed input no longer resolves");

        JsonNode scoped = payloadOf(33, "backlinks", Map.of("path", "hub.md", "vault", "gwern"));
        assertTrue(scoped.get("links").isEmpty(), "all referrers live in the main vault");

        JsonNode bad = callTool(34, "backlinks", Map.of("path", "hub.md", "vault", "nonsense"));
        assertTrue(bad.get("result").get("isError").asBoolean(), "unknown alias rejected");
    }

    @Test
    @DisplayName("T1/A: empty search/graph queries match all; limit:0 counts; path is the one key")
    void emptyQueryAndCountIdiom() throws IOException {
        // A1: empty string and missing query = match-all (docs-promised)
        JsonNode all = payloadOf(50, "search", Map.of("query", ""));
        assertTrue(all.get("totalSize").asInt() >= 2, "empty query = match-all: " + all);
        assertTrue(all.get("files").size() >= 2);

        // A3: limit:0 → totals only (the count idiom)
        JsonNode count = payloadOf(51, "search", Map.of("query", "tags:test", "limit", 0));
        assertEquals(0, count.get("files").size());
        assertEquals(0, count.get("rows_returned").asInt());
        assertTrue(count.get("totalSize").asInt() >= 2);

        JsonNode graphAll = payloadOf(52, "graph", Map.of());
        assertTrue(graphAll.get("files").size() >= 1, "missing graph query = match-all");

        // T1-B2: the `note` key is gone — hard cutover, no alias window.
        // outline with only the old key fails loudly, naming `path`.
        JsonNode legacyKey = callTool(53, "outline", Map.of("note", "test.md"));
        assertTrue(legacyKey.get("result").get("isError").asBoolean(),
                "the note key is no longer accepted");
        assertTrue(legacyKey.get("result").get("content").toString().contains("path"),
                "the error names the expected key");

        // canonical key
        JsonNode canonical = payloadOf(54, "outline", Map.of("path", "test.md"));
        assertEquals("test.md", canonical.get("path").asText());
    }

    @Test
    @DisplayName("outline returns the leveled heading structure for a note")
    void outlineTool() throws IOException {
        // createFile frontmatter-nonnempty notes carry the appended
        // "# This is the body content for: <file>" heading (level 1)
        JsonNode payload = payloadOf(40, "outline", Map.of("path", "test.md"));
        assertEquals("test.md", payload.get("path").asText());
        JsonNode items = payload.get("items");
        assertEquals(1, items.size());
        assertEquals("This is the body content for: test.md", items.get(0).get("text").asText());
        assertTrue(items.get(0).get("level").asInt() >= 1);

        // unresolved note — zero-result with exists:false (T1/B1)
        JsonNode missing = callTool(41, "outline", Map.of("path", "ghost-note"));
        assertFalse(missing.get("result").get("isError").asBoolean());
        JsonNode miss = MAPPER.readTree(missing.get("result").get("content").get(0).get("text").asText());
        assertFalse(miss.get("exists").asBoolean());
        assertEquals(0, miss.get("rows_returned").asInt());
    }

    @Test
    @DisplayName("T1/C: graph caps at 100 by default with truncated + next_offset; ids shape")
    void graphCapsContinuation() throws IOException {
        JsonNode page1 = payloadOf(46, "graph", Map.of());
        assertTrue(page1.get("files").size() <= 100, "default cap");
        assertTrue(page1.get("rows_returned").asInt() <= 100);
        assertTrue(page1.get("totalSize").asInt() >= page1.get("rows_returned").asInt());
        if (page1.get("totalSize").asInt() > 100) {
            assertTrue(page1.get("truncated").asBoolean(), "cap bites → marker");
            assertEquals(100, page1.get("next_offset").asInt());
        } else {
            assertFalse(page1.get("truncated").asBoolean());
        }

        JsonNode page2 = payloadOf(47, "graph", Map.of("limit", 2, "offset", 0, "sort", "filePath:asc"));
        assertEquals(2, page2.get("rows_returned").asInt(), "limit honored");
        String first = page2.get("files").get(0).get("filePath").asText();
        JsonNode page3 = payloadOf(48, "graph", Map.of("limit", 2, "offset", 2, "sort", "filePath:asc"));
        assertTrue(!page3.get("files").get(0).get("filePath").asText().equals(first), "offset walks");
        if (!page3.get("truncated").asBoolean()) {
            assertTrue(page3.get("next_offset").isMissingNode(), "no continuation when exhausted");
        }

        JsonNode ids = payloadOf(49, "graph", Map.of("shape", "ids", "limit", 3));
        JsonNode idFile = ids.get("files").get(0);
        assertTrue(idFile.has("filePath"), "bare-path shape");
        assertFalse(idFile.has("links") || idFile.has("backlinks") || idFile.has("tags"), "no extra materialization");
    }

    @Test
    @DisplayName("backlink provenance reads parse-time spans; code-span ghosts excluded")
    void backlinksSpanProvenance() throws IOException {
        JsonNode payload = payloadOf(45, "backlinks", Map.of("path", "hub.md", "limit", 100));
        java.util.List<String> hitLines = new java.util.ArrayList<>();
        java.util.List<String> sources = new java.util.ArrayList<>();
        for (JsonNode link : payload.get("links")) {
            if ("refD.md".equals(link.get("source").asText())) {
                hitLines.add(link.get("line").asText());
                sources.add(link.get("context").asText());
            }
        }
        assertEquals(1, hitLines.size(), "exactly one real span for refD (ghost excluded): " + hitLines);
        assertEquals("3", hitLines.get(0), "the real [[hub]] is on refD line 3");
        assertTrue(sources.get(0).contains("real [[hub]] here"), sources.get(0));
    }

    // --- broken_links: vault-wide dangling-target report ---

    @Test
    @DisplayName("broken_links clusters near-misses and ranks by refcount")
    void brokenLinksBasics() throws IOException {
        JsonNode payload = payloadOf(35, "broken_links", Map.of());
        assertEquals(3, payload.get("total_broken").asInt(), "ghost* merges + alias-targeted refB dangler");

        JsonNode targets = payload.get("targets");
        Map<String, Integer> refcounts = new java.util.LinkedHashMap<>();
        targets.forEach(t -> refcounts.put(t.get("target").asText(), t.get("refcount").asInt()));
        assertEquals(4, refcounts.get("Ghost"), "Ghost + ghost + second source + fmlink frontmatter dangler: " + refcounts);
        assertEquals(1, refcounts.get("Ghost2"), refcounts.toString());
        assertEquals(1, refcounts.get("HubAlias"), "alias-targeting wikilink dangles now: " + refcounts);

        int first = targets.get(0).get("refcount").asInt();
        int second = targets.get(1).get("refcount").asInt();
        assertTrue(first >= second, "refcount desc by default: " + refcounts);

        java.util.List<String> sources = new java.util.ArrayList<>();
        targets.get(0).get("sources").forEach(s -> sources.add(s.asText()));
        java.util.Set<String> sourceSet = new java.util.LinkedHashSet<>(sources);
        assertEquals(3, sourceSet.size(), "dangly + dangly2 + fmlink's frontmatter dangler refer to ghost cluster");
        assertTrue(targets.get(0).get("sources").size() <= 10, "sources default to a bounded list");

        // flex-unlock 3: dead markdown destinations resolved through the
        // FS name ladder — nowheres stay dead, hub.md resolves alive
        assertEquals(3, payload.get("total_md").asInt(), payload.toString());
        Map<String, Integer> mdCounts = new java.util.LinkedHashMap<>();
        payload.get("md_targets").forEach(t ->
                mdCounts.put(t.get("target").asText(), t.get("refcount").asInt()));
        assertEquals(1, mdCounts.get("nowhere.md"), "dangly2's raw dest: " + mdCounts);
        assertEquals(1, mdCounts.get("dead.md"), "deadmd link: " + mdCounts);
        assertEquals(1, mdCounts.get("phantom.svg"), "deadmd image dest: " + mdCounts);
        JsonNode mdFirst = payload.get("md_targets").get(0);
        assertTrue(mdFirst.get("instances").size() >= 1, "span facts ride md buckets");

        // flex-unlock 2: anchor rot — wrong anchor on an existing target; the
        // verbatim-matching anchor stays out; dangling targets don't double-report
        assertEquals(2, payload.get("total_anchor").asInt(), payload.toString());
        JsonNode rotWrong = null;
        JsonNode rotNoHead = null;
        for (JsonNode r : payload.get("anchor_rot")) {
            if ("Wrong Heading".equals(r.get("anchor").asText())) {
                rotWrong = r;
            } else if ("No Such Heading".equals(r.get("anchor").asText())) {
                rotNoHead = r;
            }
        }
        assertTrue(rotWrong != null && rotNoHead != null, payload.toString());
        assertEquals("hub", rotWrong.get("target").asText());
        assertEquals(1, rotWrong.get("refcount").asInt());
        assertTrue(rotWrong.get("headings").size() >= 1, "target outline rides as repair hints");
        assertEquals("fmlink.md", rotNoHead.get("sources").get(0).asText(),
                "the frontmatter anchor rot sources its note");
    }

    @Test
    @DisplayName("broken_links filters, sorts by target, and respects fences/aliases")
    void brokenLinksFilters() throws IOException {
        JsonNode filtered = payloadOf(36, "broken_links", Map.of("min_refs", 2));
        assertEquals(1, filtered.get("total_broken").asInt(), "Ghost2 below the floor");

        JsonNode byTarget = payloadOf(37, "broken_links", Map.of("sort", "target", "limit", 1));
        assertEquals(1, byTarget.get("targets").size(), "limit caps targets");

        JsonNode all = payloadOf(38, "broken_links", Map.of("sort", "target"));
        java.util.List<String> names = new java.util.ArrayList<>();
        all.get("targets").forEach(t -> names.add(t.get("target").asText()));
        java.util.List<String> sorted = new java.util.ArrayList<>(names);
        java.util.Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        assertEquals(sorted, names, "target sort is lexicographic");

        String blob = MAPPER.writeValueAsString(payloadOf(39, "broken_links", Map.of()));
        assertFalse(blob.contains("FencedGhost"), "fence demo never counts as dangling");
        assertFalse(blob.contains("Finances"), "the alias keyword itself never dangles (aliases are data)");

        JsonNode scoped = payloadOf(40, "broken_links", Map.of("vault", "gwern"));
        assertTrue(scoped.get("targets").isEmpty(), "danglers live in the main vault");

        JsonNode bad = callTool(41, "broken_links", Map.of("vault", "nonsense"));
        assertTrue(bad.get("result").get("isError").asBoolean(), "unknown vault rejected");
    }

    @Test
    @DisplayName("search hits carry the modified ISO timestamp; ranges query it")
    void modifiedKeySurfacedAndQueryable() throws IOException {
        JsonNode payload = payloadOf(26, "search", Map.of("query", "tags:test"));
        for (JsonNode file : payload.get("files")) {
            assertTrue(file.get("modified").asText().matches("\\d{4}-\\d{2}-\\d{2}T.*"),
                    "modified must be an ISO instant, got: " + file.get("modified"));
        }

        JsonNode recent = payloadOf(27, "search", Map.of("query", "modified:[2000-01-01 TO *]"));
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        recent.get("files").forEach(f -> paths.add(f.get("filePath").asText()));
        assertTrue(paths.contains("test.md"), "recency range matches indexed files (binaries unmodified, excluded)");
    }

    // --- Batch 2: token economy (limit/offset/sort, read heads, composites) ---

    @Test
    @DisplayName("search honors limit/offset with stable sort")
    void searchLimitOffsetSort() throws IOException {
        JsonNode page = payloadOf(30, "search", Map.of(
                "query", "tags:test", "sort", "filePath:asc", "limit", 1, "offset", 0));
        assertEquals(1, page.get("rows_returned").asInt());
        assertEquals("test.md", page.get("files").get(0).get("filePath").asText());
        assertTrue(page.get("totalSize").asInt() >= 2, "totalSize reports the pre-slice count");

        JsonNode second = payloadOf(31, "search", Map.of(
                "query", "tags:test", "sort", "filePath:asc", "limit", 1, "offset", 1));
        assertEquals(1, second.get("rows_returned").asInt());
        assertEquals("test2.md", second.get("files").get(0).get("filePath").asText());

        JsonNode page2 = payloadOf(32, "search", Map.of(
                "query", "tags:test", "sort", "filePath:asc", "limit", 1, "offset", 1));
        assertEquals("test2.md", page2.get("files").get(0).get("filePath").asText());
    }

    @Test
    @DisplayName("search sort desc orders modified newest-first")
    void searchSortModifiedDesc() throws IOException {
        JsonNode payload = payloadOf(33, "search", Map.of(
                "query", "tags:test", "sort", "modified:desc"));
        JsonNode files = payload.get("files");
        assertTrue(files.size() >= 2, "two tags:test fixtures");
        String first = files.get(0).get("modified").asText();
        String last = files.get(files.size() - 1).get("modified").asText();
        assertTrue(first.compareTo(last) >= 0, "desc order: " + first + " >= " + last);
    }

    @Test
    @DisplayName("read_file head caps truncate with a marker")
    void readFileHeadCaps() throws IOException {
        JsonNode lines = callTool(34, "read_file", Map.of("path", "tasks.md", "head_lines", 2));
        String text = lines.get("result").get("content").get(0).get("text").asText();
        assertTrue(text.startsWith("- [ ] open task\n- [x] done task\n"), text);
        assertTrue(text.contains("[truncated ") && text.contains(" lines]"), text);

        JsonNode chars = callTool(35, "read_file", Map.of("path", "tasks.md", "head_chars", 5));
        String head = chars.get("result").get("content").get(0).get("text").asText();
        assertEquals("- [ ]… [truncated at 5 chars]", head,
                "first five chars kept, marker appended");
    }

    @Test
    @DisplayName("search_and_read bundles hits with per-file heads")
    void searchAndReadBundles() throws IOException {
        JsonNode payload = payloadOf(36, "search_and_read", Map.of(
                "query", "tags:test", "head_chars", 24));
        JsonNode entries = payload.get("entries");
        assertEquals(2, entries.size(), "both tags:test notes");
        assertTrue(entries.get(0).get("head").asText().contains("[truncated at 24 chars]"),
                "head capped: " + entries.get(0).get("head").asText());
        assertTrue(entries.get(0).has("modified"));
    }

    @Test
    @DisplayName("recent shorthand returns newest-first entries capped")
    void recentShorthand() throws IOException {
        JsonNode payload = payloadOf(37, "recent", Map.of("limit", 2));
        JsonNode entries = payload.get("entries");
        assertEquals(2, entries.size());
        String first = entries.get(0).get("modified").asText();
        String second = entries.get(1).get("modified").asText();
        assertTrue(first.compareTo(second) >= 0, "desc order: " + first + " >= " + second);
    }

    @Test
    @DisplayName("batched backlink derivation matches single-row derivation")
    void backlinksBatchedNotPart() throws IOException {
        JsonNode payload = payloadOf(42, "search", Map.of("query", "filePath:test2.md"));
        JsonNode file = payload.get("files").get(0);
        assertEquals(1, payload.get("rows_returned").asInt());
        assertEquals(java.util.List.of("test.md"), file.get("backlinks") == null
                ? java.util.List.of() : java.util.stream.StreamSupport
                .stream(file.get("backlinks").spliterator(), false)
                .map(JsonNode::asText).toList());
    }

    @Test
    @DisplayName("bad sort direction rejects as tool error")
    void searchBadSort() throws IOException {
        JsonNode response = callTool(38, "search", Map.of("query", "", "sort", "filePath:sideways"));
        assertTrue(response.get("result").get("isError").asBoolean());
    }

    @Test
    @DisplayName("graph over a text probe carries the textSnippet")
    void graphCarriesSnippet() throws IOException {
        // bodies: test.md "…body", virtual "virtual content" — probe sweep
        JsonNode payload = payloadOf(39, "graph", Map.of("query", "text:body"));
        JsonNode files = payload.get("files");
        for (JsonNode file : files) {
            if ("test.md".equals(file.get("filePath").asText())) {
                assertTrue(file.has("textSnippet"), file.toString());
            }
        }
    }

    @Test
    @DisplayName("graph_hops walks 1-2 hops both directions with a cap")
    void graphHops() throws IOException {
        // A -> B -> C chain fixture is A: test.md -> test2.md; plus finance/islands
        JsonNode oneHop = payloadOf(40, "graph_hops", Map.of("path", "test.md", "hops", 1));
        assertEquals("test.md", oneHop.get("path").asText());
        JsonNode hopFiles = oneHop.get("files");
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        hopFiles.forEach(f -> paths.add(f.get("filePath").asText()));
        assertEquals(java.util.Set.of("test.md", "test2.md"), paths, paths.toString());

        JsonNode twoHop = payloadOf(41, "graph_hops", Map.of("path", "test.md", "hops", 2));
        java.util.Set<String> paths2 = new java.util.LinkedHashSet<>();
        twoHop.get("files").forEach(f -> paths2.add(f.get("filePath").asText()));
        assertTrue(paths2.contains("test.md") && paths2.contains("test2.md"), paths2.toString());
        // cap is enforced: hops > 2 clamps
        JsonNode clamped = payloadOf(42, "graph_hops", Map.of("path", "test.md", "hops", 9));
        java.util.Set<String> paths3 = new java.util.LinkedHashSet<>();
        clamped.get("files").forEach(f -> paths3.add(f.get("filePath").asText()));
        assertEquals(paths2, paths3, "hops clamp to 2");

        JsonNode unresolved = callTool(43, "graph_hops", Map.of("path", "ghost"));
        assertFalse(unresolved.get("result").get("isError").asBoolean(), "zero-result, not throw");
        JsonNode miss = MAPPER.readTree(unresolved.get("result").get("content").get(0).get("text").asText());
        assertFalse(miss.get("exists").asBoolean());
        assertEquals(0, miss.get("rows_returned").asInt());
    }

    @Test
    @DisplayName("binary_file stat reports size/mtime without content")
    void binaryFileStat() throws IOException {
        byte[] body = new byte[2048];
        java.util.Arrays.fill(body, (byte) 0xAB);
        Path png = vaultDir.resolve("image.png");
        Files.write(png, body);

        JsonNode payload = payloadOf(44, "binary_file", Map.of("path", "image.png", "mode", "stat"));
        assertEquals(2048, payload.get("size").asLong());
        assertTrue(payload.has("modified"));
        assertEquals("png", payload.get("ext").asText());
    }

    @Test
    @DisplayName("binary_file base64 roundtrips; oversize rejects with cap message")
    void binaryFileBase64() throws IOException {
        byte[] body = "PKSPKMS-BINARY-CONTENT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(vaultDir.resolve("blob.bin"), body);

        JsonNode payload = payloadOf(45, "binary_file", Map.of("path", "blob.bin", "mode", "base64"));
        String encoded = payload.get("data").asText();
        assertEquals(payload.get("size").asLong(), body.length);
        assertEquals(java.util.Base64.getEncoder().encodeToString(body), encoded);

        byte[] big = new byte[6 * 1024 * 1024];
        Files.write(vaultDir.resolve("huge.bin"), big);
        JsonNode rejected = callTool(46, "binary_file", Map.of("path", "huge.bin", "mode", "base64"));
        assertTrue(rejected.get("result").get("isError").asBoolean());
        assertTrue(rejected.get("result").get("content").get(0).get("text").asText().contains("5 MB"),
                "cap message present");
    }

}
