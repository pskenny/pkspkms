package io.pskenny.pkspkms.io.feed;

import io.pskenny.pkspkms.io.fs.PkmsFileSystem;

import java.io.IOException;

/** Mounts a read-only vault from a feed or OPML source (URL or local file). */
public final class SyndicationVaults {

    private SyndicationVaults() {}

    // Local bytes (file or already-fetched): sniff the root element
    public static PkmsFileSystem forBytes(byte[] bytes, long mtime) throws IOException {
        if (FeedParser.isOpml(bytes)) {
            return new OpmlFileSystem(bytes, mtime, FeedFetcher.loader());
        }
        return new FeedFileSystem(bytes, null, mtime);
    }

    // Remote URL: fetch once, then sniff like local content
    public static PkmsFileSystem forUrl(String url) throws IOException {
        byte[] bytes = FeedFetcher.httpGet(url);
        FeedFetcher.enforceLimit(bytes, url);
        long now = System.currentTimeMillis();
        if (FeedParser.isOpml(bytes)) {
            return new OpmlFileSystem(bytes, now, FeedFetcher.loader());
        }
        return new FeedFileSystem(bytes, url, now);
    }
}
