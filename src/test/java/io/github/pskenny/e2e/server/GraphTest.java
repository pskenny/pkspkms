package io.github.pskenny.e2e.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GraphTest {

//    private static final int TEST_PORT = 7001;
//    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
//    private static final Path TEST_DIR = Paths.get("target/test-data/server/graphs");
//    private static final Path TEST_DATA_DIR = Paths.get("test/data/graphs");
//    private static Javalin app;
//
//    static class MockNamespace extends Namespace {
//        public MockNamespace(Map<String, Object> attrs) {
//            super(attrs);
//        }
//        @Override
//        public <T> T get(String dest) {
//            return (T) super.get(dest);
//        }
//        @Override
//        public Integer getInt(String dest) {
//            return (Integer) get(dest);
//        }
//        @Override
//        public Boolean getBoolean(String dest) {
//            return (Boolean) get(dest);
//        }
//    }
//
//    public static class TestConfig extends PkspkmsConfig {
//
//        public TestConfig(String directory, int port, int depth) {
//            super(new io.github.pskenny.e2e.server.GraphTest.MockNamespace(Map.of(
//                    "directory", directory,
//                    "port", port,
//                    "depth", depth,
//                    "load", false
//            )));
//
//            this.command = Optional.of("test");
//            this.directory = Optional.of(directory);
//            this.port = Optional.of(port);
//            this.depth = Optional.of(depth);
//
//            // Ensure other fields accessed by the server are present, though they default to Optional.of("") or Optional.of(1) in the parent.
//            this.query = Optional.of("");
//            this.output = Optional.of("");
//            this.type = Optional.of("");
//            this.sqliteDb = Optional.of("");
//            this.options = Optional.of("");
//            this.includeLinked = Optional.of("");
//            this.loadData = false; // Manually set boolean field
//        }
//
//        // An optional constructor for simpler tests when only directory and port are needed
//        public TestConfig(String directory, int port) {
//            this(directory, port, 1); // Default depth to 1
//        }
//    }
//
//    @BeforeAll
//    static void setupServer() throws Exception {
//        Files.createDirectories(TEST_DIR);
//        copyDirectory(TEST_DATA_DIR, TEST_DIR);
//
//        PkspkmsConfig config = new GraphTest.TestConfig(TEST_DIR.toString(), TEST_PORT);
//        Server serverInstance = new Server(config);
//        app = serverInstance.getJavalinApp();
//    }
//
//    public static void copyDirectory(Path sourcePath, Path destinationPath) throws IOException {
//        Files.walk(sourcePath)
//                .forEach(source -> {
//                    String sdl = destinationPath.toFile().getPath();
//                    Path destination = Paths.get(sdl, source.toString()
//                            .substring(sourcePath.toFile().getPath().length()));
//                    try {
//                        Files.copy(source, destination);
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                });
//    }
//
//    public static boolean deleteDirectory(File directoryToBeDeleted) {
//        File[] allContents = directoryToBeDeleted.listFiles();
//        if (allContents != null) {
//            for (File file : allContents) {
//                deleteDirectory(file);
//            }
//        }
//        return directoryToBeDeleted.delete();
//    }
//
//    @AfterAll
//    static void tearDownServer() {
//        app.stop();
//        // Clean up the test directory
//        // Optional: Files.walk(TEST_DIR).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
//        deleteDirectory(TEST_DIR.toFile());
//    }
//
//    @Test
//    @DisplayName("GET /files/list/graph returns graph data")
//    void testFilesListGraphEndpoint() throws IOException, InterruptedException {
//        // ARRANGE: Mocks are set up in @BeforeAll
//
//        HttpClient client = HttpClient.newHttpClient();
//        HttpRequest request = HttpRequest.newBuilder()
//                .uri(URI.create(BASE_URL + "/files/list/graph"))
//                .GET()
//                .build();
//
//        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
//        assertEquals(200, response.statusCode(), "The /files/list/graph endpoint should return HTTP 200 OK.");
//
//        String body = response.body();
////        assertTrue(body.contains("\"filePath\":\"FileA.md\""), "Graph response should contain FileA.md");
//    }
//
//    // TODO test depth 2 graph
}