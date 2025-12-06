package io.github.pskenny.e2e;

import io.github.pskenny.Server;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerE2ETest {

    private static final Logger logger = LoggerFactory.getLogger(ServerE2ETest.class);
    private static final int TEST_PORT = 7001;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private static final Path TEST_DIR = Paths.get("target", "test-notes", ServerE2ETest.class.getSimpleName());
    private static Javalin app;

    @BeforeAll
    static void setupServer() throws Exception {
        Files.createDirectories(TEST_DIR);

        Server serverInstance = new Server(TEST_DIR.toAbsolutePath().toString());
        app = serverInstance.getJavalinApp();
        app.start(TEST_PORT);
    }

    @AfterAll
    static void tearDownServer() {
        app.stop();
    }

    @Test
    @DisplayName("GET /ping returns 200 OK")
    void testPingEndpoint() throws IOException, InterruptedException {
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
        createFile("test.md", Map.of(
                "tags", "test",
                "links", List.of("test2.md")),
                """
                        ![test2](test2.svg)
                        [test1](test1.md)
                        """
        );
        createFile("test2.md", Map.of("tags", "test"));

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/files/list")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "The /files/list endpoint should return HTTP 200 OK.");

        String expected = "{\"resultSize\":2,\"files\":[{\"tags\":\"test\",\"filePath\":\"/test2.md\"},{\"links\":[\"test2.svg\",\"test1.md\"],\"tags\":\"test\",\"filePath\":\"/test.md\"}]}";
        String actual = response.body();
//        assertEquals(expected, actual);
    }

    private static void createFile(String fileName, Map<String, Object> frontmatter) throws IOException {
        createFile(fileName, frontmatter, "");
    }

    private static void createFile(String fileName, Map<String, Object> frontmatter, String content) throws IOException {
        StringBuilder contentBuilder = new StringBuilder();
        contentBuilder.append("---\n");

        for (Map.Entry<String, Object> entry : frontmatter.entrySet()) {
            contentBuilder.append(entry.getKey()).append(": ");
            Object value = entry.getValue();
            if (value instanceof Collection) {
                contentBuilder.append(
                        ((Collection<?>) value).stream()
                                .map(item -> "\n  - " + item.toString())
                                .collect(Collectors.joining())
                );
            } else {
                contentBuilder.append(value.toString());
            }
            contentBuilder.append("\n");
        }

        contentBuilder.append("---\n# This is the body content for: ")
                .append(fileName)
                .append("\n")
                .append(content);

        Path filePath = TEST_DIR.resolve(fileName);
        Files.writeString(filePath, contentBuilder.toString());
        logger.info("Created test file: {}", filePath);
    }
}