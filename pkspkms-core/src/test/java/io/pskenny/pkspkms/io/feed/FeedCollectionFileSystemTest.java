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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FeedCollectionFileSystemTest {

    @TempDir
    Path vault;

    private static final String FEED_A = """
            <rss version="2.0"><channel><title>Blog</title>
              <item><title>Post A</title><pubDate>Tue, 01 Jul 2025 10:30:00 GMT</pubDate></item>
            </channel></rss>
            """;

    private static final String FEED_B = """
            <rss version="2.0"><channel><title>News</title>
              <item><title>Post B</title></item>
            </channel></rss>
            """;

    private String writeFeed(String name, String xml) throws IOException {
        Path file = vault.resolve(name);
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file.toAbsolutePath().toString();
    }

    @Test
    void severalSourcesShareOneNamespace() throws Exception {
        String a = writeFeed("a.xml", FEED_A);
        String b = writeFeed("b.xml", FEED_B);

        FeedCollectionFileSystem fs = new FeedCollectionFileSystem(List.of(a, b), FeedFetcher.loader());

        assertTrue(fs.exists("Blog.md"));
        assertTrue(fs.exists("Blog/2025-07-01 Post A.md"));
        assertTrue(fs.exists("News.md"));
        assertTrue(fs.exists("News/Post B.md"));
        assertEquals(4, fs.listFiles(List.of()).size());
    }

    @Test
    void sameTitledFeedsRenameTheirSegment() throws Exception {
        String a = writeFeed("a.xml", FEED_A);
        String a2 = writeFeed("a2.xml", FEED_A.replace("Post A", "Post A2"));

        FeedCollectionFileSystem fs = new FeedCollectionFileSystem(List.of(a, a2), FeedFetcher.loader());

        assertTrue(fs.exists("Blog.md"));
        assertTrue(fs.exists("Blog-2.md"), "second Blog keeps its own channel note");
        assertTrue(fs.exists("Blog/2025-07-01 Post A.md"));
        assertTrue(fs.exists("Blog-2/2025-07-01 Post A2.md"), "renamed source keeps its items together");
    }

    @Test
    void deadSourceIsSkippedAndOthersMount() throws Exception {
        String b = writeFeed("b.xml", FEED_B);

        FeedCollectionFileSystem fs = new FeedCollectionFileSystem(
                List.of("/nonexistent/dead.xml", b), FeedFetcher.loader());

        assertFalse(fs.isEmpty(), "dead source skipped, good source mounted");
        assertTrue(fs.exists("News.md"));
        assertFalse(fs.exists("dead.xml"), "the dead source contributes nothing");
    }

    @Test
    void allSourcesDeadLeavesTheVaultEmpty() throws Exception {
        FeedCollectionFileSystem fs = new FeedCollectionFileSystem(
                List.of("/nonexistent/dead1.xml", "/nonexistent/dead2.xml"), FeedFetcher.loader());

        assertTrue(fs.isEmpty(), "callers must skip registering an empty collection");
        assertEquals(0, fs.listFiles(List.of()).size());
    }

    @Test
    void opmlSourceInlinesItsTree() throws Exception {
        Path opmlFile = vault.resolve("tree.opml");
        Files.writeString(opmlFile, """
                <?xml version="1.0"?>
                <opml version="2.0"><body>
                  <outline text="Work"><outline text="Lisp" _note="functional"/></outline>
                </body></opml>
                """, StandardCharsets.UTF_8);
        String b = writeFeed("b.xml", FEED_B);

        FeedCollectionFileSystem fs = new FeedCollectionFileSystem(List.of(opmlFile.toAbsolutePath().toString(), b), FeedFetcher.loader());

        assertTrue(fs.exists("Work/Lisp.md"), "OPML source materializes inline");
        assertTrue(fs.exists("News/Post B.md"));
    }
}
