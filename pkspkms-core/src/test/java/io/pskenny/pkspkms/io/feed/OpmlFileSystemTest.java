package io.pskenny.pkspkms.io.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OpmlFileSystemTest {

    @TempDir
    Path vault;

    // Mixed tree: outliner branches plus one xmlUrl subscription
    private static final String OPML = """
            <?xml version="1.0" encoding="utf-8"?>
            <opml version="2.0">
              <head><title>Gwern</title></head>
              <body>
                <outline/>
                <outline text="Work">
                  <outline text="Topic 1" _note="Nested note&#10;second line"/>
                  <outline text="has/slash"/>
                  <outline text="Reference" htmlUrl="https://viznut.fi/unscii/" type="link"/>
                </outline>
                <outline text="Podcasts" type="rss" xmlUrl="LOCAL_FEED"/>
              </body>
            </opml>
            """;

    private OpmlFileSystem fsWithLocalFeed(String feedXml) throws IOException {
        Path feedFile = vault.resolve("fixture-feed.xml");
        Files.writeString(feedFile, feedXml);
        // Non-http sources are treated as local paths, so the fixture feed needs no network
        String opml = OPML.replace("LOCAL_FEED", feedFile.toAbsolutePath().toString());
        return new OpmlFileSystem(opml.getBytes(StandardCharsets.UTF_8), 1000L, FeedFetcher.loader());
    }

    @Test
    void laysOutTheWholeTree() throws Exception {
        OpmlFileSystem fs = fsWithLocalFeed("<rss version=\"2.0\"><channel><title>F</title></channel></rss>");

        List<String> paths = fs.listFiles(List.of()).stream().map(e -> e.relativePath()).toList();
        // minimal fixture feed: channel note only, no items
        assertEquals(List.of(
                "Podcasts.md",
                "Untitled.md",
                "Work.md",
                "Work/Reference.md",
                "Work/Topic 1.md",
                "Work/hasslash.md"), paths);
    }

    @Test
    void synthesizedFrontmatterCarriesTextNoteAndUrl() throws Exception {
        OpmlFileSystem fs = fsWithLocalFeed("<rss version=\"2.0\"><channel><title>F</title></channel></rss>");

        String topic = new String(fs.openInput("Work/Topic 1.md").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(topic.contains("title: \"Topic 1\""), "title from text attr");
        assertTrue(topic.startsWith("---\ntitle: \"Topic 1\"\n---\nNested note\nsecond line\n"), "body from _note");

        String reference = new String(fs.openInput("Work/Reference.md").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(reference.contains("url: \"https://viznut.fi/unscii/\""), "htmlUrl mapped");
        assertTrue(reference.contains("type: \"link\""), "type attr mapped");

        String slash = new String(fs.openInput("Work/hasslash.md").readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("---\ntitle: \"has/slash\"\n---\n", slash, "separators stripped, not reinterpreted as dirs");
    }

    @Test
    void subscriptionMountsFeedUnderItsOwnPath() throws Exception {
        OpmlFileSystem fs = fsWithLocalFeed("""
                <rss version="2.0"><channel>
                  <title>CoRecursive</title>
                  <item><title>Ep</title><pubDate>Tue, 01 Jul 2025 10:30:00 GMT</pubDate></item>
                </channel></rss>
                """);

        assertTrue(fs.exists("Podcasts.md"), "channel note sits at the subscription node's path");
        assertTrue(fs.exists("Podcasts/2025-07-01 Ep.md"), "item notes nest under the feed");

        var outliner = fs.listFiles(List.of()).stream().filter(e -> e.relativePath().equals("Work.md")).findFirst().orElseThrow();
        var feedItem = fs.listFiles(List.of()).stream().filter(e -> e.relativePath().equals("Podcasts/2025-07-01 Ep.md")).findFirst().orElseThrow();
        assertFalse(outliner.lastModified() == feedItem.lastModified(),
                "outliner mtimes = OPML file mtime, feed mtimes = fetch time");
    }

    @Test
    void missingTextFallsBackToUntitled() throws Exception {
        OpmlFileSystem fs = fsWithLocalFeed("<rss version=\"2.0\"><channel><title>F</title></channel></rss>");
        assertTrue(fs.exists("Untitled.md"), "missing text and title attrs -> Untitled");
    }

    @Test
    void noBodyThrowsAndWritesAreRefused() throws Exception {
        assertThrows(IOException.class,
                () -> new OpmlFileSystem("<opml><head/></opml>".getBytes(StandardCharsets.UTF_8), 0L, FeedFetcher.loader()));

        OpmlFileSystem fs = fsWithLocalFeed("<rss version=\"2.0\"><channel><title>F</title></channel></rss>");
        assertThrows(UnsupportedOperationException.class, () -> fs.writeString("x.md", "y"));
    }

    @Test
    void deadSubscriptionIsSkippedAndOthersMount() throws Exception {
        Path goodFeed = vault.resolve("good.xml");
        Files.writeString(goodFeed, """
                <rss version="2.0"><channel>
                  <title>Good</title>
                  <item><title>Ep</title></item>
                </channel></rss>
                """);

        String opml = """
                <?xml version="1.0"?>
                <opml version="2.0"><body>
                  <outline text="Dead" xmlUrl="/nonexistent/dead-feed.xml"/>
                  <outline text="Good" xmlUrl="%s"/>
                  <outline text="Survivor" _note="outliner branch"/>
                </body></opml>
                """.formatted(goodFeed.toAbsolutePath());

        // One dead feed must not abort the whole vault (large NewsBlur exports)
        OpmlFileSystem fs = new OpmlFileSystem(opml.getBytes(StandardCharsets.UTF_8), 1000L, FeedFetcher.loader());

        assertTrue(fs.exists("Good.md"), "good subscription mounts");
        assertTrue(fs.exists("Good/Ep.md"), "good feed items mount");
        assertFalse(fs.exists("Dead.md"), "dead subscription is skipped");
        assertTrue(fs.exists("Survivor.md"), "outliner branch unaffected");
    }
}
