package io.github.pskenny;

import io.javalin.Javalin;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.pskenny.test.FileUtil.createFile;
import static io.github.pskenny.test.JsonUtil.assertJsonEquals;
import static io.github.pskenny.test.JsonUtil.readJsonFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTest {

    private static final int TEST_PORT = 7001;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private static final Path TEST_DIR = Paths.get("target", "test-notes", ServerTest.class.getSimpleName());
    private static Javalin app;

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
    }

    void startServer() {
        Server serverInstance = new Server(TEST_DIR.toAbsolutePath().toString());
        app = serverInstance.getJavalinApp();
        app.start(TEST_PORT);
    }

    void stopServer() {
        app.stop();
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

        stopServer();
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
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/files/list")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "The /files/list endpoint should return HTTP 200 OK.");

        String actual = response.body();
        String expectedJson = readJsonFile("test/data/response/files-list.json");
        assertJsonEquals(expectedJson, actual, true);

        stopServer();
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
                .uri(URI.create(BASE_URL + "/files/list/graph?tags=Tag1"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The /files/list/graph endpoint should return HTTP 200 OK.");

        String actual = response.body();
        String expectedJson = readJsonFile("test/data/response/files-list-graph.json");
        assertJsonEquals(expectedJson, actual, true);
        stopServer();
    }


    @Test
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
No links
                """.trim());

        startServer();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list/graph/depth/2?tags=Tag1"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The /files/list/graph/depth/2 endpoint should return HTTP 200 OK.");

        String actual = response.body();
        String expectedJson = readJsonFile("test/data/response/files-list-graph-depth-2.json");
        assertJsonEquals(expectedJson, actual, true);
        stopServer();
    }

}