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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    private static final Path VIRTUAL_DIR = Paths.get("target", "test-notes", CacheEndpointTest.class.getSimpleName() + "-virtual");
    private static final Path OUTSIDE_DIR = Paths.get("target", "test-notes", "outside-cache");
    private static Server app;
    private SQLitePksFileRepository repository;

    @BeforeEach
    void setup() throws IOException {
        Files.createDirectories(TEST_DIR);
        Files.createDirectories(CACHE_DIR.resolve("@gwern/programming"));
        Files.writeString(CACHE_DIR.resolve("@gwern/programming/haskell.md"), "# Haskell\n\nContent about Haskell.");
        Files.createDirectories(VIRTUAL_DIR);
        Files.writeString(VIRTUAL_DIR.resolve("note.md"), "# Virtual note\n\nFrom the gwern vault.");
    }

    @AfterEach
    void tearDown() {
        if (app != null && app.wasStarted()) {
            app.stop();
        }

        for (Path dir : new Path[]{TEST_DIR, VIRTUAL_DIR, OUTSIDE_DIR, Paths.get("target", "outside-abs-cache")}) {
            if (Files.exists(dir)) {
                try (Stream<Path> pathStream = Files.walk(dir)) {
                    pathStream
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete test directory", e);
                }
            }
        }
    }

    void startServer() throws IOException {
        String dir = TEST_DIR.toAbsolutePath().toString();
        PkmsFileSystem fs = new JavaFileSystem(new File(dir));
        repository = new SQLitePksFileRepository("jdbc:sqlite:" + new File("pkspkms-cache-test.db").getAbsolutePath(), null, fs);
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

    @Test
    @DisplayName("GET /cache resolves a percent-encoded # in the location")
    void testCacheHashInFilename() throws IOException, InterruptedException, SQLException {
        // Clients must send # as %23 (raw # is a fragment and clients drop it);
        // the server decodes it and matches the file by its literal name
        startServer();
        repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");
        Files.writeString(VIRTUAL_DIR.resolve("note #1.md"), "hash note");
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern/note%20%231.md?directory=.pkspkms-cache"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Encoded # must resolve to the literal file");
        assertTrue(Files.exists(CACHE_DIR.resolve("@gwern").resolve("note #1.md")),
                "Cached copy keeps the literal #");
    }

    @Test
    @DisplayName("GET /cache strips the leading @ and copies a new virtual-vault file")
    void testCacheFirstClickCachesFromVirtualVault() throws IOException, InterruptedException, SQLException {
        // pkspkms://@gwern/note.md -> /cache/@gwern/note.md: the alias registers
        // bare, so the @ must be stripped server-side (first click, no cache yet)
        startServer();
        repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern/note.md?directory=.pkspkms-cache"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "First click should cache the file");
        assertTrue(response.body().contains("\"status\":\"success\""), "Response should have success status");
        assertTrue(Files.exists(CACHE_DIR.resolve("@gwern/note.md")), "Cached copy should exist on disk");

        HttpRequest bareRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/gwern/note.md?directory=.pkspkms-cache"))
                .GET()
                .build();
        HttpResponse<String> bareResponse = client.send(bareRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, bareResponse.statusCode(), "Bare alias URLs keep working");
    }

    @Test
    @DisplayName("GET /cache rejects location traversal outside the vault")
    void testCacheLocationTraversalRejected() throws IOException, InterruptedException, SQLException {
        startServer();
        repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern/..%2F..%2Fsecret.md?directory=.pkspkms-cache"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode(), "Traversal location must be rejected");
    }

    @Test
    @DisplayName("GET /cache rejects directory escapes without creating them")
    void testCacheDirectoryTraversalRejected() throws IOException, InterruptedException, SQLException {
        // Bare alias on purpose: the copy path must be reached so the
        // mkdirs-before-containment-check residual is exercised
        startServer();
        repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/gwern/note.md?directory=../outside-cache"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertNotEquals(200, response.statusCode(), "Escaping directory must be rejected");
        assertFalse(Files.exists(OUTSIDE_DIR), "Rejected paths must not create directories outside the vault");
    }

    @Test
    @DisplayName("GET /cache accepts an absolute in-vault directory")
    void testCacheAbsoluteInVaultDirectory() throws IOException, InterruptedException, SQLException {
        // The plugin sends an absolute server-side path to <vault>/pkspkms-cache:
        // File(root, absolute) uses the path as-is and containment allows in-vault
        // absolutes — this pins that contract
        startServer();
        repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");
        String absoluteCacheDir = TEST_DIR.toAbsolutePath().resolve(".pkspkms-cache").toString();
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/@gwern/note.md?directory="
                        + URLEncoder.encode(absoluteCacheDir, StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Absolute in-vault directory should work");
        assertTrue(Files.exists(CACHE_DIR.resolve("@gwern/note.md")), "Copy lands inside the vault");
    }

    @Test
    @DisplayName("GET /cache rejects an absolute directory outside the vault")
    void testCacheAbsoluteOutsideDirectoryRejected() throws IOException, InterruptedException, SQLException {
        startServer();
        repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");
        String outsideDir = Paths.get("target", "outside-abs-cache").toAbsolutePath().toString();
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/cache/gwern/note.md?directory="
                        + URLEncoder.encode(outsideDir, StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertNotEquals(200, response.statusCode(), "Absolute directory outside the vault must be rejected");
        assertFalse(Files.exists(Paths.get("target", "outside-abs-cache")), "No directory may be created");
    }
}
