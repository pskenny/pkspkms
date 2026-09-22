package io.pskenny.pkspkms.io.feed;

import org.w3c.dom.Element;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses RSS 2.0 (including podcast extensions: enclosure, itunes:author,
 * itunes:duration, itunes:episode/season) and Atom 1.0 into the normalized
 * Feed model. Feed source selection is by root element, not extension.
 */
public final class FeedParser {

    // slash:comments rides the slash namespace; plain <comments> is a URL
    private static final String SLASH_NS = "http://purl.org/rss/1.0/modules/slash/";

    private static final List<DateTimeFormatter> RSS_DATE_FORMATS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH));

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

    // --- RSS 2.0 (podcast RSS is RSS 2.0 + itunes/enclosure/slash) ---

    private static Feed parseRss(Element root) throws IOException {
        Element channel = Xml.first(root, "channel");
        if (channel == null) {
            throw new IOException("RSS feed has no <channel>");
        }

        List<FeedItem> items = new ArrayList<>();
        for (Element item : Xml.all(channel, "item")) {
            items.add(new FeedItem(
                    Xml.text(item, "title"),
                    Xml.text(item, "link"),
                    normalizeDate(Xml.text(item, "pubDate")),
                    firstAuthor(item),
                    Xml.text(item, "description"),
                    enclosureUrl(item),
                    Xml.text(item, "guid"),
                    itemTags(item),
                    durationSeconds(Xml.text(item, "duration")),
                    intOrNull(Xml.text(item, "episode")),
                    intOrNull(Xml.text(item, "season")),
                    itemImage(item),
                    commentsUrl(item),
                    commentsCount(item),
                    enclosureType(item)));
        }

        String buildDate = orDefault(Xml.text(channel, "lastBuildDate"), Xml.text(channel, "pubDate"));
        return new Feed(
                Xml.text(channel, "title"),
                Xml.text(channel, "link"),
                Xml.text(channel, "description"),
                firstAuthor(channel),
                null,
                items,
                Xml.text(channel, "language"),
                normalizeDate(buildDate),
                channelTags(channel),
                Xml.text(channel, "websiteUrl"),
                channelImage(channel));
    }

    // dc:creator and itunes:author both surface as local name "creator"/"author"
    private static String firstAuthor(Element element) {
        String creator = Xml.text(element, "creator");
        return creator != null ? creator : Xml.text(element, "author");
    }

    private static String enclosureUrl(Element item) {
        Element enclosure = Xml.first(item, "enclosure");
        return enclosure == null ? null : Xml.attr(enclosure, "url");
    }

    private static String enclosureType(Element item) {
        Element enclosure = Xml.first(item, "enclosure");
        return enclosure == null ? null : Xml.attr(enclosure, "type");
    }

    // Plain <category> and dc:subject carry element text; empty values dropped
    private static List<String> itemTags(Element item) {
        Set<String> tags = new LinkedHashSet<>();
        for (Element child : Xml.children(item)) {
            String localName = child.getLocalName();
            if ("category".equals(localName) || "subject".equals(localName)) {
                String value = child.getTextContent().strip();
                if (!value.isEmpty()) {
                    tags.add(value);
                }
            }
        }
        return tags.isEmpty() ? null : List.copyOf(tags);
    }

    // "1:02:05" -> 3725; "90" -> 90; garbage -> null
    private static Integer durationSeconds(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            int seconds = 0;
            for (String part : raw.split(":")) {
                seconds = seconds * 60 + Integer.parseInt(part.strip());
            }
            return seconds;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String itemImage(Element item) {
        Element itunesImage = Xml.first(item, "image");
        if (itunesImage != null && itunesImage.hasAttribute("href")) {
            return Xml.attr(itunesImage, "href");
        }
        Element thumbnail = Xml.first(item, "thumbnail");
        return thumbnail == null ? null : Xml.attr(thumbnail, "url");
    }

    // Plain <comments> is a URL; slash:comments is a count
    private static String commentsUrl(Element item) {
        Element comments = Xml.firstByNamespace(item, "comments", null);
        return comments == null ? null : Xml.textOf(comments);
    }

    private static Integer commentsCount(Element item) {
        Element comments = Xml.firstByNamespace(item, "comments", SLASH_NS);
        return comments == null ? null : intOrNull(Xml.textOf(comments));
    }

    // itunes:category carries @text; plain channel <category> carries text
    private static List<String> channelTags(Element channel) {
        Set<String> tags = new LinkedHashSet<>();
        for (Element child : Xml.children(channel)) {
            if ("category".equals(child.getLocalName())) {
                String text = child.getAttribute("text");
                String value = text.isEmpty() ? child.getTextContent().strip() : text;
                if (!value.isEmpty()) {
                    tags.add(value);
                }
            }
        }
        return tags.isEmpty() ? null : List.copyOf(tags);
    }

    // itunes:image is an empty element with @href; plain RSS <image> nests <url>
    private static String channelImage(Element channel) {
        for (Element child : Xml.children(channel)) {
            if ("image".equals(child.getLocalName())) {
                if (child.hasAttribute("href")) {
                    return child.getAttribute("href");
                }
                Element url = Xml.first(child, "url");
                if (url != null) {
                    return Xml.textOf(url);
                }
            }
        }
        return null;
    }

    // --- Atom 1.0 ---

    private static Feed parseAtom(Element root) throws IOException {
        List<FeedItem> items = new ArrayList<>();
        for (Element entry : Xml.all(root, "entry")) {
            String published = orDefault(Xml.text(entry, "published"), Xml.text(entry, "updated"));
            Element enclosure = enclosureLink(entry);
            items.add(new FeedItem(
                    Xml.text(entry, "title"),
                    alternateLink(entry),
                    normalizeDate(published),
                    Xml.text(entry, "author"),
                    orDefault(Xml.text(entry, "summary"), Xml.text(entry, "content")),
                    enclosure == null ? null : Xml.attr(enclosure, "href"),
                    Xml.text(entry, "id"),
                    atomTags(entry),
                    null, null, null,
                    null,
                    null, null,
                    enclosure == null ? null : Xml.attr(enclosure, "type")));
        }

        return new Feed(
                Xml.text(root, "title"),
                alternateLink(root),
                orDefault(Xml.text(root, "subtitle"), Xml.text(root, "content")),
                Xml.text(root, "author"),
                null,
                items,
                root.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"),
                normalizeDate(Xml.text(root, "updated")),
                atomTags(root),
                Xml.text(root, "websiteUrl"),
                null);
    }

    private static String alternateLink(Element parent) {
        List<Element> links = Xml.all(parent, "link");
        String fallback = null;
        for (Element link : links) {
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

    private static Element enclosureLink(Element parent) {
        for (Element link : Xml.all(parent, "link")) {
            if ("enclosure".equals(Xml.attr(link, "rel"))) {
                return link;
            }
        }
        return null;
    }

    // Atom categories carry @term
    private static List<String> atomTags(Element parent) {
        Set<String> tags = new LinkedHashSet<>();
        for (Element child : Xml.children(parent)) {
            if ("category".equals(child.getLocalName())) {
                String term = child.getAttribute("term");
                String value = term.isEmpty() ? child.getTextContent().strip() : term;
                if (!value.isEmpty()) {
                    tags.add(value);
                }
            }
        }
        return tags.isEmpty() ? null : List.copyOf(tags);
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
