package io.pskenny.pkspkms.io.feed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FeedParserTest {

    private static byte[] utf8(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesRss2() throws Exception {
        Feed feed = FeedParser.parse(utf8("""
        <?xml version="1.0"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0"><channel>
                  <title>CoRecursive Podcast</title>
                  <link>https://corecursive.com/</link>
                  <description><![CDATA[Software dev <b>podcast</b>]]></description>
                  <item>
                    <title>Episode 1</title>
                    <link>https://corecursive.com/ep1</link>
                    <description><![CDATA[Show notes]]></description>
                    <pubDate>Tue, 01 Jul 2025 10:30:00 GMT</pubDate>
                    <guid>ep-1</guid>
                    <enclosure url="https://cdn/ep1.mp3" length="100" type="audio/mpeg"/>
                    <itunes:author>Adam</itunes:author>
                  </item>
                </channel></rss>
                """));

        assertEquals("CoRecursive Podcast", feed.title());
        assertEquals("https://corecursive.com/", feed.url());
        assertEquals(1, feed.items().size());

        FeedItem item = feed.items().get(0);
        assertEquals("Episode 1", item.title());
        assertEquals("https://corecursive.com/ep1", item.url());
        assertEquals("2025-07-01T10:30:00Z", item.published(), "RFC-822 date normalized to ISO");
        assertEquals("https://cdn/ep1.mp3", item.enclosureUrl(), "podcast enclosure mapped");
        assertEquals("ep-1", item.guid());
        assertEquals("Adam", item.author(), "itunes:author is an author");
    }

    @Test
    void parsesPodcastRssNumericOffset() throws Exception {
        // "GMT"-less RFC-822 with numeric offset
        Feed feed = FeedParser.parse(utf8("""
                <rss version="2.0"><channel><title>X</title>
                  <item><title>Ep</title><pubDate>Tue, 07 Jun 2011 17:00:00 +0000</pubDate></item>
                </channel></rss>
                """));
        assertEquals("2011-06-07T17:00:00Z", feed.items().get(0).published());
    }

    @Test
    void parsesAtom() throws Exception {
        Feed feed = FeedParser.parse(utf8("""
                <?xml version="1.0"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>perrotta.dev</title>
                  <subtitle>(I used to like the blog)</subtitle>
                  <link rel="alternate" href="https://perrotta.dev/posts"/>
                  <link rel="self" href="https://perrotta.dev/posts/index.xml"/>
                  <author><name>Thiago Perrotta</name></author>
                  <entry>
                    <title>Hello Atom</title>
                    <link rel="alternate" href="https://perrotta.dev/hello"/>
                    <link rel="enclosure" type="audio/mpeg" href="https://cdn/hello.mp3"/>
                    <published>2025-12-06T09:00:00Z</published>
                    <summary>Post summary</summary>
                  </entry>
                </feed>
                """));

        assertEquals("perrotta.dev", feed.title());
        assertEquals("https://perrotta.dev/posts", feed.url(), "alternate link wins over self");
        assertEquals("(I used to like the blog)", feed.description());

        FeedItem item = feed.items().get(0);
        assertEquals("Hello Atom", item.title());
        assertEquals("https://cdn/hello.mp3", item.enclosureUrl(), "Atom rel=enclosure maps to media");
        assertEquals("2025-12-06T09:00:00Z", item.published());
        assertNull(item.guid(), "Atom uses id, surfaced as guid");
        assertEquals("https://perrotta.dev/hello", item.url());
    }

    @Test
    void atomWithoutAlternateLinkFallsBack() throws Exception {
        Feed feed = FeedParser.parse(utf8("""
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>T</title>
                  <entry><title>E</title><link href="https://x/y"/></entry>
                </feed>
                """));
        assertEquals("https://x/y", feed.items().get(0).url(), "plain link is a fallback url");
    }

    @Test
    void undatedAndBareItemsStayNullable() throws Exception {
        Feed feed = FeedParser.parse(utf8("""
                <rss version="2.0"><channel><title>X</title>
                  <item><title>No date</title></item>
                  <item></item>
                </channel></rss>
                """));
        assertNull(feed.items().get(0).published(), "unparseable date -> null, not garbage");
        assertNull(feed.items().get(1).title());
    }

    @Test
    void malformedXmlThrows() {
        assertThrows(IOException.class, () -> FeedParser.parse(utf8("<rss><channel><title>x")));
    }

    @Test
    void unknownRootElementThrows() {
        assertThrows(IOException.class, () -> FeedParser.parse(utf8("""
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><channel/></rdf:RDF>
                """)));
    }

    @Test
    void parsesPodcastExtensions() throws Exception {
        Feed feed = FeedParser.parse(utf8("""
                <?xml version="1.0"?>
                <rss version="2.0"
                     xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0"
                     xmlns:slash="http://purl.org/rss/1.0/modules/slash/">
                  <channel>
                    <title>Huberman Lab</title>
                    <language>en-us</language>
                    <lastBuildDate>Wed, 02 Jul 2025 08:00:00 GMT</lastBuildDate>
                    <itunes:category text="Health &amp; Fitness"/>
                    <itunes:image href="https://cdn/cover.jpg"/>
                    <item>
                      <title>Sleep episode</title>
                      <link>https://hubermanlab.com/sleep</link>
                      <category>Health</category>
                      <category>Sleep</category>
                      <itunes:duration>1:02:05</itunes:duration>
                      <itunes:episode>12</itunes:episode>
                      <itunes:season>3</itunes:season>
                      <itunes:image href="https://cdn/sleep.jpg"/>
                      <comments>https://corecursive.com/ep1#comments</comments>
                      <slash:comments>42</slash:comments>
                      <enclosure url="https://cdn/sleep.mp3" type="audio/mpeg" length="100"/>
                    </item>
                  </channel>
                </rss>
                """));

        assertEquals("en-us", feed.language());
        assertEquals("2025-07-02T08:00:00Z", feed.modified());
        assertEquals(List.of("Health & Fitness"), feed.tags(), "itunes:category @text");
        assertEquals("https://cdn/cover.jpg", feed.image());

        FeedItem item = feed.items().get(0);
        assertEquals(List.of("Health", "Sleep"), item.tags(), "item categories -> tags");
        assertEquals(3725, item.seconds(), "1:02:05 -> seconds");
        assertEquals(12, item.episode());
        assertEquals(3, item.season());
        assertEquals("https://cdn/sleep.jpg", item.image());
        assertEquals("https://corecursive.com/ep1#comments", item.comments(), "plain <comments> is a URL");
        assertEquals(42, item.commentsCount(), "slash:comments is a count");
        assertEquals("audio/mpeg", item.enclosureType());
    }

    @Test
    void unparseablePodcastNumbersStayNull() throws Exception {
        Feed feed = FeedParser.parse(utf8("""
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0"><channel><title>X</title>
                  <item>
                    <title>Ep</title>
                    <itunes:duration>garbage</itunes:duration>
                    <itunes:episode>ninety</itunes:episode>
                  </item>
                </channel></rss>
                """));
        assertNull(feed.items().get(0).seconds());
        assertNull(feed.items().get(0).episode());
    }

    @Test
    void atomCategoriesAndChannelMeta() throws Exception {
        Feed feed = FeedParser.parse(utf8("""
                <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en">
                  <title>T</title>
                  <updated>2025-12-06T09:00:00Z</updated>
                  <category term="Privacy"/>
                  <entry>
                    <title>E</title>
                    <category term="Security"/>
                  </entry>
                </feed>
                """));
        assertEquals("en", feed.language(), "xml:lang");
        assertEquals("2025-12-06T09:00:00Z", feed.modified(), "updated -> modified");
        assertEquals(List.of("Privacy"), feed.tags());
        assertEquals(List.of("Security"), feed.items().get(0).tags(), "term attr -> tags");
    }

    @Test
    void detectsOpmlRoot() throws Exception {
        assertTrue(FeedParser.isOpml(utf8("<opml version=\"2.0\"><head/><body/></opml>")));
        assertFalse(FeedParser.isOpml(utf8("<rss version=\"2.0\"><channel/></rss>")));
        assertFalse(FeedParser.isOpml(utf8("not xml")));
    }
}
