package io.pskenny.pkspkms.io.processor.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Embed/link span distinction (BUGS.md H2): {@code ![[x]]} must parse as a
 * WikiImage (flex {@code IMAGE_LINKS:true}) — a wikilinkSpan that carries the
 * embed marker, offset at the {@code !}, with the inner text identical to a
 * plain {@code [[x]]} link. Fenced-view {@code embedSpans} stay limited to
 * base/luabase fences.
 */
public class MarkdownEmbedSpanTest {

    private final FlexmarkMarkdownExtractor flex = new FlexmarkMarkdownExtractor();

    @SuppressWarnings("unchecked")
    private static java.util.List<java.util.Map<String, Object>> spansOf(MarkdownExtractor.Extraction out) {
        return out.spans();
    }

    @Test
    void embedRecordsAsMarkedWikilinkSpan() {
        var out = flex.extract("![[img.png]]\n[[plain.md]]\n");

        var spans = spansOf(out);
        assertEquals(2, spans.size(), spans.toString());

        Map<String, Object> embed = findSpan(spans, "img.png");
        assertEquals("wikilink", embed.get("kind"), "kind stays wikilink (links contract)");
        assertEquals(Boolean.TRUE, embed.get("embed"), "the embed marker distinguishes ![[ from [[");
        assertFalse(embed.get("offset").equals(spans.get(spans.indexOf(findSpan(spans, "plain.md"))).get("offset")),
                "image offset points at the !, link offset at the [");
        // inner text parity: the raw inner text of ![[img.png]] == img.png
        assertTrue(out.wikilinks().contains("img.png"), out.wikilinks().toString());
        assertTrue(out.wikilinks().contains("plain.md"), out.wikilinks().toString());

        // fence-view embedSpans stay limited to base/luabase
        assertTrue(out.embedSpans().isEmpty(), out.embedSpans().toString());
    }

    @Test
    void embedOffsetPointsAtExclamation() {
        var out = flex.extract("\n\n![[img.png]]");
        var spans = spansOf(out);
        assertEquals(1, spans.size(), spans.toString());
        assertEquals(2, Integer.valueOf(String.valueOf(spans.get(0).get("offset"))),
                "offset indexes the ! of ![[ (content line 3, col 1 → offset 2)");
        assertEquals(3, Integer.valueOf(String.valueOf(spans.get(0).get("line"))));
    }

    @Test
    void anchorAndDisplayStillSplitForEmbeds() {
        var out = flex.extract("![[notes/Book.md#Chapter|page 12]]");
        var spans = spansOf(out);
        assertEquals(1, spans.size(), spans.toString());
        Map<String, Object> span = spans.get(0);
        assertEquals("notes/Book.md", span.get("target"), span.toString());
        assertEquals("Chapter", span.get("anchor"), span.toString());
        assertEquals("page 12", span.get("display"), span.toString());
        assertEquals(Boolean.TRUE, span.get("embed"), span.toString());
    }

    private static Map<String, Object> findSpan(List<Map<String, Object>> spans, String target) {
        return spans.stream()
                .filter(s -> String.valueOf(s.get("target")).equals(target))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span for " + target + " in " + spans));
    }
}
