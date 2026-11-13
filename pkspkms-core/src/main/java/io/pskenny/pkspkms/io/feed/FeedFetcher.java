package io.pskenny.pkspkms.io.feed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** HTTP fetching for remote feeds and OPML subscriptions. Fetch-once-per-start. */
public final class FeedFetcher {

    private static final String USER_AGENT = "PKSPKMS/0.1.0";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_FEED_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_REDIRECTS = 5;
    private static final long RETRY_DELAY_CAP_MS = 30_000;
    private static final long THROTTLE_MAX_INTERVAL_MS = 30_000;

    // Redirects are followed manually so every target passes the fetch policy
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // Test seams: sleeper and clock are swapped by FeedFetcherTest so pacing
    // and retries assert without real waiting
    static java.util.function.Consumer<Long> sleeper = FeedFetcher::sleepMs;
    static java.util.function.LongSupplier clockNanos = System::nanoTime;

    // SSRF guard seams: production rejects loopback/link-local/private-range
    // targets; FeedFetcherTest swaps them (harness servers run on loopback)
    static java.util.function.Predicate<String> hostGuard = FeedFetcher::isPublicHost;

    // H5: policy is now RRSET-level — every resolvable address must be
    // public, so an attacker cannot rotate a mixed record past a single
    // good-looking answer. Resolution goes through a seam so tests can fake
    // DNS (java.net has no JVM-local resolver hook).
    static java.util.function.Function<String, java.net.InetAddress[]> resolver = FeedFetcher::defaultResolver;

    static java.net.InetAddress[] defaultResolver(String host) {
        try {
            return java.net.InetAddress.getAllByName(host);
        } catch (java.net.UnknownHostException e) {
            return new java.net.InetAddress[0];
        }
    }

    // Post-read pin (H5): after the body was read, the host must still
    // resolve to one of the ADDRESSES validated before the fetch. A DNS
    // rebinder now waits ≤10 MB into a discarded buffer instead of landing
    // private content in the index. Swappable for the loopback harness.
    static java.util.function.BiPredicate<String, java.util.Set<java.net.InetAddress>> resolutionPin =
            FeedFetcher::resolutionMatches;

    private static final int MAX_ADDRESSES = 16;

    static List<java.net.InetAddress> resolveAll(String host) {
        if (host == null || host.isEmpty()) {
            return List.of();
        }
        java.net.InetAddress[] answers = resolver.apply(host);
        if (answers == null || answers.length == 0 || answers.length > MAX_ADDRESSES) {
            return List.of();
        }
        return List.of(answers);
    }

    // Resolvable hosts must be public — loopback/link-local/private-range
    // fetches are refused (B22), and the classification extends to ranges
    // InetAddress does not know: CGNAT 100.64/10 and benchmarking 198.18/15
    static boolean isPublicAddress(java.net.InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            return false;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if (first == 100 && (second & 0xC0) == 0x40) {
                return false; // CGNAT 100.64.0.0/10 — second octet 64..127
            }
            if (first == 198 && second >= 18 && second <= 19) {
                return false; // benchmarking 198.18.0.0/15
            }
        }
        return true;
    }

    // Resolvable hosts must be public — loopback/link-local/private-range
    // fetches are refused (B22); RRset form (H5). Kept as the default guard
    // so the test seam and restore reference the same method.
    static boolean isPublicHost(String host) {
        List<java.net.InetAddress> answers = resolveAll(host);
        if (answers.isEmpty()) {
            return false;
        }
        for (java.net.InetAddress answer : answers) {
            if (!isPublicAddress(answer)) {
                return false;
            }
        }
        return true;
    }

    static boolean resolutionMatches(String host, java.util.Set<java.net.InetAddress> validated) {
        List<java.net.InetAddress> answers = resolveAll(host);
        if (answers.isEmpty() || validated.isEmpty()) {
            return false;
        }
        for (java.net.InetAddress answer : answers) {
            if (validated.contains(answer)) {
                return true;
            }
        }
        return false;
    }

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

    /** Lenient loader for operator-configured sources: http(s) remote or local paths. */
    public static FeedLoader loader() {
        return FeedFetcher::load;
    }

    /**
     * Strict loader for untrusted-content sources (OPML-embedded subscriptions):
     * http(s) targets only — an untrusted outline can never read local files.
     */
    public static FeedLoader strictLoader() {
        return FeedFetcher::strictLoad;
    }

    static byte[] load(String source) throws IOException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return httpGet(source);
        }
        throw new IOException("non-http(s) feed source refused: " + source);
    }

    static byte[] strictLoad(String source) throws IOException {
        return load(source);
    }

    // Fetch policy: scheme http(s), host not loopback/link-local/private-range
    private static void checkTarget(URI target, List<java.net.InetAddress> validatedOut) throws IOException {
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("non-http(s) feed target refused: " + target);
        }
        if (!hostGuard.test(target.getHost())) {
            throw new IOException("Feed fetch refused private/loopback target: " + target);
        }
        // The answers the guard judged go alongside: the post-read pin (H5)
        // compares new DNS answers against exactly these.
        validatedOut.addAll(resolveAll(target.getHost()));
    }

    static byte[] httpGet(String url) throws IOException {
        URI target = URI.create(url);
        IOException failure = null;
        int redirectsLeft = MAX_REDIRECTS;
        int attempts = 0;
        long retryDelayMs = 0;

        while (true) {
            if (attempts >= MAX_ATTEMPTS) {
                throw failure != null ? failure : new IOException("Feed fetch exhausted retries: " + url);
            }
            List<java.net.InetAddress> validated = new ArrayList<>();
            checkTarget(target, validated);
            Throttle throttle = THROTTLES.computeIfAbsent(hostOf(target), host -> new Throttle());
            acquire(throttle, retryDelayMs);
            retryDelayMs = 0;

            HttpResponse<InputStream> response = send(target, url);
            int status = response.statusCode();

            if (status == 429 || status == 503) {
                response.body().close();
                attempts++;
                if (attempts >= MAX_ATTEMPTS) {
                    throw new IOException("Feed fetch exhausted retries: " + url);
                }
                failure = new IOException("Feed fetch failed with HTTP " + status + ": " + url);
                retryDelayMs = retryDelayMs(response.headers(), attempts);
                throttle.minIntervalMs = growInterval(throttle.minIntervalMs);
                continue;
            }
            if (status == 301 || status == 302 || status == 307 || status == 308) {
                response.body().close();
                if (redirectsLeft-- == 0) {
                    throw new IOException("Too many redirects: " + url);
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new IOException("Redirect without Location: " + url);
                }
                URI next = target.resolve(location);
                checkTarget(next, new ArrayList<>());
                target = next;
                attempts = 0;
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new IOException("Feed fetch failed with HTTP " + status + ": " + url);
            }

            byte[] body = readBody(response.body(), url);
            // Post-read pin (H5): the response is only valid while the host
            // still resolves to one of the validated addresses. A changed
            // RRset marks a rebinding attempt — the bytes were read (cap
            // applied) but land in nothing: no index, no cache, no export.
            if (!resolutionPin.test(target.getHost(), java.util.Set.copyOf(validated))) {
                throw new IOException(
                        "Feed host resolution changed mid-fetch — DNS rebinding pattern, discarding: " + url);
            }
            synchronized (throttle) {
                throttle.minIntervalMs = shrinkInterval(throttle.minIntervalMs);
            }
            return body;
        }
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
