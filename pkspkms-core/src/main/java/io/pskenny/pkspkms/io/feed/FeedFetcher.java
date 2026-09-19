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

    // NORMAL follows cross-protocol http->https 301s (libsyn-style) but blocks
    // secure-to-insecure downgrades
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Feed fetch interrupted: " + url, e);
        } catch (IOException e) {
            throw new IOException("Feed fetch failed: " + url + " (" + e.getMessage() + ")", e);
        }

        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Feed fetch failed with HTTP " + response.statusCode() + ": " + url);
        }

        // ofInputStream so the cap is enforced while reading, not after
        try (InputStream in = response.body()) {
            byte[] body = in.readNBytes(MAX_FEED_BYTES + 1);
            if (body.length > MAX_FEED_BYTES) {
                throw new IOException("Feed exceeds " + MAX_FEED_BYTES + " bytes: " + url);
            }
            return body;
        } catch (IOException e) {
            throw new IOException("Feed read failed: " + url + " (" + e.getMessage() + ")", e);
        }
    }

    /** Functional source: feed/OPML URL or local path to bytes. */
    @FunctionalInterface
    public interface FeedLoader {
        byte[] load(String source) throws IOException;
    }
}
