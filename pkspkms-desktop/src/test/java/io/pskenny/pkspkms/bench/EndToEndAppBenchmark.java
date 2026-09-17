package io.pskenny.pkspkms.bench;

import io.pskenny.pkspkms.Application;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EndToEndAppBenchmark {

    private static final Path PROJECT_ROOT = resolveProjectRoot();
    private static final int SERVER_PORT = 29876;
    private static final int WARMUP_ITERS = 3;
    private static final int MEASURED_ITERS = 10;

    private static final List<BenchResult> loadResults = new ArrayList<>();
    private static final List<BenchResult> queryResults = new ArrayList<>();
    private static final List<BenchResult> exportResults = new ArrayList<>();

    record BenchResult(String name, long avgMs, long minMs, long maxMs, long fileCount) {}

    @AfterAll
    static void printAllResults() {
        printLoadTable();
        printQueryTable();
        printExportTable();
    }

    // ── GROUP A: STARTUP & LOAD BENCHMARKS (FULL LIFE CYCLE) ──

    @Test
    @Order(1)
    void benchLoadBase() throws Exception {
        runAppServerStart("base", PROJECT_ROOT.resolve("test/data/pkms-examples/base"));
    }

//     long running, do not uncomment or remove
    @Test
    @Order(2)
    void benchLoadLuabase() throws Exception {
        runAppServerStart("luabase", PROJECT_ROOT.resolve("test/data/pkms-examples/luabase"));
    }

    @Test
    @Order(3)
    void benchLoadBaseLinks() throws Exception {
        runAppServerStart("links", PROJECT_ROOT.resolve("test/data/pkms-examples/links"));
    }

    @Test
    @Order(4)
    void benchLoadBaseCycle() throws Exception {
        runAppServerStart("base-cycle-dependancy", PROJECT_ROOT.resolve("test/data/base-cycle-dependancy"));
    }

    @Test
    @Order(5)
    void benchLoadPkmsExamples() throws Exception {
        runAppServerStart("example", PROJECT_ROOT.resolve("test/data/pkms-examples/example"));
    }

    // do not uncomment or remove these tests
//    @Test
//    @Order(6)
//    void benchLoadKnowledge() throws Exception {
//        runAppServerStart("knowledge", PROJECT_ROOT.resolve("test/temp/knowledge"));
//    }
//
//    @Test
//    @Order(7)
//    void benchLoadPages() throws Exception {
//        runAppServerStart("pages", PROJECT_ROOT.resolve("test/temp/pages"));
//    }

    @Test
    @Order(8)
    void benchLoadPkspkmsDocs() throws Exception {
        runAppServerStart("pkspkms docs", PROJECT_ROOT.resolve("docs"));
    }

    // ── GROUP B: QUERY BENCHMARKS──

    @Test
    @Order(9)
    void benchQuerySimple() throws Exception {
        runAppQuery("filePath:*.md", "filePath:*.md");
    }

    @Test
    @Order(10)
    void benchQueryTag() throws Exception {
        runAppQuery("tags:Tag", "tags:Tag");
    }

    @Test
    @Order(11)
    void benchQueryHasTag() throws Exception {
        runAppQuery("tags (has property)", "not tags:Private and access:Public");
    }

    @Test
    @Order(12)
    void benchQueryNotTag() throws Exception {
        runAppQuery("NOT tags:Tag", "NOT tags:Tag");
    }

    @Test
    @Order(13)
    void benchQueryAnd() throws Exception {
        runAppQuery("tags:Tag1 AND tags:Tag2", "tags:Tag1 AND tags:Tag2");
    }

    @Test
    @Order(14)
    void benchQueryOr() throws Exception {
        runAppQuery("(tags:Tag1) OR (tags:Tag2)", "(tags:Tag1) OR (tags:Tag2)");
    }

    // ── GROUP C: EXPORT BENCHMARKS (CLI LIFECYCLE) ──

    @Test
    @Order(15)
    void benchExportMarkdown() throws Exception {
        runAppExport("markdown export", "markdown");
    }

    @Test
    @Order(16)
    void benchExportCopy() throws Exception {
        runAppExport("copy export", "copy");
    }

    // ── BENCH RUNNERS (FULL LIFECYCLE) ──

    private void runAppServerStart(String label, Path vaultDir) throws Exception {
        long fileCount = countFiles(vaultDir);
        if (fileCount == 0) {
            System.out.println("SKIP: " + label + " (no files found)");
            return;
        }

        String dbFile = PROJECT_ROOT.resolve("pkspkms-desktop/target/bench-app-" + label + ".db").toString();
        double[] times = new double[WARMUP_ITERS + MEASURED_ITERS];

        for (int i = 0; i < WARMUP_ITERS + MEASURED_ITERS; i++) {
            int port = SERVER_PORT + i;
            Files.deleteIfExists(Path.of(dbFile));

            long start = System.nanoTime();
            Thread appThread = new Thread(() -> {
                try {
                    new Application(new String[]{
                            "server", "--directory", vaultDir.toString(),
                            "--port", String.valueOf(port), "--db", dbFile
                    });
                } catch (Exception ignored) {}
            });
            appThread.start();

            // Use HttpClient to poll /ping until NanoHTTPD is listening and ready
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest ping = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ping")).GET().build();
            while (appThread.isAlive()) {
                try {
                    HttpResponse<Void> resp = client.send(ping, HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() == 200) break;
                } catch (IOException e) {
                    try {
                        Thread.sleep(5); // poll quickly
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            long end = System.nanoTime();
            times[i] = (end - start) / 1_000_000.0;

            // Shutdown server cleanly
            appThread.interrupt();
            appThread.join(5000);
            Files.deleteIfExists(Path.of(dbFile));
        }

        double avg = 0, min = Double.MAX_VALUE, max = 0;
        for (int i = WARMUP_ITERS; i < WARMUP_ITERS + MEASURED_ITERS; i++) {
            avg += times[i];
            if (times[i] < min) min = times[i];
            if (times[i] > max) max = times[i];
        }
        avg /= MEASURED_ITERS;

        synchronized (loadResults) {
            loadResults.add(new BenchResult(label, (long) avg, (long) min, (long) max, fileCount));
        }
    }

    private void runAppQuery(String label, String query) throws Exception {
        String vaultDir = PROJECT_ROOT.resolve("test/data/pkms-examples").toString();
        String dbFile = PROJECT_ROOT.resolve("pkspkms-desktop/target/bench-app-query.db").toString();
        int port = SERVER_PORT + 99;

        Files.deleteIfExists(Path.of(dbFile));
        Thread appThread = new Thread(() -> {
            try {
                new Application(new String[]{
                        "server", "--directory", vaultDir,
                        "--port", String.valueOf(port), "--db", dbFile
                });
            } catch (Exception ignored) {}
        });
        appThread.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest ping = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ping")).GET().build();
        while (appThread.isAlive()) {
            try {
                HttpResponse<Void> resp = client.send(ping, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) break;
            } catch (IOException e) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest queryReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/files/search?query=" + encodedQuery))
                .GET().build();

        // Warm up
        for (int i = 0; i < WARMUP_ITERS; i++) {
            client.send(queryReq, HttpResponse.BodyHandlers.discarding());
        }

        // Measure
        double[] times = new double[MEASURED_ITERS];
        for (int i = 0; i < MEASURED_ITERS; i++) {
            long start = System.nanoTime();
            HttpResponse<String> resp = client.send(queryReq, HttpResponse.BodyHandlers.ofString());
            long end = System.nanoTime();
            times[i] = (end - start) / 1_000_000.0;
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("HTTP query failed with status: " + resp.statusCode());
            }
        }

        // Shutdown
        appThread.interrupt();
        appThread.join(5000);
        Files.deleteIfExists(Path.of(dbFile));

        double avg = 0, min = Double.MAX_VALUE, max = 0;
        for (double t : times) {
            avg += t;
            if (t < min) min = t;
            if (t > max) max = t;
        }
        avg /= MEASURED_ITERS;

        synchronized (queryResults) {
            queryResults.add(new BenchResult(label, (long) avg, (long) min, (long) max, 0));
        }
    }

    private void runAppExport(String label, String type) throws Exception {
        Path outputDir = PROJECT_ROOT.resolve("pkspkms-desktop/target/bench-app-export");
        deleteDirectory(outputDir);
        Files.createDirectories(outputDir);
        String vaultDir = PROJECT_ROOT.resolve("test/data/pkms-examples").toString();
        String dbFile = PROJECT_ROOT.resolve("pkspkms-desktop/target/bench-app.db").toString();

        double[] times = new double[WARMUP_ITERS + MEASURED_ITERS];
        for (int i = 0; i < WARMUP_ITERS + MEASURED_ITERS; i++) {
            deleteDirectory(outputDir);
            Files.createDirectories(outputDir);
            Files.deleteIfExists(Path.of(dbFile));

            long start = System.nanoTime();
            new Application(new String[]{
                    "export", "--directory", vaultDir, "--db", dbFile,
                    "--output", outputDir.toString(), "--type", type
            });
            long end = System.nanoTime();
            times[i] = (end - start) / 1_000_000.0;

            Files.deleteIfExists(Path.of(dbFile));
        }

        double avg = 0, min = Double.MAX_VALUE, max = 0;
        for (int i = WARMUP_ITERS; i < WARMUP_ITERS + MEASURED_ITERS; i++) {
            avg += times[i];
            if (times[i] < min) min = times[i];
            if (times[i] > max) max = times[i];
        }
        avg /= MEASURED_ITERS;

        synchronized (exportResults) {
            exportResults.add(new BenchResult(label, (long) avg, (long) min, (long) max, 0));
        }
        deleteDirectory(outputDir);
    }

    // ── UTILITIES ──

    private static Path resolveProjectRoot() {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path current = start;
        while (current != null) {
            if (Files.exists(current.resolve("pkspkms-core/pom.xml"))) return current;
            current = current.getParent();
        }
        return start;
    }

    private static long countFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    // ── CONSOLIDATED TABLE PRINTERS ──

    private static void printLoadTable() {
        System.out.println();
        System.out.println("=" .repeat(76));
        System.out.println("  1. Startup & Load E2E Benchmarks (Through Application CLI)");
        System.out.println("  Warmup: " + WARMUP_ITERS + " | Measured: " + MEASURED_ITERS);
        System.out.println("=" .repeat(76));
        System.out.printf("%-30s %10s %11s %11s %10s%n", "Scenario", "Files", "Avg (ms)", "Min (ms)", "Max (ms)");
        System.out.println("-".repeat(76));
        for (BenchResult r : loadResults) {
            System.out.printf("%-30s %10d %11d %11d %10d%n", r.name(), r.fileCount(), r.avgMs(), r.minMs(), r.maxMs());
        }
        System.out.println();
    }

    private static void printQueryTable() {
        System.out.println("=" .repeat(76));
        System.out.println("  2. HTTP Search API Queries Benchmarks (Through Network HTTP)");
        System.out.println("=" .repeat(76));
        System.out.printf("%-35s %12s %11s %11s%n", "Query Endpoint", "Avg (ms)", "Min (ms)", "Max (ms)");
        System.out.println("-".repeat(76));
        for (BenchResult r : queryResults) {
            System.out.printf("%-35s %12d %11d %11d%n", r.name(), r.avgMs(), r.minMs(), r.maxMs());
        }
        System.out.println();
    }

    private static void printExportTable() {
        System.out.println("=" .repeat(76));
        System.out.println("  3. E2E Export CLI Benchmarks (Through Application CLI)");
        System.out.println("=" .repeat(76));
        System.out.printf("%-35s %12s %11s %11s%n", "Scenario", "Avg (ms)", "Min (ms)", "Max (ms)");
        System.out.println("-".repeat(76));
        for (BenchResult r : exportResults) {
            System.out.printf("%-35s %12d %11d %11d%n", r.name(), r.avgMs(), r.minMs(), r.maxMs());
        }
        System.out.println();
    }
}
