package io.pskenny.pkspkms.io.processor.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownLinkReaderTest {

    private final MarkdownLinkReader reader = new MarkdownLinkReader();

    @Test
    void plainLinksSurvive() {
        List<String> links = reader.getMarkdownLinksProperties("[x](note.md)");
        assertEquals(List.of("note.md"), links);
    }

    @Test
    void angleBracketDestinationWithSpaces() {
        List<String> links = reader.getMarkdownLinksProperties("[x](<My Image.png>)");
        assertEquals(List.of("My Image.png"), links);
    }

    @Test
    void angleBracketDestinationWithParens() {
        List<String> links = reader.getMarkdownLinksProperties("[x](<Meeting (2024).md>)");
        assertEquals(List.of("Meeting (2024).md"), links);
    }

    @Test
    void unbalancedAngleSkipped() {
        List<String> links = reader.getMarkdownLinksProperties("[x](<note.md");
        assertTrue(links.isEmpty(), List.of("no dest extracted").toString());
    }

    @Test
    void htmlMarkerInsideDestSkipped() {
        // malformed: unbalanced `<` lands in the plain path's raw capture
        List<String> links = reader.getMarkdownLinksProperties("[x](<a href)");
        assertTrue(links.isEmpty());
    }

    @Test
    void balancedHtmlWordAngleIsValidLink() {
        // markdown-valid: [x](<a href>) names the destination "a href"
        List<String> links = reader.getMarkdownLinksProperties("[x](<a href>)");
        assertEquals(List.of("a href"), links);
    }

    @Test
    void httpStillSkippedEvenInAngle() {
        List<String> links = reader.getMarkdownLinksProperties("[x](<https://a.b>) [y](https://c.d)");
        assertTrue(links.isEmpty());
    }

    @Test
    void duplicateDestinationsDeduped() {
        List<String> links = reader.getMarkdownLinksProperties("[a](x.md)\n[b](x.md)");
        assertEquals(1, links.size());
    }
}
