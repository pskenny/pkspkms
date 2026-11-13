package io.pskenny.pkspkms.repo.sqlite;

import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Span-coordinate basis pin (BUGS.md — span-basis contract, Jade-Plant-class).
 *
 * The contract the MCP.md doc states: every span/instance fact is a
 * FULL-CONTENT CHAR offset (UTF-16 units on the BOM-stripped text — not UTF-8
 * bytes) anchored at the markdown MARKER ({@code [} for links, {@code !} for
 * images/embeds), with {@code line} = raw-article line (frontmatter counted).
 * The one exception documented: taskSpans.offset = task TEXT start.
 *
 * This test pins the basis end-to-end over content engineered to make every
 * char/byte/marker confusion a failure: °-glyphs in the frontmatter (byte
 * count ≠ char count), one of each shape, and raw-geometry assertions
 * computed from the same seeded string (no hardcoded offsets).
 */
public class SpanBasisProbeTest {

    @TempDir
    Path vault;

    private static final String PROBE_NOTE = """
            ---
            title: Each ° glyph costs one char but two UTF-8 bytes °°°°
            tags: [basis]
            ---
            # Basis probe

            Dangling [[Wiki Dangler]] target rides file B.
            Anchor rot: [[Existing Note#No Such Heading]] resolves the file, not the heading.

            ## Links

            - [a md url link](dead-destination.md)
            - ![](probe%20spaces.jpg)
            - ![[wiki image embed]]


            """;

    private static final String EXISTING_NOTE = """
            ---
            title: Existing Note
            ---
            # Existing Note

            Some heading that is not "No Such Heading".
            """;

    private SQLitePksFileRepository repo;
    private String probeContent;
    private String basePath;

    @BeforeEach
    void seed() throws Exception {
        probeContent = PROBE_NOTE;
        basePath = "probe.md";
        Files.writeString(vault.resolve(basePath), probeContent);
        Files.writeString(vault.resolve("Existing Note.md"), EXISTING_NOTE);

        repo = new SQLitePksFileRepository(
                "jdbc:sqlite:" + vault.resolve("index.db"), null,
                new JavaFileSystem(vault.toFile()), false);
        repo.loadDirectoryIntoRepository();
    }

    @Test
    void mdLinkFactsAreMarkerAnchoredFullContentChars() throws Exception {
        Map<String, Object> props = propertiesFor(basePath);
        var linkSpans = (List<?>) props.getOrDefault("linkSpans", List.of());
        assertEquals(2, linkSpans.size(), "md link + md image: " + linkSpans);

        for (Object o : linkSpans) {
            var span = (Map<?, ?>) o;
            int offset = Integer.parseInt(String.valueOf(span.get("offset")));
            int line = Integer.parseInt(String.valueOf(span.get("line")));
            String target = String.valueOf(span.get("target"));
            String kind = String.valueOf(span.get("kind"));
            int marker = "image".equals(kind)
                    ? probeContent.lastIndexOf('!', probeContent.indexOf(target))
                    : probeContent.lastIndexOf('[', probeContent.indexOf(target));
            assertEquals(marker, offset, "marker-anchored char offset for " + target);
            assertEquals(1 + lineOf(probeContent, marker), line, "raw line for " + target);
        }
    }

    private static int lineOf(String content, int index) {
        return (int) content.chars().limit(Math.max(0, index)).filter(c -> c == '\n').count();
    }

    @Test
    void wikiFactsAreMarkerAnchoredFullContentChars() throws Exception {
        Map<String, Object> props = propertiesFor(basePath);
        var spans = (List<?>) props.getOrDefault("wikilinkSpans", List.of());
        assertEquals(3, spans.size(), "dangler + anchor-rot + wiki embed: " + spans);

        long embedCount = spans.stream().filter(s -> String.valueOf(((Map<?, ?>) s).get("embed")).equals("true")).count();
        // the embed marker offset: backwards search from the embed text — the
        // fact anchors at the `!` of `![[wiki image embed]]`
        int embedOffset = Integer.parseInt(String.valueOf(
                ((Map<?, ?>) spans.stream().filter(s -> String.valueOf(((Map<?, ?>) s).get("embed")).equals("true")).findFirst().orElseThrow())
                        .get("offset")));
        assertEquals(probeContent.lastIndexOf('!', probeContent.indexOf("wiki image embed")), embedOffset,
                "embed fact anchors at the ! (H2 family)");
        assertEquals(1, embedCount, "the ![[ embed recorded (H2 family)");
    }

    @Test
    void brokenLinksInstancesCarryMarkerCoordinates() throws Exception {
        Map<String, Object> report = repo.brokenLinks("refcount", 100, 0, "");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) report.getOrDefault("targets", List.of());
        Map<String, Object> dangler = targets.stream()
                .filter(t -> String.valueOf(t.get("target")).contains("Wiki Dangler"))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> instances = (List<Map<String, Object>>) dangler.get("instances");
        Map<String, Object> instance = instances.get(0);
        int offset = Integer.parseInt(String.valueOf(instance.get("offset")));
        assertEquals(probeContent.indexOf("[[Wiki Dangler"), offset,
                "instance offset = the marker char, full-content, char-based");
        assertEquals(1 + lineOf(probeContent, probeContent.indexOf("[[Wiki Dangler")),
                Integer.parseInt(String.valueOf(instance.get("line"))), "instance line = raw line");
    }

    @Test
    void anchorRotBasisMatchesTheSameContract() throws Exception {
        Map<String, Object> report = repo.brokenLinks("refcount", 100, 0, "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) report.getOrDefault("anchor_rot", List.of());
        assertTrue(!buckets.isEmpty(), "anchor rot surfaced");
        // identical prediction for every instance the bucket carries
        var bucket = buckets.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> instances = (List<Map<String, Object>>) bucket.get("instances");
        if (instances != null && !instances.isEmpty()) {
            for (Map<String, Object> i : instances) {
                int offset = Integer.parseInt(String.valueOf(i.get("offset")));
                assertEquals(probeContent.indexOf("[[Existing Note#No Such Heading"), offset,
                        "every anchor_rot instance points at the same marker (same span basis)");
            }
        }
    }

    @Test
    void multibyteProvesCharBasis() throws Exception {
        // The frontmatter ° glyphs make byte offsets drift +8 before any body
        // anchor — a byte-based projection would fail the marker anchors above.
        String fmLine = probeContent.lines().skip(1).findFirst().orElseThrow();
        assertTrue(fmLine.contains("°°°°"), "fixture sanity: multibyte in frontmatter");
        Map<String, Object> props = propertiesFor(basePath);
        var linkSpans = (List<?>) props.getOrDefault("linkSpans", List.of());
        assertTrue(!linkSpans.isEmpty());
        // char-vs-byte differential: indexOf == persisted offset — a byte
        // count would be higher by the glyph count
        var first = (Map<?, ?>) linkSpans.get(0);
        int persisted = Integer.parseInt(String.valueOf(first.get("offset")));
        int charIndex = "image".equals(first.get("kind"))
                ? probeContent.indexOf('!', probeContent.indexOf(String.valueOf(first.get("target"))))
                : probeContent.lastIndexOf('[', probeContent.indexOf(String.valueOf(first.get("target"))));
        assertEquals(charIndex, persisted, "offsets are CHAR indexes (byte count would drift)");
    }

    private Map<String, Object> propertiesFor(String path) {
        var byPath = repo.propertiesForPaths(List.of(path));
        var props = byPath.get(path);
        assertNotNull(props, "properties for " + path);
        return props;
    }
}
