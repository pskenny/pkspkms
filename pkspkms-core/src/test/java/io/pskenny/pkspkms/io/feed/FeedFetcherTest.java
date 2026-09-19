package io.pskenny.pkspkms.io.feed;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FeedFetcherTest {

    private static final byte[] BODY = "<rss version=\"2.0\"/>".getBytes(StandardCharsets.UTF_8);

    @Test
    void redirectIsFollowed() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            harness.serve("/a", 301, new byte[0], "http://localhost:" + harness.port() + "/b");
            harness.serve("/b", 200, BODY, null);

            assertArrayEquals(BODY, FeedFetcher.httpGet("http://localhost:" + harness.port() + "/a"));
        }
    }

    @Test
    void notFoundThrows() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            harness.serve("/missing", 404, new byte[0], null);

            assertThrows(IOException.class,
                    () -> FeedFetcher.httpGet("http://localhost:" + harness.port() + "/missing"));
        }
    }

    @Test
    void oversizedBodyThrows() throws Exception {
        try (HttpServerHarness harness = new HttpServerHarness()) {
            byte[] huge = new byte[11 * 1024 * 1024];
            harness.serve("/big", 200, huge, null);

            assertThrows(IOException.class,
                    () -> FeedFetcher.httpGet("http://localhost:" + harness.port() + "/big"));
        }
    }

    // http->https cross-protocol redirects (the libsyn 301 case) are covered by
    // HttpClient.Redirect.NORMAL semantics and verified in the live check —
    // com.sun.net.httpserver cannot speak TLS without a real keystore.

    /** Tiny com.sun.net.httpserver wrapper; one harness per test. */
    private static final class HttpServerHarness implements AutoCloseable {
        private final HttpServer server;
        private final int port;

        HttpServerHarness() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.start();
            port = server.getAddress().getPort();
        }

        int port() {
            return port;
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
