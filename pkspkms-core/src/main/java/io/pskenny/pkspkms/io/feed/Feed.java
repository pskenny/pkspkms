package io.pskenny.pkspkms.io.feed;

import java.util.List;

/** Normalized channel metadata. Fields are nullable when the source omits them. */
public record Feed(String title, String url, String description, String author, String feedUrl, List<FeedItem> items) {}
