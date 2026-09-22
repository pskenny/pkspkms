package io.pskenny.pkspkms.io.feed;

import io.pskenny.pkspkms.io.PathUtil;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        channel.append(yaml("url", feed.url()));
        channel.append(yaml("rss", feedUrl));
        channel.append(yaml("author", feed.author()));
        channel.append(yaml("description", feed.description()));
        channel.append(yaml("language", feed.language()));
        channel.append(yaml("modified", feed.modified()));
        channel.append(yamlList("tags", feed.tags()));
        channel.append(yaml("website", feed.website()));
        channel.append(yaml("image", feed.image()));
        channel.append("---\n");
        files.put(channelPath, new SynthesizedFileSystem.SynthFile(channel.toString(), mtime));

        String dirPrefix = itemsDir.isEmpty() ? "" : itemsDir + "/";

        Set<String> usedNames = new LinkedHashSet<>();
        for (FeedItem item : feed.items()) {
            StringBuilder note = new StringBuilder("---\n");
            note.append(yaml("title", item.title()));
            note.append(yaml("url", item.url()));
            note.append(yaml("published", item.published()));
            note.append(yaml("author", item.author()));
            note.append(yamlList("tags", item.tags()));
            note.append(yamlNumber("seconds", item.seconds()));
            note.append(yamlNumber("episode", item.episode()));
            note.append(yamlNumber("season", item.season()));
            note.append(yaml("image", item.image()));
            note.append(yaml("comments", item.comments()));
            note.append(yamlNumber("commentsCount", item.commentsCount()));
            note.append(yaml("guid", item.guid()));
            note.append(yaml("media", item.enclosureUrl()));
            note.append("---\n");

            List<String> embeds = new ArrayList<>();
            if (playableMedia(item.enclosureUrl(), item.enclosureType())) {
                embeds.add(item.enclosureUrl());
            }
            String youtube = youtubeEmbed(item.url());
            if (youtube != null) {
                embeds.add(youtube);
            }
            for (String embed : embeds) {
                note.append("![](").append(embed).append(")\n");
            }
            if (!embeds.isEmpty()) {
                note.append("\n");
            }
            if (item.summary() != null) {
                note.append(item.summary()).append("\n");
            }

            files.put(dirPrefix + itemName(item, usedNames),
                    new SynthesizedFileSystem.SynthFile(note.toString(), mtime));
        }
        return files;
    }

    // Audio/video enclosures embed in the note body: Obsidian renders external
    // ![]() media; nothing is downloaded
    private static final List<String> AUDIO_EXTENSIONS =
            List.of(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac");
    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi");

    private static boolean playableMedia(String enclosureUrl, String enclosureType) {
        if (enclosureUrl == null) {
            return false;
        }
        if (enclosureType != null) {
            String type = PathUtil.lower(enclosureType);
            return type.startsWith("audio/") || type.startsWith("video/");
        }
        String path = PathUtil.lower(enclosureUrl);
        int query = path.indexOf('?');
        if (query != -1) {
            path = path.substring(0, query);
        }
        return AUDIO_EXTENSIONS.stream().anyMatch(path::endsWith)
                || VIDEO_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    // YouTube items embed a player: watch/shorts/youtu.be links normalize to
    // the canonical watch URL; the url: property keeps the original link
    private static final List<String> YOUTUBE_HOSTS = List.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
            "www.youtube-nocookie.com", "youtu.be");
    private static final java.util.regex.Pattern YOUTUBE_ID =
            java.util.regex.Pattern.compile("(?:watch\\?v=|youtu\\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{6,})");

    private static String youtubeEmbed(String url) {
        if (url == null || !YOUTUBE_HOSTS.contains(hostOf(url))) {
            return null;
        }
        java.util.regex.Matcher matcher = YOUTUBE_ID.matcher(url);
        return matcher.find() ? "https://www.youtube.com/watch?v=" + matcher.group(1) : null;
    }

    // Host without lowercasing the whole URL: YouTube IDs are case-sensitive
    private static String hostOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme == -1) {
            return "";
        }
        String rest = url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return slash == -1 ? rest : rest.substring(0, slash);
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

    static String yamlList(String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder list = new StringBuilder(key).append(":\n");
        for (String value : values) {
            list.append("  - \"").append(escape(value)).append("\"\n");
        }
        return list.toString();
    }

    static String yamlNumber(String key, Integer value) {
        if (value == null) {
            return "";
        }
        return key + ": " + value + "\n";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
