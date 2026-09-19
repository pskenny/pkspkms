package io.pskenny.pkspkms.io.feed;

import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem.SynthFile;
import io.pskenny.pkspkms.io.feed.FeedFetcher.FeedLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One alias, several sources. Each source fetches independently (parallel
 * pool via {@link Mounts}) and contributes its materialized tree under
 * {@code FeedTitle.md} / {@code FeedTitle/…}; a source that fails to fetch
 * or parse warns and is skipped — the alias mounts with whatever succeeded,
 * and an alias whose sources all fail stays empty (check via {@link #isEmpty()}
 * before registering it). Sources are content-sniffed, so feed URLs, local
 * feed files and OPML files mix under one namespace; same-titled feeds rename
 * their first path segment (Blog → Blog-2) to avoid overwriting each other.
 */
public final class FeedCollectionFileSystem extends SynthesizedFileSystem {

    public FeedCollectionFileSystem(List<String> sources, FeedLoader feedLoader) throws IOException {
        super(merge(sources, feedLoader));
    }

    private static Map<String, SynthFile> merge(List<String> sources, FeedLoader feedLoader) throws IOException {
        List<Map<String, SynthFile>> mounted = Mounts.fetch(sources, s -> s, source -> materializeSource(source, feedLoader));

        LinkedHashMap<String, SynthFile> files = new LinkedHashMap<>();
        for (Map<String, SynthFile> sourceFiles : mounted) {
            if (sourceFiles != null) {
                merge(files, sourceFiles);
            }
        }
        return files;
    }

    // Fetch or read, then sniff: RSS/Atom materialize as a feed, OPML as its tree
    private static Map<String, SynthFile> materializeSource(String source, FeedLoader feedLoader) throws IOException {
        boolean remote = source.startsWith("http://") || source.startsWith("https://");
        byte[] bytes = remote ? FeedFetcher.httpGet(source) : Files.readAllBytes(Paths.get(source));
        long mtime = remote ? System.currentTimeMillis() : Files.getLastModifiedTime(Paths.get(source)).toMillis();

        if (FeedParser.isOpml(bytes)) {
            return OpmlFileSystem.materialize(bytes, mtime, feedLoader);
        }
        return FeedFileSystem.materialize(bytes, remote ? source : null, mtime);
    }

    // Feed sources share a first path segment (channel "Blog.md", items "Blog/…");
    // rename the whole source when its segment is taken (same-titled feeds)
    private static void merge(Map<String, SynthFile> files, Map<String, SynthFile> sourceFiles) {
        String segment = firstSegmentOf(sourceFiles);
        String unique = uniqueSegment(files, segment);

        for (Map.Entry<String, SynthFile> entry : sourceFiles.entrySet()) {
            files.put(namespaced(entry.getKey(), segment, unique), entry.getValue());
        }
    }

    // Feed sources: first key is the channel note ("Blog.md"); the feed's
    // segment is its stem, without the extension
    private static String firstSegmentOf(Map<String, SynthFile> sourceFiles) {
        String segment = sourceFiles.keySet().iterator().next().split("/", 2)[0];
        if (segment.endsWith(".md")) {
            return segment.substring(0, segment.length() - ".md".length());
        }
        return segment;
    }

    private static String uniqueSegment(Map<String, SynthFile> files, String segment) {
        if (segmentFree(files, segment)) {
            return segment;
        }
        int suffix = 2;
        while (!segmentFree(files, segment + "-" + suffix)) {
            suffix++;
        }
        return segment + "-" + suffix;
    }

    private static boolean segmentFree(Map<String, SynthFile> files, String segment) {
        if (files.containsKey(segment + ".md")) {
            return false;
        }
        for (String path : files.keySet()) {
            if (path.startsWith(segment + "/")) {
                return false;
            }
        }
        return true;
    }

    // Unique segment: keys under the feed's own segment stay; anything else
    // (multi-root OPML trees colliding across sources) namespaces under it
    private static String namespaced(String path, String segment, String unique) {
        if (unique.equals(segment)) {
            return path;
        }
        if (path.equals(segment + ".md")) {
            return unique + ".md";
        }
        if (path.startsWith(segment + "/")) {
            return unique + "/" + path.substring(segment.length() + 1);
        }
        return unique + "/" + path;
    }
}
