package io.pskenny.pkspkms.io.feed;

import java.util.List;

/**
 * One item: blog post, podcast episode, or Atom entry. Fields are nullable
 * (or empty-list) when the source omits them; seconds/episode/season/
 * commentsCount bind as numbers for typed range comparisons.
 */
public record FeedItem(String title, String url, String published, String author, String summary,
                       String enclosureUrl, String guid,
                       List<String> tags, Integer seconds, Integer episode, Integer season,
                       String image, String comments, Integer commentsCount, String enclosureType) {}
