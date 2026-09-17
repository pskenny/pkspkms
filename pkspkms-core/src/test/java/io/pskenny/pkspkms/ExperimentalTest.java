package io.pskenny.pkspkms;

import fi.iki.elonen.NanoHTTPD;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.*;
import static io.pskenny.pkspkms.test.JsonUtil.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExperimentalTest {

    private Export export;
    private final Path TEST_DIR = Paths.get("target", "test-experimental", ExportTest.class.getSimpleName());
    private final Path MARKDOWN_DIR = Paths.get(System.getProperty("user.dir")).getParent().toAbsolutePath().resolve("docs");
    private final Path MY_NOTES = Paths.get("/home/pk/Dropbox/Notes").toAbsolutePath();
    private final int TEST_PORT = 7001;
    private final String BASE_URL = "http://localhost:" + TEST_PORT;
    private Server app;

    @BeforeEach
    void setup() throws IOException {
        if (!Files.exists(TEST_DIR)) {
            Files.createDirectories(TEST_DIR);
        }
    }

    @AfterEach
    void tearDown() {
        // Delete files in test directory as cleanup
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

        if (app != null && app.wasStarted()) {
            app.stop();
        }
    }

    void startServer() throws IOException {
//        String dir = MARKDOWN_DIR.toString();
        String dir = MY_NOTES.toString();
        PkmsFileSystem fs = new JavaFileSystem(new File(dir));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + new File("pkspkms.db").getAbsolutePath(), null, fs);
        app = new Server(TEST_PORT, repository);
        app.loadRepo();
        app.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

//    @Test
    @DisplayName("Server: Experimental test to see why some results are missing")
    void testFilesListEndpoint() throws IOException, InterruptedException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list?query=tags%3Alonelyvaultproblem"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The /files/list endpoint should return HTTP 200 OK.");
        String actual = response.body();
        String expectedJson = readFile("test/data/response/files-list.json");
//        assertJsonEquals(expectedJson, actual, false);

        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/files/list/graph"))
                .GET()
                .build();
        HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response2.statusCode(), "The /files/list/graph endpoint should return HTTP 200 OK.");
        String actual2 = response2.body();
        String expectedJson2 = readFile("test/data/response/files-list.json");
        assertJsonEquals(expectedJson2, actual2, false);
    }

//    @Test
    @DisplayName("Server: iterate each file in docs and query /files/list/graph by filePath")
    void testFilesListGraphPerFile() throws IOException {
        startServer();
        HttpClient client = HttpClient.newHttpClient();

        try (Stream<Path> walk = Files.walk(MY_NOTES)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String relative = MY_NOTES.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                String encoded;
                try {
                    encoded = java.net.URLEncoder.encode(relative, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                String uri = BASE_URL + "/files/list/graph?query=filePath%3A%22" + encoded + "%22";
                try {
                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(uri)).GET().build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    String body = response.body();
                    if (body.equals("{\"files\":[],\"resultSize\":0}")) {
                        System.out.println("bazinga");
                    }
                    System.out.println(relative + " -> " + response.statusCode() + " body=" + body);
                    assertEquals(200, response.statusCode(), "filePath:" + relative + " should return 200");
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    //    @Test
    @DisplayName("Export: Experiment test why some results are missing")
    void testExportDryRunDoesntWriteFiles() {
        Export.ExportConfig config = new Export.ExportConfig(
                TEST_DIR.toString(),
                "pkspkms.db",
                "filePath:*.md",
                MARKDOWN_DIR.toString(),
                "markdown",
                true);
        PkmsFileSystem inputFs = new JavaFileSystem(new File(config.directory()));
        PkmsFileSystem outputFs = new JavaFileSystem(new File(config.output()));
        SQLitePksFileRepository repository = new SQLitePksFileRepository("jdbc:sqlite:" + config.dbPath(), null, inputFs);
        repository.loadDirectoryIntoRepository();
        export = new Export(config, repository, inputFs, outputFs);
        export.export();

        assertEquals(false, fileExists(TEST_DIR + "/output/test.md"));
    }
}
