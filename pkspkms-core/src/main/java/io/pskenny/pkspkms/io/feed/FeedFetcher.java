package io.pskenny.pkspkms.io.feed;

import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** HTTP fetching for remote feeds and OPML subscriptions. Fetch-once-per-start. */
public final class FeedFetcher {

    private static final String USER_AGENT = "PKSPKMS/0.1.0";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_FEED_BYTES = 10 * 1024 * 1024;

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
        URL target = URI.create(url).toURL();
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setInstanceFollowRedirects(true);

        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Feed fetch failed with HTTP " + status + ": " + url);
        }
        try (InputStream in = connection.getInputStream()) {
            return in.readNBytes(MAX_FEED_BYTES + 1);
        } finally {
            connection.disconnect();
        }
    }

    public static void enforceLimit(byte[] bytes, String url) throws IOException {
        if (bytes.length > MAX_FEED_BYTES) {
            throw new IOException("Feed exceeds " + MAX_FEED_BYTES + " bytes: " + url);
        }
    }

    /** Functional source: feed/OPML URL or local path to bytes. */
    @FunctionalInterface
    public interface FeedLoader {
        byte[] load(String source) throws IOException;
    }
}
