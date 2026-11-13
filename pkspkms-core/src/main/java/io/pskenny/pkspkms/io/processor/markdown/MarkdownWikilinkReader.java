package io.pskenny.pkspkms.io.processor.markdown;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownWikilinkReader {
    public static final Pattern PATTERN_WIKILINK =
            Pattern.compile("\\[\\[(.*?)]]");

    public List<String> getWikilinks(String content) {
        Set<String> embeds = new LinkedHashSet<>();
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher matcher = PATTERN_WIKILINK.matcher(content);
        while (matcher.find()) {
            // D1: blank inners ([[ ]]) and pure self-anchors ([[#h]]) are
            // not targets in either engine (parity with the flex walk)
            String inner = matcher.group(1).strip();
            if (inner.isEmpty() || inner.startsWith("#")) {
                continue;
            }
            embeds.add(matcher.group(1));
        }
        return new ArrayList<>(embeds);
    }
}
