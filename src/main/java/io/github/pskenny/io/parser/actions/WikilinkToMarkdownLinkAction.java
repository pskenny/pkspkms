package io.github.pskenny.io.parser.actions;

import io.github.pskenny.io.PksFile;
import io.github.pskenny.repo.WikilinkResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WikilinkToMarkdownLinkAction {
    private static final Logger logger = LoggerFactory.getLogger(WikilinkToMarkdownLinkAction.class);

    // Matches [[...]] ensuring it's not preceded by a tilde (~[[...]])
    private static final Pattern wikiLinkPattern = Pattern.compile("(?<!~)\\[\\[(.*?)\\]\\]");

    public Set<String> act(PksFile pksFile, WikilinkResolver wikilinkResolver) {
        if (!pksFile.getFilePath().endsWith(".md")) {
            return Set.of();
        }

        Set<String> changedFiles = new HashSet<>();
        String content;
        if (pksFile.getProperties().containsKey("content")) {
           content = pksFile.getProperties().get("content").toString();
        } else {
            try {
                content = Files.readString(pksFile.getFile().toPath(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                logger.error("Error reading file " + pksFile.getFile().getAbsolutePath() + ": " + ex.getMessage());
                return Set.of();
            }
        }

        StringBuffer newContentBuffer = new StringBuffer();
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

            String resolvedLink = wikilinkResolver.resolveWikilink(linkPath);
            if (resolvedLink == null) {
                continue;
            }

            String markdownLink = String.format("[%s](%s)", linkText, resolvedLink);
            matcher.appendReplacement(newContentBuffer, Matcher.quoteReplacement(markdownLink));

            changedFiles.add(resolvedLink);
        }

        // Append the rest of the string
        matcher.appendTail(newContentBuffer);

        if (!changedFiles.isEmpty()) {
            String newContent = newContentBuffer.toString();
            pksFile.getProperties().put("content", newContent);
        }

        return changedFiles;
    }
}