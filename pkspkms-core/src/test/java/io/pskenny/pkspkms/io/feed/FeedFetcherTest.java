package io.pskenny.pkspkms.io.feed;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FeedFetcherTest {

    private static final byte[] BODY = "<rss version=\"2.0\"/>".getBytes(StandardCharsets.UTF_8);

    private final List<Long> sleeps = new ArrayList<>();
    private final AtomicLong fakeNanos = new AtomicLong();

    @BeforeEach
    void installSeams() {
        FeedFetcher.resetThrottles();
        // Deterministic fake time: the sleeper advances the clock instead of
        // blocking, so throttle spacing and retry delays assert exactly
        FeedFetcher.clockNanos = () -> fakeNanos.get();
        FeedFetcher.sleeper = ms -> {
            sleeps.add(ms);
            fakeNanos.addAndGet(ms * 1_000_000);
        };
    }

    @AfterEach
    void restoreSeams() {
        FeedFetcher.resetThrottles();
        FeedFetcher.sleeper = FeedFetcher::sleepMs;
        FeedFetcher.clockNanos = System::nanoTime;
    }

    @Test
    void redirectIsFollowed() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            harness.serve("/a", 301, new byte[0], "http://localhost:" + harness.port() + "/b");
            harness.serveStatic("/b", 200, BODY);

            assertArrayEquals(BODY, FeedFetcher.httpGet("http://localhost:" + harness.port() + "/a"));
        }
    }

    @Test
    void notFoundThrows() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            harness.serveStatic("/missing", 404, new byte[0]);

            assertThrows(IOException.class,
                    () -> FeedFetcher.httpGet("http://localhost:" + harness.port() + "/missing"));
        }
    }

    @Test
    void oversizedBodyThrows() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            byte[] huge = new byte[11 * 1024 * 1024];
            harness.serveStatic("/big", 200, huge);

            assertThrows(IOException.class,
                    () -> FeedFetcher.httpGet("http://localhost:" + harness.port() + "/big"));
        }
    }

    @Test
    void rateLimitRetriesThenSucceeds() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            harness.serveSequence("/a", List.of(429, 429, 200), null);

            assertArrayEquals(BODY, FeedFetcher.httpGet("http://localhost:" + harness.port() + "/a"));
            assertEquals(3, harness.hits("/a"), "two retries before success");
            assertEquals(List.of(1_000L, 2_000L), sleeps, "exponential backoff 1s then 2s");
            assertEquals(1_000L, FeedFetcher.intervalOf("localhost"), "grew to 2s, success decayed to 1s");
        }
    }

    @Test
    void retryAfterHeaderHonored() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            harness.serveSequence("/a", List.of(429, 200), "1");

            assertArrayEquals(BODY, FeedFetcher.httpGet("http://localhost:" + harness.port() + "/a"));
            assertEquals(List.of(1_000L), sleeps, "Retry-After wins over the exponential default");
        }
    }

    @Test
    void persistent429ThrowsAfterBudget() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            harness.serveSequence("/a", List.of(429, 429, 429, 429), null);

            assertThrows(IOException.class,
                    () -> FeedFetcher.httpGet("http://localhost:" + harness.port() + "/a"));
            assertEquals(3, harness.hits("/a"), "retry budget is 2");
        }
    }

    @Test
    void throttleSpacesSameHostOnly() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            // Grow the interval: 429 then success leaves minInterval at 500ms
            harness.serveSequence("/a", List.of(429, 200), null);
            assertArrayEquals(BODY, FeedFetcher.httpGet("http://localhost:" + harness.port() + "/a"));
            sleeps.clear();

            // Second request on the throttled host waits out the remaining slot
            // (reserved while the interval was 1s), then paces at the decayed 500ms
            harness.serveStatic("/b", 200, BODY);
            assertArrayEquals(BODY, FeedFetcher.httpGet("http://localhost:" + harness.port() + "/b"));
            harness.serveStatic("/b2", 200, BODY);
            assertArrayEquals(BODY, FeedFetcher.httpGet("http://localhost:" + harness.port() + "/b2"));
            assertEquals(List.of(1000L, 500L), sleeps, "reserved slot first, then the decayed interval");

            // A different host has an independent bucket: no wait
            sleeps.clear();
            try (HttpServerHarness other = new HttpServerHarness()) {
                other.serveStatic("/c", 200, BODY);
                assertArrayEquals(BODY, FeedFetcher.httpGet("http://127.0.0.1:" + other.port() + "/c"));
                assertEquals(0, sleeps.size(), "other hosts never wait");
            }
        }
    }

    // http->https cross-protocol redirects (the libsyn 301 case) are covered by
    // HttpClient.Redirect.NORMAL semantics and verified in the live check —
    // com.sun.net.httpserver cannot speak TLS without a real keystore.

    /** Tiny com.sun.net.httpserver wrapper; one harness per test. */
    private static final class HttpServerHarness implements AutoCloseable {
        private final HttpServer server;
        private final int port;
        private final Map<String, AtomicInteger> hits = new java.util.HashMap<>();

        HttpServerHarness() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.start();
            port = server.getAddress().getPort();
        }

        int port() {
            return port;
        }

        int hits(String path) {
            return hits.get(path).get();
        }

        void serveStatic(String path, int status, byte[] body) {
            serve(path, status, body, null);
        }

        void serveSequence(String path, List<Integer> statuses, String retryAfter) {
            AtomicInteger index = new AtomicInteger();
            AtomicInteger count = new AtomicInteger();
            hits.put(path, count);
            server.createContext(path, exchange -> {
                count.incrementAndGet();
                int position = Math.min(index.getAndIncrement(), statuses.size() - 1);
                int status = statuses.get(position);
                byte[] body = status == 200 ? BODY : new byte[0];
                if (retryAfter != null && (status == 429 || status == 503)) {
                    exchange.getResponseHeaders().add("Retry-After", retryAfter);
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
        }

        void serve(String path, int status, byte[] body, String location) {
            server.createContext(path, exchange -> {
                if (location != null) {
                    exchange.getResponseHeaders().add("Location", location);
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
