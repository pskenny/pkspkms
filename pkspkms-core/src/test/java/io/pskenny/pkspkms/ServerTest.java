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

package io.pskenny.pkspkms;

import fi.iki.elonen.NanoHTTPD;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.createFile;
import static io.pskenny.pkspkms.test.FileUtil.readFile;
import static io.pskenny.pkspkms.test.JsonUtil.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTest {

    private static final int TEST_PORT = 7001;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private static final Path TEST_DIR = Paths.get("target", "test-notes", ServerTest.class.getSimpleName());
    private static Server app;

    private static final Path TEST_DB = Path.of("pkspkms.db");

    @BeforeEach
    void setup() throws IOException {
        // Index persists across constructions now — start each test from a
        // fresh DB for isolation (several tests mount vault aliases)
        Files.deleteIfExists(TEST_DB);
        Files.deleteIfExists(Path.of("pkspkms.db-wal"));
        Files.deleteIfExists(Path.of("pkspkms.db-shm"));
        if (!Files.exists(TEST_DIR)) {
            Files.createDirectories(TEST_DIR);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(TEST_DIR)) {
            try (Stream<Path> pathStream = Files.walk(TEST_DIR)) {
                pathStream
                        .sorted(Comparator.reverseOrder()) // Must delete children before parents
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete test directory", e);
            }
        }

        for (String dir : List.of("manifest-virtual-a", "manifest-virtual-b")) {
            Path path = Paths.get("target", "test-notes", dir);
            if (Files.exists(path)) {
                try (Stream<Path> pathStream = Files.walk(path)) {
                    pathStream
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete test directory", e);
                }
            }
        }

        if (app.wasStarted()) {
            app.stop();
        }
        Files.deleteIfExists(TEST_DB);
        Files.deleteIfExists(Path.of("pkspkms.db-wal"));
        Files.deleteIfExists(Path.of("pkspkms.db-shm"));
    }

    void startServer() throws IOException {
        startServer(null, java.util.Set.of());
    }

    void startServer(String token) throws IOException {
        startServer(token, java.util.Set.of());
    }

    void startServer(String token, java.util.Set<String> corsOrigins) throws IOException {
        String dir = TEST_DIR.toAbsolutePath().toString();
        PkmsFileSystem fs = new JavaFileSystem(new File(dir));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + new File("pkspkms.db").getAbsolutePath(), null, fs);
        app = new Server("127.0.0.1", TEST_PORT, token, corsOrigins, repository);
        app.loadRepo();
        app.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Test
    @DisplayName("GET /files/search is gone — no duplicate route (B28)")
    void testFilesSearchRemoved() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/files/search")).GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode(), "the /files/list duplicate must be gone");
    }

    // --- CORS allow-list (extension/remote-origin consumption) ---

    private static final String EXT_ORIGIN = "moz-extension://abc-123";
    private static final String OTHER_ORIGIN = "moz-extension://other-999";

    private boolean hasHeader(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).isPresent();
    }

    private String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    @Test
    @DisplayName("CORS default off: no headers anywhere (B3-posture unchanged without the flag)")
    void noCorsHeadersByDefault() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> get = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list"))
                .header("Origin", EXT_ORIGIN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> preflight = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list"))
                .header("Origin", EXT_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertFalse(hasHeader(get, "Access-Control-Allow-Origin"), "no ACAO without the flag");
        assertFalse(hasHeader(preflight, "Access-Control-Allow-Origin"), "no preflight ACAO without the flag");
    }

    @Test
    @DisplayName("Preflight carries CORS for a registered origin — before the auth gate (CORS spec)")
    void preflightCarriesCorsForAllowedOrigin() throws IOException, InterruptedException {
        startServer("test-token-123", java.util.Set.of(EXT_ORIGIN));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list"))
                .header("Origin", EXT_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "preflight never demands the bearer token");
        assertEquals(EXT_ORIGIN, header(response, "Access-Control-Allow-Origin"), "origin reflected");
        assertTrue(header(response, "Access-Control-Allow-Headers").contains("Authorization"),
                "preflight declares the auth header");
        assertTrue(header(response, "Access-Control-Allow-Methods").contains("GET"),
                "preflight declares GET");
        assertFalse(header(response, "Access-Control-Max-Age").isEmpty(), "preflight cacheable");
    }

    @Test
    @DisplayName("Register-only origin: a different origin gets no CORS grant")
    void disallowedOriginGetsNoCors() throws IOException, InterruptedException {
        startServer("test-token-123", java.util.Set.of(EXT_ORIGIN));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list"))
                .header("Origin", OTHER_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertFalse(hasHeader(response, "Access-Control-Allow-Origin"), "unregistered origin blocked");
    }

    @Test
    @DisplayName("Allowed origin reflected on the actual response too")
    void allowedOriginReflectedOnActualRequest() throws IOException, InterruptedException {
        startServer(null, java.util.Set.of(EXT_ORIGIN));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list"))
                .header("Origin", EXT_ORIGIN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(EXT_ORIGIN, header(response, "Access-Control-Allow-Origin"));
        assertTrue(header(response, "Vary").contains("Origin"), "reflection requires Vary: Origin");
    }

    @Test
    @DisplayName("Auth gate survives CORS: 401 keeps CORS headers, data still token-gated")
    void authGateSurvivesCors() throws IOException, InterruptedException {
        startServer("test-token-123", java.util.Set.of(EXT_ORIGIN));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> rejected = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list"))
                .header("Origin", EXT_ORIGIN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, rejected.statusCode(), "cross-origin does not bypass auth");
        assertEquals(EXT_ORIGIN, header(rejected, "Access-Control-Allow-Origin"),
                "the browser must be able to read the rejection");

        HttpResponse<String> allowed = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list"))
                .header("Origin", EXT_ORIGIN)
                .header("Authorization", "Bearer test-token-123")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, allowed.statusCode());
        assertEquals(EXT_ORIGIN, header(allowed, "Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Wildcard mode emits * without Vary")
    void starModeEmitsWildcard() throws IOException, InterruptedException {
        startServer(null, java.util.Set.of("*"));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list"))
                .header("Origin", EXT_ORIGIN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals("*", header(response, "Access-Control-Allow-Origin"));
        assertFalse(header(response, "Vary").contains("Origin"), "wildcard reflection needs no Vary");
    }

    @Test
    @DisplayName("MCP route carries CORS too")
    void mcpRouteCarriesCors() throws IOException, InterruptedException {
        startServer("test-token-123", java.util.Set.of(EXT_ORIGIN));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/mcp"))
                .header("Origin", EXT_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(EXT_ORIGIN, header(response, "Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("No Origin header → CORS inert even with the flag set")
    void noOriginHeaderStaysInert() throws IOException, InterruptedException {
        startServer(null, java.util.Set.of(EXT_ORIGIN));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertFalse(hasHeader(response, "Access-Control-Allow-Origin"), "same-origin webui unaffected");
    }

    @Test
    @DisplayName("Token auth: 401 without, 200 with, /ping exempt (B3)")
    void tokenAuthFlow() throws IOException, InterruptedException {
        startServer("test-token-123");
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(200, client.send(HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/ping")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode(), "/ping stays exempt");

        assertEquals(401, client.send(HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/files/list")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode(), "no token → 401");

        assertEquals(200, client.send(HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/files/list"))
                        .header("Authorization", "Bearer test-token-123").GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode(), "valid bearer → 200");
    }

    @Test
    @DisplayName("Host header allow-list: rebinding hosts rejected (B3)")
    void foreignHostRejected() throws Exception {
        startServer();
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", TEST_PORT)) {
            socket.getOutputStream().write("GET /ping HTTP/1.1\r\nHost: evil.example\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertTrue(statusLine.startsWith("HTTP/1.1 403"), "rebinding Host must be rejected, got: " + statusLine);
        }
    }

    @Test
    @DisplayName("GET /ping returns 200 OK")
    void testPingEndpoint() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/ping"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "The /ping endpoint should return HTTP 200 OK.");
        assertTrue(response.body().isEmpty(), "The /ping response body should be empty.");
    }

    @Test
    @DisplayName("GET /files/list returns a list of files with YAML properties")
    void testFilesListEndpoint() throws IOException, InterruptedException {
        createFile(TEST_DIR, "test.md", Map.of(
                        "tags", "test",
                        "links", List.of("test2.md")),
                """
                        ![test2](test2.svg)
                        [test1](test1.md)
                        """
        );
        createFile(TEST_DIR, "test2.md", Map.of("tags", "test"));
        // `modified` rides the properties JSON — stamp a deterministic mtime
        // so the pinned response can carry a literal ISO instant
        FileTime fixed = FileTime.fromMillis(1735689600000L);
        Files.setLastModifiedTime(TEST_DIR.resolve("test.md"), fixed);
        Files.setLastModifiedTime(TEST_DIR.resolve("test2.md"), fixed);

        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/files/list?query=filePath%3A%2A.md")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "The /files/list endpoint should return HTTP 200 OK.");

        String actual = response.body();
        String expectedJson = readFile("test/data/response/files-list.json");
        assertJsonEquals(expectedJson, actual, true);
    }

    @Test
    @DisplayName("Server response wikilinks should resolve with links")
    void testFilesWikilinks() throws IOException, InterruptedException {
        createFile(TEST_DIR, "test1.md", Map.of(),
                """
                        [[test2]]
                        """
        );
        createFile(TEST_DIR, "test2.md", Map.of());
        FileTime fixed = FileTime.fromMillis(1735689600000L);
        Files.setLastModifiedTime(TEST_DIR.resolve("test1.md"), fixed);
        Files.setLastModifiedTime(TEST_DIR.resolve("test2.md"), fixed);

        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/files/list?query=filePath%3A%2A.md")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "The /files/list endpoint should return HTTP 200 OK.");

        String actual = response.body();
        String expectedJson = readFile("test/data/response/files-list-with-resolved-wikilinks.json");
        assertJsonEquals(expectedJson, actual, true);
    }

    @Test
    @DisplayName("GET /files/list/graph returns graph data")
    void testFilesListGraphEndpoint() throws IOException, InterruptedException {
        createFile(TEST_DIR, "test-graph.md", Map.of(),
                """
---
tags:
- Tag1
---
[test2](test2-graph.md)
[test1 doesn't exist](test1-no-existy.md)
                        """.trim()
        );
        createFile(TEST_DIR, "test2-graph.md", Map.of(),
                """
---
tags:
- Tag1
- Tag2
---
[test3](test3-graph.md)
                """.trim());
        createFile(TEST_DIR, "test3-graph.md", Map.of(),
                """
---
tags:
- Tag3
---
No links
                """.trim());
        startServer();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list/graph?query=tags%3ATag1"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The /files/list/graph endpoint should return HTTP 200 OK");

        String actual = response.body();
        String expectedJson = readFile("test/data/response/files-list-graph.json");
        assertJsonEquals(expectedJson, actual, true);
    }

    @Test
    @DisplayName("GET /webui redirects to /webui/index.html")
    void testWebuiRedirect() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/webui"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(301, response.statusCode(), "The /webui endpoint should redirect.");
        String location = response.headers().firstValue("Location").orElse("");
        assertEquals("/webui/index.html", location, "Should redirect to /webui/index.html");
    }

    @Test
    @DisplayName("GET /webui/index.html returns the UI page")
    void testWebuiIndexHtml() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/webui/index.html"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The /webui/index.html endpoint should return HTTP 200 OK.");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("text/html"), "Content-Type should be text/html");
        assertTrue(response.body().contains("<title>PKSPKMS</title>"), "Body should contain PKSPKMS title");
    }

    @Test
    @DisplayName("Webui responses carry browser hardening headers (B4)")
    void testWebuiSecurityHeaders() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/webui/index.html")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String csp = response.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("default-src 'self'"), "CSP default-src 'self' required");
        assertTrue(csp.contains("script-src 'self'"), "CSP script-src 'self' required (no inline scripts)");
        assertTrue(csp.contains("frame-ancestors 'none'"), "CSP frame-ancestors 'none' required");
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(""), "X-Frame-Options: DENY");
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""), "nosniff required");
    }

    @Test
    @DisplayName("Note-controlled values no longer interpolate into onclick strings (B4)")
    void testWebuiNoInlineHandlers() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> page = client.send(HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/webui/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> app = client.send(HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/webui/app.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, app.statusCode(), "/webui/app.js must be served (CSP script-src 'self')");
        assertFalse(app.body().contains("onclick="), "app.js must not emit inline handlers");
        assertTrue(app.body().contains("&quot;"), "escapeHtml must also escape quotes (B4)");
        assertTrue(app.body().contains("dataset"), "handlers must read data-* attributes");
    }

    @Test
    @DisplayName("GET /webui/pk.png returns the icon")
    void testWebuiIcon() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/webui/pk.png"))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), "The /webui/pk.png endpoint should return HTTP 200 OK.");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("image/png"), "Content-Type should be image/png");
        assertTrue(response.body().length > 0, "Icon body should not be empty");
    }

    @Test
    @DisplayName("GET /openapi.json serves the OpenAPI spec for the running port")
    void testOpenApiEndpoint() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/openapi.json"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The /openapi.json endpoint should return HTTP 200 OK.");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("application/json"), "Content-Type should be application/json");

        JsonNode spec = new ObjectMapper().readTree(response.body());
        assertTrue(spec.get("paths").size() >= 6, "Spec should declare at least 6 paths");
        assertTrue(spec.get("servers").get(0).get("url").asText().contains(":7001"),
                "Servers URL should be rewritten to the running port");
    }

    @Test
    @DisplayName("GET /webui/swagger redirects to /webui/swagger/index.html")
    void testSwaggerRedirect() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/webui/swagger"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(301, response.statusCode(), "The /webui/swagger endpoint should redirect.");
        String location = response.headers().firstValue("Location").orElse("");
        assertEquals("/webui/swagger/index.html", location, "Should redirect to /webui/swagger/index.html");
    }

    @Test
    @DisplayName("GET /webui/swagger/index.html returns the Swagger UI page")
    void testSwaggerIndexHtml() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/webui/swagger/index.html"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The /webui/swagger/index.html endpoint should return HTTP 200 OK.");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("text/html"), "Content-Type should be text/html");
        assertTrue(response.body().contains("/openapi.json"), "Docs page should reference the served spec");
    }

    @Test
    @DisplayName("GET /webui/swagger/swagger-ui-bundle.js serves the webjar asset")
    void testSwaggerBundle() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/webui/swagger/swagger-ui-bundle.js"))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), "The swagger-ui-bundle.js should return HTTP 200 OK.");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("text/javascript"), "Content-Type should be text/javascript");
        assertTrue(response.body().length > 100_000, "Bundle body should be non-trivial");
    }

    @Test
    @DisplayName("Pinned swagger-ui version matches the webjar on the classpath")
    void testSwaggerUiVersionSync() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/maven/org.webjars/swagger-ui/pom.properties")) {
            assertNotNull(in, "swagger-ui webjar pom.properties must be on the classpath");
            Properties props = new Properties();
            props.load(in);
            assertEquals(Server.SWAGGER_UI_WEBJAR_VERSION, props.getProperty("version"),
                    "Server.SWAGGER_UI_WEBJAR_VERSION and the pom.xml dependency must match");
        }
    }

    @Test
    @DisplayName("GET /files/manifest groups virtual-vault files per alias")
    void testFilesManifestEndpoint() throws IOException, InterruptedException {
        createFile(TEST_DIR, "main.md", Map.of(), "main vault file");
        startServer();

        Path virtualA = Paths.get("target", "test-notes", "manifest-virtual-a");
        Path virtualB = Paths.get("target", "test-notes", "manifest-virtual-b");
        Files.createDirectories(virtualA);
        Files.createDirectories(virtualB);
        createFile(virtualA, "gwern-note.md", Map.of("tags", "g"), "content");
        createFile(virtualB, "zeta-note.md", Map.of(), "content");
        app.loadVirtualVault(new JavaFileSystem(virtualA.toFile()), "gwern");
        app.loadVirtualVault(new JavaFileSystem(virtualB.toFile()), "zeta");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/manifest"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "The /files/manifest endpoint should return HTTP 200 OK.");
        JsonNode manifest = new ObjectMapper().readTree(response.body());
        assertTrue(manifest.has("gwern"), "Manifest should group by alias");
        assertTrue(manifest.has("zeta"), "Manifest should group by alias");
        assertFalse(manifest.has("main"), "Main-vault rows (no alias) must be excluded");
        // D3 runtime probe: main-vault rows must not leak under an empty/null key group
        assertFalse(manifest.has(""), "no empty-key group (main-vault exclusion is absolute)");

        JsonNode gwernFiles = manifest.get("gwern");
        assertEquals(1, gwernFiles.size());
        assertEquals("@gwern/gwern-note.md", gwernFiles.get(0).get("filePath").asText());
        assertEquals(64, gwernFiles.get(0).get("blake3").asText().length(), "blake3 should be a full hash");
    }

    @Test
    @DisplayName("GET /files/manifest returns an empty object with no virtual vaults")
    void testFilesManifestEmpty() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/manifest"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "The /files/manifest endpoint should return HTTP 200 OK.");
        JsonNode manifest = new ObjectMapper().readTree(response.body());
        assertFalse(manifest.elements().hasNext(), "Manifest should be empty without virtual vaults");
    }

    @Test
    @DisplayName("Server binds 127.0.0.1 only (B3 quick win)")
    void serverBindsLoopbackOnly() throws IOException, InterruptedException {
        startServer();
        assertEquals("127.0.0.1", app.getHostname(), "the server must bind loopback, not all interfaces");

        // Behavior pin where the machine has a distinct LAN address
        try {
            java.net.InetAddress local = java.net.InetAddress.getLocalHost();
            if (local != null && !local.isLoopbackAddress()) {
                HttpClient lanClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + local.getHostAddress() + ":" + TEST_PORT + "/ping"))
                        .timeout(java.time.Duration.ofSeconds(3))
                        .GET().build();
                assertThrows(Exception.class, () -> lanClient.send(request, HttpResponse.BodyHandlers.discarding()),
                        "LAN address must be unreachable after loopback-only binding");
            }
        } catch (java.net.UnknownHostException unresolvable) {
            // No distinct hostname on this machine — the hostname pin above is the whole check
        }
    }

    @Test
    @DisplayName("Responses carry no CORS headers (B3 quick win)")
    void noCorsHeaders() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/ping")).GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "CORS must be gone — browsers only ever need same-origin here");
        assertFalse(response.headers().firstValue("Access-Control-Allow-Methods").isPresent(),
                "No method advertising (POST is rejected anyway)");
    }

    @Test
    @DisplayName("POST /mcp requires the bearer token like every other route")
    void mcpRequiresToken() throws IOException, InterruptedException {
        startServer("test-token-123");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"))
                .build();
        assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "/mcp must sit behind bearer auth");
    }

    @Test
    @DisplayName("POST /mcp initialize negotiates the protocol version")
    void mcpInitialize() throws IOException, InterruptedException {
        startServer("test-token-123");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/mcp"))
                .header("Authorization", "Bearer test-token-123")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "JSON-RPC requests answer 200 with a JSON body");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertEquals("2025-06-18", body.get("result").get("protocolVersion").asText());
        assertTrue(body.get("result").get("capabilities").has("tools"));
    }

    @Test
    @DisplayName("POST /mcp notification answers 202 Accepted with no body")
    void mcpNotificationAccepted() throws IOException, InterruptedException {
        startServer("test-token-123");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/mcp"))
                .header("Authorization", "Bearer test-token-123")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), "notifications answer 202");
        assertTrue(response.body().isEmpty(), "notification responses carry no body");
    }

    @Test
    @DisplayName("GET /mcp is 405 — no standalone SSE stream (stateless server)")
    void mcpGetRejected() throws IOException, InterruptedException {
        startServer("test-token-123");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/mcp"))
                .header("Authorization", "Bearer test-token-123")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode(), "GET /mcp must be 405 without SSE support");
        assertEquals("POST", response.headers().firstValue("Allow").orElse(""),
                "405 must advertise POST");
    }

    @Test
    @DisplayName("POST /mcp tools/call serves vault content over HTTP")
    void mcpToolsCallOverHttp() throws IOException, InterruptedException {
        createFile(TEST_DIR, "mcp-note.md", Map.of("tags", "mcp-test"), "mcp body content");
        startServer("test-token-123");
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> read = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/mcp"))
                        .header("Authorization", "Bearer test-token-123")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"../pkspkms.db"}}}
                                """))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode rejected = new ObjectMapper().readTree(read.body());
        assertTrue(rejected.get("result").get("isError").asBoolean(),
                "path traversal must be rejected at the HTTP layer too");

        HttpResponse<String> found = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/mcp"))
                        .header("Authorization", "Bearer test-token-123")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"mcp-note.md\"}}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode body = new ObjectMapper().readTree(found.body());
        assertFalse(body.get("result").get("isError").asBoolean());
        assertTrue(body.get("result").get("content").get(0).get("text").asText().contains("mcp body content"),
                "read_file must return the note's raw markdown");
    }

    @Disabled("Depth-2 graph traversal not yet implemented")
    @DisplayName("GET /files/list/graph returns graph data")
    void testFilesListGraphDepth2Endpoint() throws IOException, InterruptedException {
        createFile(TEST_DIR, "test-graph.md", Map.of(),
                """
---
tags:
- Tag1
---
[test2](test2-graph.md)
[test1 doesn't exist](test1-no-existy.md)
                        """.trim()
        );
        createFile(TEST_DIR, "test2-graph.md", Map.of(),
                """
---
tags:
- Tag1
- Tag2
---
[test3](test3-graph.md)
                """.trim());

        createFile(TEST_DIR, "test3-graph.md", Map.of(),
                """
---
tags:
- Tag3
---
[test4-graph](test4-graph.md)
                """.trim());

        createFile(TEST_DIR, "test4-graph.md", Map.of(),
                """
---
tags:
- Tag4
---
[test2-graph](test2-graph.md)
                """.trim());

        startServer();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list/graph?query=links%3Atest2-graph.md%20OR%20filePath%3Atest2-graph.md"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The /files/list/graph endpoint should return HTTP 200 OK.");

        String actual = response.body();
        String expectedJson = readFile("test/data/response/files-list-graph-depth-2.json");
        assertJsonEquals(expectedJson, actual, true);
    }
}
