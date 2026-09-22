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

    @BeforeEach
    void setup() throws IOException {
        if (!Files.exists(TEST_DIR)) {
            Files.createDirectories(TEST_DIR);
        }
    }

    @AfterEach
    void tearDown() {
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
    }

    void startServer() throws IOException {
        startServer(null);
    }

    void startServer(String token) throws IOException {
        String dir = TEST_DIR.toAbsolutePath().toString();
        PkmsFileSystem fs = new JavaFileSystem(new File(dir));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + new File("pkspkms.db").getAbsolutePath(), null, fs);
        app = new Server("127.0.0.1", TEST_PORT, token, repository);
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
