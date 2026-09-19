package io.pskenny.pkspkms.io.feed;

import com.sun.net.httpserver.HttpServer;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem.SynthFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FeedFileSystemTest {

    private static final String RSS = """
            <rss version="2.0"><channel>
              <title>CoRecursive Podcast</title>
              <link>https://corecursive.com/</link>
              <description>Software dev podcast</description>
              <item>
                <title>Show notes</title>
                <link>https://corecursive.com/ep1</link>
                <pubDate>Tue, 01 Jul 2025 10:30:00 GMT</pubDate>
                <enclosure url="https://cdn/ep1.mp3" length="100" type="audio/mpeg"/>
              </item>
              <item><title>Duplicate</title></item>
              <item><title>Duplicate</title></item>
            </channel></rss>
            """;

    @Test
    void laysOutChannelNoteAndDatePrefixedItems() throws Exception {
        FeedFileSystem fs = new FeedFileSystem(RSS.getBytes(StandardCharsets.UTF_8), "https://corecursive.com/rss", 1000L);

        List<String> paths = fs.listFiles(List.of()).stream().map(e -> e.relativePath()).toList();
        assertEquals(List.of(
                "CoRecursive Podcast.md",
                "CoRecursive Podcast/2025-07-01 Show notes.md",
                "CoRecursive Podcast/Duplicate-2.md",
                "CoRecursive Podcast/Duplicate.md"), paths, "channel note + items nested under the feed, collision dedupe");

        SynthFile channel = resolve(fs, "CoRecursive Podcast.md");
        assertTrue(channel.content().contains("title: \"CoRecursive Podcast\""), "channel title");
        assertTrue(channel.content().contains("url: \"https://corecursive.com/\""), "channel url");
        assertTrue(channel.content().contains("rss: \"https://corecursive.com/rss\""), "channel rss source");
        assertTrue(channel.content().contains("description: \"Software dev podcast\""), "channel description");

        SynthFile item = resolve(fs, "CoRecursive Podcast/2025-07-01 Show notes.md");
        assertTrue(item.content().contains("title: \"Show notes\""));
        assertTrue(item.content().contains("url: \"https://corecursive.com/ep1\""));
        assertTrue(item.content().contains("published: \"2025-07-01T10:30:00Z\""));
        assertTrue(item.content().contains("media: \"https://cdn/ep1.mp3\""), "podcast enclosure -> media property");

        SynthFile undated = resolve(fs, "CoRecursive Podcast/Duplicate.md");
        assertTrue(undated.content().startsWith("---\ntitle: \"Duplicate\"\n"), "no date -> no prefix");
        assertTrue(fs.exists("CoRecursive Podcast/Duplicate-2.md"), "collisions deduped");
    }

    @Test
    void readsWriteThrows() throws Exception {
        FeedFileSystem fs = new FeedFileSystem(RSS.getBytes(StandardCharsets.UTF_8), null, 42L);

        SynthFile channel = resolve(fs, "CoRecursive Podcast.md");
        assertEquals(42L, channel.mtime(), "caller-supplied mtime drives incremental loads");

        assertThrows(UnsupportedOperationException.class, () -> fs.writeString("x.md", "y"));
        assertThrows(UnsupportedOperationException.class, () -> fs.openOutput("x.md"));
        assertTrue(!fs.exists("nope.md"));
    }

    @Test
    void fetchesOverHttp() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        byte[] body = RSS.getBytes(StandardCharsets.UTF_8);
        server.createContext("/feed.xml", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            FeedFileSystem fs = new FeedFileSystem(
                    FeedFetcher.httpGet("http://localhost:" + server.getAddress().getPort() + "/feed.xml"),
                    "https://corecursive.com/rss",
                    System.currentTimeMillis());
            assertTrue(fs.exists("CoRecursive Podcast.md"), "fetched feed materializes the channel note");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void untitledChannelStillMounts() throws Exception {
        // bsky-style bridges can ship a channel without a title: must not NPE
        FeedFileSystem fs = new FeedFileSystem("<rss version=\"2.0\"><channel><link>https://x/</link></channel></rss>"
                .getBytes(StandardCharsets.UTF_8), null, 7L);

        assertTrue(fs.exists("untitled.md"), "channel without title -> untitled note");
    }

    @Test
    void podcastFrontmatterAndMediaEmbed() throws Exception {
        String podcastRss = """
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0" xmlns:slash="http://purl.org/rss/1.0/modules/slash/">
                <channel>
                  <title>Pod</title>
                  <language>en-us</language>
                  <lastBuildDate>Wed, 02 Jul 2025 08:00:00 GMT</lastBuildDate>
                  <itunes:category text="Health"/>
                  <itunes:image href="https://cdn/cover.jpg"/>
                  <item>
                    <title>Episode</title>
                    <link>https://pod/ep</link>
                    <pubDate>Tue, 01 Jul 2025 10:30:00 GMT</pubDate>
                    <category>Health</category>
                    <itunes:duration>1:02:05</itunes:duration>
                    <itunes:episode>12</itunes:episode>
                    <itunes:season>3</itunes:season>
                    <comments>https://pod/ep#comments</comments>
                    <slash:comments>42</slash:comments>
                    <enclosure url="https://cdn/ep.mp3" type="audio/mpeg" length="100"/>
                  </item>
                  <item><title>Pdf</title><enclosure url="https://cdn/doc.pdf" type="application/pdf"/></item>
                  <item><title>Typeless</title><enclosure url="https://cdn/x.mp3"/></item>
                </channel></rss>
                """;
        FeedFileSystem fs = new FeedFileSystem(podcastRss.getBytes(StandardCharsets.UTF_8), "https://pod/rss", 9L);

        String episode = new String(fs.openInput("Pod/2025-07-01 Episode.md").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(episode.contains("tags:\n  - \"Health\"\n"), "item categories -> tags list");
        assertTrue(episode.contains("\nseconds: 3725\n"), "numeric fields bind unquoted");
        assertTrue(episode.contains("\nepisode: 12\n"));
        assertTrue(episode.contains("\nseason: 3\n"));
        assertTrue(episode.contains("\ncommentsCount: 42\n"));
        String body = episode.substring(episode.lastIndexOf("---\n") + 4);
        assertTrue(body.startsWith("![](https://cdn/ep.mp3)\n\n"), "audio embed leads the body");

        String pdf = new String(fs.openInput("Pod/Pdf.md").readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(pdf.contains("![]("), "non-media enclosures do not embed");

        String typeless = new String(fs.openInput("Pod/Typeless.md").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(typeless.contains("![](https://cdn/x.mp3)"), "extension fallback when no type attr");

        String channel = new String(fs.openInput("Pod.md").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(channel.contains("language: \"en-us\""));
        assertTrue(channel.contains("modified: \"2025-07-02T08:00:00Z\""));
        assertTrue(channel.contains("tags:\n  - \"Health\"\n"));
        assertTrue(channel.contains("image: \"https://cdn/cover.jpg\""));
    }

    @Test
    void youtubeItemUrlEmbedsInBody() throws Exception {
        String rss = """
                <rss version="2.0"><channel><title>Vids</title>
                  <item><title>Watch</title><link>https://www.youtube.com/watch?v=dQw4w9WgXcQ&amp;list=xyz</link></item>
                  <item><title>Shorts</title><link>https://www.youtube.com/shorts/abc_-9xyz</link></item>
                  <item><title>Shortlink</title><link>https://youtu.be/xyz_-987abc</link></item>
                  <item><title>Plain</title><link>https://example.com/watch?v=dQw4w9WgXcQ</link></item>
                  <item><title>Both</title><link>https://www.youtube.com/watch?v=abc12345678</link>
                    <enclosure url="https://cdn/x.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """;
        FeedFileSystem fs = new FeedFileSystem(rss.getBytes(StandardCharsets.UTF_8), null, 3L);

        String watch = new String(fs.openInput("Vids/Watch.md").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(watch.contains("\n![](https://www.youtube.com/watch?v=dQw4w9WgXcQ)\n"), "watch URL embeds");
        assertTrue(watch.contains("url: \"https://www.youtube.com/watch?v=dQw4w9WgXcQ&amp;list=xyz\"")
                || watch.contains("&list=xyz"), "frontmatter url keeps the original link");

        String shorts = new String(fs.openInput("Vids/Shorts.md").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(shorts.contains("![](https://www.youtube.com/watch?v=abc_-9xyz)"), "shorts normalize to watch URLs");

        String shortlink = new String(fs.openInput("Vids/Shortlink.md").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(shortlink.contains("![](https://www.youtube.com/watch?v=xyz_-987abc)"), "youtu.be normalizes to watch URLs");

        String plain = new String(fs.openInput("Vids/Plain.md").readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(plain.contains("![](https://www.youtube.com"), "non-YouTube urls do not embed");

        String both = new String(fs.openInput("Vids/Both.md").readAllBytes(), StandardCharsets.UTF_8);
        int enclosureAt = both.indexOf("![](https://cdn/x.mp3)");
        int youtubeAt = both.indexOf("![](https://www.youtube.com/watch?v=abc12345678)");
        assertTrue(enclosureAt != -1 && youtubeAt != -1, "both embeds present");
        assertTrue(enclosureAt < youtubeAt, "media embed comes first");
    }

    private static SynthFile resolve(FeedFileSystem fs, String path) throws IOException {
        // mtime comes from the listed entry; content from openInput
        var entry = fs.listFiles(List.of()).stream()
                .filter(e -> e.relativePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing synthesized file: " + path));
        try (java.io.InputStream in = fs.openInput(path)) {
            return new SynthFile(new String(in.readAllBytes(), StandardCharsets.UTF_8), entry.lastModified());
        }
    }
}
