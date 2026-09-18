package io.pskenny.pkspkms.io.feed;

/** One item: blog post, podcast episode, or Atom entry. Fields nullable when omitted. */
public record FeedItem(String title, String url, String published, String author, String summary, String enclosureUrl, String guid) {}
