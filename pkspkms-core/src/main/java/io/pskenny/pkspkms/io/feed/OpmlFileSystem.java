package io.pskenny.pkspkms.io.feed;

import io.pskenny.pkspkms.io.PathUtil;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem.SynthFile;
import io.pskenny.pkspkms.io.feed.FeedFetcher.FeedLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Read-only vault over an OPML file. Outliner outlines (text/_note, htmlUrl)
 * become one note per node; outlines carrying xmlUrl become feed subscriptions
 * — fetched in parallel (4 threads) and mounted (channel note + item notes)
 * under the node's own path. Mixed trees are allowed. A subscription that
 * fails to fetch or parse logs a warning and is skipped — large exports
 * (NewsBlur, AntennaPod) mount even when individual feeds are dead. Outliner
 * notes carry the OPML file's mtime, so unchanged files skip re-parsing.
 */
public final class OpmlFileSystem extends SynthesizedFileSystem {

    private static final Logger logger = LoggerFactory.getLogger(OpmlFileSystem.class);
    private static final int SUBSCRIPTION_POOL = 4;

    public OpmlFileSystem(Path opmlFile, FeedLoader feedLoader) throws IOException {
        this(Files.readAllBytes(opmlFile), fileMtime(opmlFile), feedLoader);
    }

    public OpmlFileSystem(byte[] opmlBytes, long mtime, FeedLoader feedLoader) throws IOException {
        super(materialize(opmlBytes, mtime, feedLoader));
    }

    private static long fileMtime(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toMillis();
    }

    // One subscription: feed URL plus the path it mounts under
    private record Subscription(String xmlUrl, String here) {}

    private static Map<String, SynthFile> materialize(byte[] opmlBytes, long mtime, FeedLoader feedLoader) throws IOException {
        Document document = Xml.parse(opmlBytes);
        Element body = Xml.first(Xml.root(document), "body");
        if (body == null) {
            throw new IOException("OPML has no <body>");
        }

        LinkedHashMap<String, SynthFile> files = new LinkedHashMap<>();
        List<Subscription> subscriptions = new ArrayList<>();
        walk(body, "", mtime, files, feedLoader, subscriptions);
        mountSubscriptions(subscriptions, files, feedLoader);
        return files;
    }

    private static void walk(Element parent, String dir, long mtime, Map<String, SynthFile> files, FeedLoader feedLoader, List<Subscription> subscriptions) throws IOException {
        for (Element outline : Xml.all(parent, "outline")) {
            String text = orDefault(Xml.attr(outline, "text"), orDefault(Xml.attr(outline, "title"), "Untitled"));
            String name = PathUtil.safeFileName(text);
            String notePath = dir.isEmpty() ? name + ".md" : dir + "/" + name + ".md";
            String here = dir.isEmpty() ? name : dir + "/" + name;
            String xmlUrl = Xml.attr(outline, "xmlUrl");

            if (xmlUrl != null && !xmlUrl.isEmpty()) {
                // Subscription nodes carry no own note; the channel note takes their path
                subscriptions.add(new Subscription(xmlUrl, here));
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

            walk(outline, here, mtime, files, feedLoader, subscriptions);
        }
    }

    // Parallel over a small pool: a big export mounts in roughly the slowest
    // feed's time. Dead feeds warn and skip instead of aborting the vault.
    private static void mountSubscriptions(List<Subscription> subscriptions, Map<String, SynthFile> files, FeedLoader feedLoader) throws IOException {
        if (subscriptions.isEmpty()) {
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(SUBSCRIPTION_POOL);
        try {
            List<Future<Map<String, SynthFile>>> futures = new ArrayList<>();
            for (Subscription subscription : subscriptions) {
                futures.add(pool.submit(() -> fetchSubscription(subscription, feedLoader)));
            }

            for (int i = 0; i < futures.size(); i++) {
                Subscription subscription = subscriptions.get(i);
                try {
                    files.putAll(futures.get(i).get());
                } catch (java.util.concurrent.ExecutionException e) {
                    logger.warn("Skipping feed {}: {}", subscription.xmlUrl(), e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while fetching feeds", e);
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private static Map<String, SynthFile> fetchSubscription(Subscription subscription, FeedLoader feedLoader) throws IOException {
        Feed feed = FeedParser.parse(feedLoader.load(subscription.xmlUrl()));
        // The channel note takes the outline node's own path; items nest beneath it
        return FeedNotes.materialize(feed, subscription.here() + ".md", subscription.here(), subscription.xmlUrl(), System.currentTimeMillis());
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
