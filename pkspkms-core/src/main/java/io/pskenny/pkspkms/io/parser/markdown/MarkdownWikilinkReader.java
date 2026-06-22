package io.pskenny.pkspkms.io.parser.markdown;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownWikilinkReader {
    private static final Pattern WIKILINK_PATTERN =
            Pattern.compile("\\[\\[(.*?)]]");

    public List<String> getWikilinks(String content) {
        Set<String> embeds = new LinkedHashSet<>();
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher matcher = WIKILINK_PATTERN.matcher(content);
        while (matcher.find()) {
            embeds.add(matcher.group(1));
        }
        return new ArrayList<>(embeds);
    }
}
