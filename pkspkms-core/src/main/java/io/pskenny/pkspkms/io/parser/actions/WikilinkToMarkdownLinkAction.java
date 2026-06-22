package io.pskenny.pkspkms.io.parser.actions;

import io.pskenny.pkspkms.repo.WikilinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WikilinkToMarkdownLinkAction {
    private static final Logger logger = LoggerFactory.getLogger(WikilinkToMarkdownLinkAction.class);

    // Matches [[...]] ensuring it's not preceded by a tilde (~[[...]])
    private static final Pattern wikiLinkPattern = Pattern.compile("(?<!~)\\[\\[(.*?)\\]\\]");

    public Collection<String> getWikilinks(String content, WikilinkService wikilinkService) {
        Set<String> changedFiles = new HashSet<>();
        Matcher matcher = wikiLinkPattern.matcher(content);

        while (matcher.find()) {
            String wikilinkTarget = matcher.group(1);
            String linkPath;

            // Handle potential link aliases: [[path|text]] -> [text](path)
            if (wikilinkTarget.contains("|")) {
                String[] parts = wikilinkTarget.split("\\|", 2);
                linkPath = parts[0];
            } else {
                linkPath = wikilinkTarget;
            }
            String resolvedLink = wikilinkService.resolve(linkPath);
            if (resolvedLink == null) {
                continue;
            }
            changedFiles.add(resolvedLink);
        }

        return changedFiles;
    }

    public String transformWikilinksToLinks(String content, WikilinkService wikilinkService) {
        StringBuilder newContentBuffer = new StringBuilder();
        Matcher matcher = wikiLinkPattern.matcher(content);

        while (matcher.find()) {
            String wikilinkTarget = matcher.group(1);
            String linkText;
            String linkPath;

            // Handle potential link aliases: [[path|text]] -> [text](path)
            if (wikilinkTarget.contains("|")) {
                String[] parts = wikilinkTarget.split("\\|", 2);
                linkPath = parts[0];
                linkText = parts[1];
            } else {
                linkPath = wikilinkTarget;
                linkText = wikilinkTarget;
            }

            String resolvedLink = wikilinkService.resolve(linkPath);
            if (resolvedLink == null) {
                continue;
            }

            String markdownLink = String.format("[%s](%s)", linkText, resolvedLink);
            matcher.appendReplacement(newContentBuffer, Matcher.quoteReplacement(markdownLink));
        }

        // Append the rest of the string
        matcher.appendTail(newContentBuffer);
        return newContentBuffer.toString();
    }
}