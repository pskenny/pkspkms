package io.pskenny.pkspkms;

import fi.iki.elonen.NanoHTTPD;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class CacheEndpointTest {

    private static final int TEST_PORT = 7002;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private static final Path TEST_DIR = Paths.get("target", "test-notes", CacheEndpointTest.class.getSimpleName());
    private static final Path CACHE_DIR = TEST_DIR.resolve(".pkspkms-cache");
    private static Server app;

    @BeforeEach
    void setup() throws IOException {
        Files.createDirectories(TEST_DIR);
        Files.createDirectories(CACHE_DIR.resolve("@gwern/programming"));
        Files.writeString(CACHE_DIR.resolve("@gwern/programming/haskell.md"), "# Haskell\n\nContent about Haskell.");
    }

    @AfterEach
    void tearDown() {
        if (app != null && app.wasStarted()) {
            app.stop();
        }

        if (Files.exists(TEST_DIR)) {
            try (Stream<Path> pathStream = Files.walk(TEST_DIR)) {
                pathStream
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete test directory", e);
            }
        }
    }

    void startServer() throws IOException {
        String dir = TEST_DIR.toAbsolutePath().toString();
        PkmsFileSystem fs = new JavaFileSystem(new File(dir));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + new File("pkspkms-cache-test.db").getAbsolutePath(), null, fs);
        app = new Server(TEST_PORT, repository);
        app.loadRepo();
        app.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Test
    @DisplayName("GET /cache returns success with file and blake3")
    void testCacheEndpointSuccess() throws IOException, InterruptedException, SQLException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern/programming/haskell.md?directory=.pkspkms-cache"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Cache endpoint should return 200");
        String body = response.body();
        assertTrue(body.contains("\"status\":\"success\""), "Response should have success status");
        assertTrue(body.contains("\"file\":\".pkspkms-cache/@gwern/programming/haskell.md\""), "Response should include file path");
        assertTrue(body.contains("\"blake3\":\""), "Response should include blake3 hash");
    }

    @Test
    @DisplayName("GET /cache returns error for missing file")
    void testCacheEndpointFileNotFound() throws IOException, InterruptedException, SQLException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern/nonexistent.md?directory=.pkspkms-cache"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode(), "Cache endpoint should return 404 for missing file");
        String body = response.body();
        assertTrue(body.contains("\"status\":\"error\""), "Response should have error status");
    }

    @Test
    @DisplayName("GET /cache returns error for missing directory parameter")
    void testCacheEndpointMissingDirectory() throws IOException, InterruptedException, SQLException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern/programming/haskell.md"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode(), "Cache endpoint should return 400 for missing directory");
        String body = response.body();
        assertTrue(body.contains("\"status\":\"error\""), "Response should have error status");
    }

    @Test
    @DisplayName("GET /cache returns error for invalid path")
    void testCacheEndpointInvalidPath() throws IOException, InterruptedException, SQLException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern?directory=.pkspkms-cache"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode(), "Cache endpoint should return 400 for invalid path");
        String body = response.body();
        assertTrue(body.contains("\"status\":\"error\""), "Response should have error status");
    }

    @Test
    @DisplayName("GET /cache returns CORS headers")
    void testCacheEndpointCORSHeaders() throws IOException, InterruptedException, SQLException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern/programming/haskell.md?directory=.pkspkms-cache"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""), "Should have CORS header");
    }

    @Test
    @DisplayName("GET /cache with invalid directory returns error")
    void testCacheEndpointInvalidDirectory() throws IOException, InterruptedException, SQLException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern/programming/haskell.md?directory=nonexistent-dir"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode(), "Cache endpoint should return 404 for invalid directory");
        String body = response.body();
        assertTrue(body.contains("\"status\":\"error\""), "Response should have error status");
    }
}
