package io.pskenny.pkspkms.io.feed;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses RSS 2.0 (including podcast extensions: enclosure, itunes:author) and
 * Atom 1.0 into the normalized Feed model. Feed source selection is by root
 * element, not extension.
 */
public final class FeedParser {

    private static final List<DateTimeFormatter> RSS_DATE_FORMATS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", java.util.Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH));

    private FeedParser() {}

    public static boolean isOpml(byte[] bytes) {
        try {
            return "opml".equals(Xml.root(Xml.parse(bytes)).getLocalName());
        } catch (IOException e) {
            return false;
        }
    }

    public static Feed parse(byte[] bytes) throws IOException {
        Element root = Xml.root(Xml.parse(bytes));
        String localName = root.getLocalName();
        if ("rss".equals(localName)) {
            return parseRss(root);
        }
        if ("feed".equals(localName)) {
            return parseAtom(root);
        }
        throw new IOException("Unsupported feed root element: " + localName);
    }

    // --- RSS 2.0 (podcast RSS is RSS 2.0 + itunes/enclosure) ---

    private static Feed parseRss(Element root) throws IOException {
        org.w3c.dom.Element channel = Xml.first(root, "channel");
        if (channel == null) {
            throw new IOException("RSS feed has no <channel>");
        }

        List<FeedItem> items = new ArrayList<>();
        for (org.w3c.dom.Element item : Xml.all(channel, "item")) {
            items.add(new FeedItem(
                    Xml.text(item, "title"),
                    Xml.text(item, "link"),
                    normalizeDate(orDefault(Xml.text(item, "pubDate"), null)),
                    Xml.text(item, "author") != null ? Xml.text(item, "author") : firstAuthor(item),
                    Xml.text(item, "description"),
                    enclosureUrl(item),
                    Xml.text(item, "guid")));
        }

        return new Feed(
                Xml.text(channel, "title"),
                Xml.text(channel, "link"),
                Xml.text(channel, "description"),
                firstAuthor(channel),
                null,
                items);
    }

    // dc:creator and itunes:author both surface as local name "creator"/"author"
    private static String firstAuthor(org.w3c.dom.Element element) {
        String creator = Xml.text(element, "creator");
        return creator != null ? creator : Xml.text(element, "author");
    }

    private static String enclosureUrl(org.w3c.dom.Element item) {
        org.w3c.dom.Element enclosure = Xml.first(item, "enclosure");
        return enclosure == null ? null : Xml.attr(enclosure, "url");
    }

    // --- Atom 1.0 ---

    private static Feed parseAtom(org.w3c.dom.Element root) throws IOException {
        List<FeedItem> items = new ArrayList<>();
        for (org.w3c.dom.Element entry : Xml.all(root, "entry")) {
            String published = orDefault(Xml.text(entry, "published"), Xml.text(entry, "updated"));
            items.add(new FeedItem(
                    Xml.text(entry, "title"),
                    alternateLink(entry),
                    normalizeDate(published),
                    Xml.text(entry, "author"),
                    orDefault(Xml.text(entry, "summary"), Xml.text(entry, "content")),
                    enclosureLink(entry),
                    Xml.text(entry, "id")));
        }

        return new Feed(
                Xml.text(root, "title"),
                alternateLink(root),
                orDefault(Xml.text(root, "subtitle"), Xml.text(root, "content")),
                Xml.text(root, "author"),
                null,
                items);
    }

    private static String alternateLink(org.w3c.dom.Element parent) {
        List<org.w3c.dom.Element> links = Xml.all(parent, "link");
        String fallback = null;
        for (org.w3c.dom.Element link : links) {
            String rel = Xml.attr(link, "rel");
            if (rel == null || "alternate".equals(rel)) {
                return Xml.attr(link, "href");
            }
            if (fallback == null) {
                fallback = Xml.attr(link, "href");
            }
        }
        return fallback;
    }

    private static String enclosureLink(org.w3c.dom.Element parent) {
        for (org.w3c.dom.Element link : Xml.all(parent, "link")) {
            if ("enclosure".equals(Xml.attr(link, "rel"))) {
                return Xml.attr(link, "href");
            }
        }
        return null;
    }

    // --- dates ---

    // RFC-822 zoo handled best-effort; Atom's ISO handles natively
    static String normalizeDate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            // fall through to RSS formatters
        }
        for (DateTimeFormatter format : RSS_DATE_FORMATS) {
            try {
                return ZonedDateTime.parse(raw, format).toInstant().toString();
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
