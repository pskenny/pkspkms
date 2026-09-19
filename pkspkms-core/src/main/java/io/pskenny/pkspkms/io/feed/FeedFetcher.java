package io.pskenny.pkspkms.io.feed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** HTTP fetching for remote feeds and OPML subscriptions. Fetch-once-per-start. */
public final class FeedFetcher {

    private static final String USER_AGENT = "PKSPKMS/0.1.0";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_FEED_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_CAP_MS = 30_000;
    private static final long THROTTLE_MAX_INTERVAL_MS = 30_000;

    // NORMAL follows cross-protocol http->https 301s (libsyn-style) but blocks
    // secure-to-insecure downgrades
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // Test seams: sleeper and clock are swapped by FeedFetcherTest so pacing
    // and retries assert without real waiting
    static java.util.function.Consumer<Long> sleeper = FeedFetcher::sleepMs;
    static java.util.function.LongSupplier clockNanos = System::nanoTime;

    // --- per-host pacing (AIMD) ---

    // Interval starts at 0 (no delay for small vaults); 429/503 doubles it,
    // success halves it — a burst self-throttles after the first rejection
    private static final java.util.concurrent.ConcurrentHashMap<String, Throttle> THROTTLES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Throttle {
        long minIntervalMs;
        long nextSlotAtNanos;
    }

    static void resetThrottles() {
        THROTTLES.clear();
    }

    static long intervalOf(String host) {
        Throttle throttle = THROTTLES.get(host);
        return throttle == null ? 0 : throttle.minIntervalMs;
    }

    // Sleeps until the host's slot frees, advancing it by the current interval;
    // a retry delay (Retry-After / exponential) rides the same wait
    private static void acquire(Throttle throttle, long retryDelayMs) {
        long waitMs;
        synchronized (throttle) {
            long now = clockNanos.getAsLong();
            long remainingMs = Math.max(0, (throttle.nextSlotAtNanos - now) / 1_000_000L);
            waitMs = Math.max(remainingMs, retryDelayMs);
            // This attempt starts after waitMs; the next one must clear minInterval
            throttle.nextSlotAtNanos = now + (waitMs + throttle.minIntervalMs) * 1_000_000L;
        }
        if (waitMs > 0) {
            sleeper.accept(waitMs);
        }
    }

    private static long growInterval(long intervalMs) {
        return Math.min(intervalMs == 0 ? 1_000 : intervalMs * 2, THROTTLE_MAX_INTERVAL_MS);
    }

    private static long shrinkInterval(long intervalMs) {
        return intervalMs / 2;
    }

    static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private FeedFetcher() {}

    /** Loads a feed or OPML source: http(s) URLs are fetched, anything else is a local path. */
    public static FeedLoader loader() {
        return FeedFetcher::load;
    }

    static byte[] load(String source) throws IOException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return httpGet(source);
        }
        return Files.readAllBytes(Path.of(source));
    }

    static byte[] httpGet(String url) throws IOException {
        URI uri = URI.create(url);
        Throttle throttle = THROTTLES.computeIfAbsent(hostOf(uri), host -> new Throttle());

        long retryDelayMs = 0;
        IOException blocked = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            acquire(throttle, retryDelayMs);
            retryDelayMs = 0;

            HttpResponse<InputStream> response = send(uri, url);
            int status = response.statusCode();
            if (status == 429 || status == 503) {
                response.body().close();
                blocked = new IOException("Feed fetch failed with HTTP " + status + ": " + url);
                retryDelayMs = retryDelayMs(response.headers(), attempt);
                throttle.minIntervalMs = growInterval(throttle.minIntervalMs);
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new IOException("Feed fetch failed with HTTP " + status + ": " + url);
            }

            byte[] body = readBody(response.body(), url);
            synchronized (throttle) {
                throttle.minIntervalMs = shrinkInterval(throttle.minIntervalMs);
            }
            return body;
        }
        throw blocked;
    }

    private static HttpResponse<InputStream> send(URI uri, String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Feed fetch interrupted: " + url, e);
        } catch (IOException e) {
            throw new IOException("Feed fetch failed: " + url + " (" + e.getMessage() + ")", e);
        }
    }

    // Retry-After wins when present; otherwise exponential 1s, 2s, capped
    private static long retryDelayMs(java.net.http.HttpHeaders headers, int attempt) {
        java.util.Optional<String> retryAfter = headers.firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                return Math.min(Long.parseLong(retryAfter.get().strip()) * 1000L, RETRY_DELAY_CAP_MS);
            } catch (NumberFormatException e) {
                // HTTP-date form isn't worth parsing — fall through to exponential
            }
        }
        return Math.min(1_000L << (attempt - 1), RETRY_DELAY_CAP_MS);
    }

    // ofInputStream so the cap is enforced while reading, not after
    private static byte[] readBody(InputStream body, String url) throws IOException {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(MAX_FEED_BYTES + 1);
            if (bytes.length > MAX_FEED_BYTES) {
                throw new IOException("Feed exceeds " + MAX_FEED_BYTES + " bytes: " + url);
            }
            return bytes;
        } catch (IOException e) {
            throw new IOException("Feed read failed: " + url + " (" + e.getMessage() + ")", e);
        }
    }

    private static String hostOf(URI uri) {
        String host = uri.getHost();
        return host == null ? "" : io.pskenny.pkspkms.io.PathUtil.lower(host);
    }

    /** Functional source: feed/OPML URL or local path to bytes. */
    @FunctionalInterface
    public interface FeedLoader {
        byte[] load(String source) throws IOException;
    }
}
