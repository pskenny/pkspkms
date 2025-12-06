package io.github.pskenny.io.parser.markdown;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownLinkReader {
    private final Pattern markdownLinkPattern = Pattern.compile("\\[(.*?)\\]\\((.*?)\\)");

    private List<String> extractLinks(String content) {
        Set<String> uniqueUrls = new HashSet<>();
        List<String> markdownLinks = new ArrayList<>();

        Matcher matcher = markdownLinkPattern.matcher(content);
        while (matcher.find()) {
            String url = matcher.group(2);
            // don't count urls for the web
            if (url.toLowerCase().startsWith("http://") || url.toLowerCase().startsWith("https://")) {
                continue;
            }

            if (uniqueUrls.add(url)) {
                markdownLinks.add(url);
            }
        }

        return markdownLinks;
    }

    public List<String> getMarkdownLinksProperties(final String content) {
        return extractLinks(content);
    }
}