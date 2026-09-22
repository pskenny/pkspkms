package io.pskenny.pkspkms.io.feed;

import java.util.List;

/** Normalized channel metadata. Fields are nullable (or empty-list) when the source omits them. */
public record Feed(String title, String url, String description, String author, String feedUrl, List<FeedItem> items,
                   String language, String modified, List<String> tags, String website, String image) {}
