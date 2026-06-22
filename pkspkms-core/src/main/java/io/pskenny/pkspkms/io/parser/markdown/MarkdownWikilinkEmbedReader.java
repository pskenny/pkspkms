package io.pskenny.pkspkms.io.parser.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownWikilinkEmbedReader {
    private static final Pattern EMBED_PATTERN =
        Pattern.compile("!\\[\\[(.*?)]]");

    public List<String> getEmbeds(String content) {
        List<String> embeds = new ArrayList<>();
        if (content == null) {
            return embeds;
        }
        Matcher matcher = EMBED_PATTERN.matcher(content);
        while (matcher.find()) {
            embeds.add(matcher.group(1));
        }
        return embeds;
    }
}
