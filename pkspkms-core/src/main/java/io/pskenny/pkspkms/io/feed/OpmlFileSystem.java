package io.pskenny.pkspkms.io.feed;

import io.pskenny.pkspkms.io.PathUtil;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem.SynthFile;
import io.pskenny.pkspkms.io.feed.FeedFetcher.FeedLoader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only vault over an OPML file. Outliner outlines (text/_note, htmlUrl)
 * become one note per node; outlines carrying xmlUrl become feed subscriptions
 * — the feed is fetched once and mounted (channel note + item notes) under the
 * node's own path. Mixed trees are allowed. Outliner notes carry the OPML
 * file's mtime, so edits re-trigger parsing only when the file changes.
 */
public final class OpmlFileSystem extends SynthesizedFileSystem {

    public OpmlFileSystem(Path opmlFile, FeedLoader feedLoader) throws IOException {
        this(Files.readAllBytes(opmlFile), fileMtime(opmlFile), feedLoader);
    }

    public OpmlFileSystem(byte[] opmlBytes, long mtime, FeedLoader feedLoader) throws IOException {
        super(materialize(opmlBytes, mtime, feedLoader));
    }

    private static long fileMtime(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toMillis();
    }

    private static Map<String, SynthFile> materialize(byte[] opmlBytes, long mtime, FeedLoader feedLoader) throws IOException {
        Document document = Xml.parse(opmlBytes);
        Element body = Xml.first(Xml.root(document), "body");
        if (body == null) {
            throw new IOException("OPML has no <body>");
        }

        LinkedHashMap<String, SynthFile> files = new LinkedHashMap<>();
        walk(body, "", mtime, files, feedLoader);
        return files;
    }

    private static void walk(Element parent, String dir, long mtime, Map<String, SynthFile> files, FeedLoader feedLoader) throws IOException {
        for (Element outline : Xml.all(parent, "outline")) {
            String text = orDefault(Xml.attr(outline, "text"), orDefault(Xml.attr(outline, "title"), "Untitled"));
            String name = PathUtil.safeFileName(text);
            String notePath = dir.isEmpty() ? name + ".md" : dir + "/" + name + ".md";
            String here = dir.isEmpty() ? name : dir + "/" + name;
            String xmlUrl = Xml.attr(outline, "xmlUrl");

            if (xmlUrl != null && !xmlUrl.isEmpty()) {
                mountSubscription(xmlUrl, here, files, feedLoader);
                continue;
            }

            StringBuilder note = new StringBuilder("---\n");
            note.append(FeedNotes.yaml("title", text));
            String htmlUrl = Xml.attr(outline, "htmlUrl");
            if (htmlUrl != null && !htmlUrl.isEmpty()) {
                note.append(FeedNotes.yaml("url", htmlUrl));
            }
            String type = Xml.attr(outline, "type");
            if (type != null && !type.isEmpty()) {
                note.append(FeedNotes.yaml("type", type));
            }
            note.append("---\n");
            String noteBody = Xml.attr(outline, "_note");
            if (noteBody != null && !noteBody.isEmpty()) {
                note.append(noteBody).append("\n");
            }
            files.put(notePath, new SynthFile(note.toString(), mtime));

            walk(outline, here, mtime, files, feedLoader);
        }
    }

    // Subscription: fetch once now; the channel note takes the outline node's
    // own path, item notes nest beneath it
    private static void mountSubscription(String xmlUrl, String here, Map<String, SynthFile> files, FeedLoader feedLoader) throws IOException {
        Feed feed = FeedParser.parse(feedLoader.load(xmlUrl));
        files.putAll(FeedNotes.materialize(feed, here + ".md", here, xmlUrl, System.currentTimeMillis()));
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
