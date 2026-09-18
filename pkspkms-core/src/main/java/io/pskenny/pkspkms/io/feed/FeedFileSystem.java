package io.pskenny.pkspkms.io.feed;

import io.pskenny.pkspkms.io.PathUtil;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem;
import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem.SynthFile;

import java.io.IOException;
import java.util.Map;
import java.util.Map;

/**
 * Read-only vault over one fetched or local feed: a channel note plus one
 * note per item, all under the feed's title. Built once at server start.
 */
public final class FeedFileSystem extends SynthesizedFileSystem {

    public FeedFileSystem(byte[] feedBytes, String feedUrl, long mtime) throws IOException {
        super(materialize(feedBytes, feedUrl, mtime));
    }

    private static Map<String, SynthFile> materialize(byte[] feedBytes, String feedUrl, long mtime) throws IOException {
        Feed feed = FeedParser.parse(feedBytes);
        String feedDir = PathUtil.safeFileName(feed.title());
        return FeedNotes.materialize(feed, feedDir + ".md", feedDir, feedUrl, mtime);
    }
}
