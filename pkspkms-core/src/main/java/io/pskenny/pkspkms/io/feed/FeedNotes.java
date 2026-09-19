package io.pskenny.pkspkms.io.feed;

import io.pskenny.pkspkms.io.PathUtil;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Synthesizes vault files from a parsed feed: one channel note (rss: like
 * existing channel bookmarks) plus one date-prefixed note per item.
 */
final class FeedNotes {

    private FeedNotes() {}

    // channelPath: where the channel note lands (e.g. "Podcasts.md");
    // itemsDir: the folder beneath which item notes nest ("Podcasts")
    static Map<String, SynthesizedFileSystem.SynthFile> materialize(Feed feed, String channelPath, String itemsDir, String feedUrl, long mtime) {
        LinkedHashMap<String, SynthesizedFileSystem.SynthFile> files = new LinkedHashMap<>();

        StringBuilder channel = new StringBuilder("---\n");
        channel.append(yaml("title", feed.title()));
        if (feed.url() != null) {
            channel.append(yaml("url", feed.url()));
        }
        if (feedUrl != null) {
            channel.append(yaml("rss", feedUrl));
        }
        if (feed.author() != null) {
            channel.append(yaml("author", feed.author()));
        }
        if (feed.description() != null) {
            channel.append(yaml("description", feed.description()));
        }
        channel.append("---\n");
        files.put(channelPath, new SynthesizedFileSystem.SynthFile(channel.toString(), mtime));

        String dirPrefix = itemsDir.isEmpty() ? "" : itemsDir + "/";

        Set<String> usedNames = new LinkedHashSet<>();
        for (FeedItem item : feed.items()) {
            StringBuilder note = new StringBuilder("---\n");
            note.append(yaml("title", item.title()));
            if (item.url() != null) {
                note.append(yaml("url", item.url()));
            }
            if (item.published() != null) {
                note.append(yaml("published", item.published()));
            }
            if (item.author() != null) {
                note.append(yaml("author", item.author()));
            }
            if (item.enclosureUrl() != null) {
                note.append(yaml("media", item.enclosureUrl()));
            }
            note.append("---\n");
            if (item.summary() != null) {
                note.append(item.summary()).append("\n");
            }

            files.put(dirPrefix + itemName(item, usedNames),
                    new SynthesizedFileSystem.SynthFile(note.toString(), mtime));
        }
        return files;
    }

    // "2025-07-01 Episode title.md" — date prefix only when known
    private static String itemName(FeedItem item, Set<String> used) {
        String base = PathUtil.safeFileName(item.title());
        String date = datePart(item.published());
        String stem = (date != null ? date + " " : "") + base;

        String name = stem + ".md";
        if (used.add(name)) {
            return name;
        }
        int suffix = 2;
        while (!used.add(stem + "-" + suffix + ".md")) {
            suffix++;
        }
        return stem + "-" + suffix + ".md";
    }

    private static String datePart(String published) {
        try {
            return OffsetDateTime.parse(published).toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        } catch (Exception e) {
            try {
                return Instant.parse(published).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    // Double-quoted YAML scalar so colons, quotes and newlines survive
    // frontmatter parsing; null values are omitted (title-less channels happen)
    static String yaml(String key, String value) {
        if (value == null) {
            return "";
        }
        return key + ": \"" + escape(value) + "\"\n";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
