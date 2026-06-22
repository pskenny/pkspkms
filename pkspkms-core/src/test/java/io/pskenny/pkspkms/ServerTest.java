package io.pskenny.pkspkms;

import fi.iki.elonen.NanoHTTPD;
import io.pskenny.pkspkms.repo.SQLitePksFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.createFile;
import static io.pskenny.pkspkms.test.FileUtil.readFile;
import static io.pskenny.pkspkms.test.JsonUtil.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTest {

    private static final int TEST_PORT = 7001;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private static final Path TEST_DIR = Paths.get("target", "test-notes", ServerTest.class.getSimpleName());
    private static Server app;

    @BeforeEach
    void setup() {
        try {
            Files.createDirectories(TEST_DIR);
        } catch (IOException e) {
            throw new RuntimeException(e);
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

        if (app.wasStarted()) {
            app.stop();
        }
    }

    void startServer() throws IOException, SQLException {
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:pkspkms.db");
        String dir = TEST_DIR.toAbsolutePath().toString();
        app = new Server(TEST_PORT, repository, dir);
        app.loadRepo(dir);
        app.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Test
    @DisplayName("GET /ping returns 200 OK")
    void testPingEndpoint() throws IOException, InterruptedException, SQLException {
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
    void testFilesListEndpoint() throws IOException, InterruptedException, SQLException {
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
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/files/list?query=filePath%20%3D%20%2A.md")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "The /files/list endpoint should return HTTP 200 OK.");

        String actual = response.body();
        String expectedJson = readFile("test/data/response/files-list.json");
        assertJsonEquals(expectedJson, actual, true);
    }

    @Test
    @DisplayName("Server response wikilinks should resolve with links")
    void testFilesWikilinks() throws IOException, InterruptedException, SQLException {
        createFile(TEST_DIR, "test1.md", Map.of(),
                """
                        [[test2]]
                        """
        );
        createFile(TEST_DIR, "test2.md", Map.of());

        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/files/list?query=filePath%20%3D%20%2A.md")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "The /files/list endpoint should return HTTP 200 OK.");

        String actual = response.body();
        String expectedJson = readFile("test/data/response/files-list-with-resolved-wikilinks.json");
        assertJsonEquals(expectedJson, actual, true);
    }

    @Test
    @DisplayName("GET /files/list/graph returns graph data")
    void testFilesListGraphEndpoint() throws IOException, InterruptedException, SQLException {
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
                .uri(URI.create(BASE_URL + "/files/list/graph?query=tags%20%3D%20Tag1"))
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
    void testWebuiRedirect() throws IOException, InterruptedException, SQLException {
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
    void testWebuiIndexHtml() throws IOException, InterruptedException, SQLException {
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
    @DisplayName("GET /webui/brain-icon.png returns the icon")
    void testWebuiIcon() throws IOException, InterruptedException, SQLException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/webui/brain-icon.png"))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), "The /webui/brain-icon.png endpoint should return HTTP 200 OK.");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("image/png"), "Content-Type should be image/png");
        assertTrue(response.body().length > 0, "Icon body should not be empty");
    }

    @Disabled("Depth-2 graph traversal not yet implemented")
    @DisplayName("GET /files/list/graph returns graph data")
    void testFilesListGraphDepth2Endpoint() throws IOException, InterruptedException, SQLException {
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
                .uri(URI.create(BASE_URL + "/files/list/graph?query=%28links%20%3D%20test2-graph.md%29%20OR%20%28filePath%20%3D%20test2-graph.md%29"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The /files/list/graph endpoint should return HTTP 200 OK.");

        String actual = response.body();
        String expectedJson = readFile("test/data/response/files-list-graph-depth-2.json");
        assertJsonEquals(expectedJson, actual, true);
    }
}
