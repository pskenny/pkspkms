package io.pskenny.pkspkms.io.processor;

import io.pskenny.pkspkms.io.fs.PkmsEntry;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.processor.markdown.BomStripper;
import io.pskenny.pkspkms.io.processor.markdown.FlexmarkMarkdownExtractor;
import io.pskenny.pkspkms.io.processor.markdown.MarkdownExtractor;
import io.pskenny.pkspkms.io.processor.markdown.YamlFrontmatterReader;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownProcessor {
    private static final Logger logger = LoggerFactory.getLogger(MarkdownProcessor.class);

    // Text bigger than this stays unindexed (mirrors Export's inline cap)
    static final int MAX_BODY_CHARS = 1_000_000;

    // Extraction engine: spec-grammar commonmark AST + the wikilink
    // postprocessor; LegacyMarkdownExtractor remains the parity oracle in tests
    private final MarkdownExtractor extractor = new FlexmarkMarkdownExtractor();
    private final YamlFrontmatterReader yamlFrontmatterReader;

    // Comment on the retained legacy regex fields: the parity oracle
    // (LegacyMarkdownExtractor) reads them; production extraction is AST-only
    public static final Pattern BASE_PATTERN = Pattern.compile("(?s)```base(.*?)```");
    public static final Pattern LUABASE_PATTERN = Pattern.compile("(?s)```luabase(.*?)```");

    // Body extraction for the FTS index: raw content minus the frontmatter
    // block, capped at 1 MB (over-cap → null = unindexed). RAW — feeds the
    // extraction input; the FTS body is the same slice RENDERED TO TEXT
    // (plainBody below, v6).
    static String extractBody(String content) {
        int end = frontmatterEnd(content);
        String body = end == -1 ? content : content.substring(end);
        return body.length() > MAX_BODY_CHARS ? null : body;
    }

    /**
     * v6 FTS text: the raw body rendered to plain text — heading markers,
     * emphasis, fences' syntax and link destinations drop, code-block content
     * and link/wikilink label text stay. Same frontmatter strip + 1 MB clamp
     * as the raw body. Runs only at parse+re-extract time; the loader persists
     * it and FTS reads it.
     */
    static String plainBody(String rawBody) {
        if (rawBody == null) {
            return null;
        }
        Node document = PLAIN_PARSER.parse(rawBody);
        return new TextCollectingVisitor().collectAndGetText(document);
    }

    private static final com.vladsch.flexmark.parser.Parser PLAIN_PARSER =
            com.vladsch.flexmark.parser.Parser.builder().build();

    // End index just past the frontmatter block, or -1 when absent.
    // Delegate to the shared Frontmatter oracle (BUGS.md H4): a `---` inside
    // a YAML value never closes the region, `----` never opens one — the
    // handshake the raw indexOf("---", 3) body slice used to break.
    private static int frontmatterEnd(String content) {
        return io.pskenny.pkspkms.io.Frontmatter.endOf(content);
    }

    // wikilink-shaped value in a frontmatter entry: [[inner]] where inner
    // holds no brackets; BRACKET-TRANSPARENT to YAML quoting (we scan the raw
    // text, so quoted and unquoted forms both count — the v7 contract)
    private static final Pattern FRONTMATTER_WIKILINK =
            Pattern.compile("\\[\\[([^\\[\\]]+)\\]\\]");

    /**
     * v7 frontmatter link facts: wikilink-shaped strings in the frontmatter
     * region join the graph exactly like body wikilinks — same span shape
     * ({kind: "frontmatter", target, anchor?, display?, line, offset}) at
     * FULL-content coordinates (no body-relative adjust — the region sits at
     * the top of the file). Wiki-image prefixes ({@code ![[…]]}) skip.
     */
    static List<Map<String, Object>> frontmatterLinkSpans(String content) {
        int frontEnd = frontmatterEnd(content);
        if (frontEnd < 0) {
            return List.of();
        }
        List<Map<String, Object>> spans = new ArrayList<>();
        java.util.regex.Matcher m = FRONTMATTER_WIKILINK.matcher(content);
        m.region(0, frontEnd);
        while (m.find()) {
            int at = m.start();
            if (at > 0 && content.charAt(at - 1) == '!') {
                continue; // embed prefix — not a link fact
            }
            String inner = m.group(1).strip();
            if (inner.isBlank()) {
                continue;
            }
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("kind", "frontmatter");
            String beforePipe = inner.split("\\|", 2)[0];
            String target = beforePipe.split("#")[0].strip();
            if (target.isBlank()) {
                continue;
            }
            span.put("target", target);
            int hash = beforePipe.indexOf('#');
            if (hash >= 0) {
                String anchor = beforePipe.substring(hash + 1).strip();
                if (!anchor.isBlank()) {
                    span.put("anchor", anchor);
                }
            }
            if (inner.indexOf('|') >= 0) {
                String display = inner.split("\\|", 2)[1].strip();
                if (!display.isBlank()) {
                    span.put("display", display);
                }
            }
            int line = 1;
            for (int i = 0; i < at; i++) {
                if (content.charAt(i) == '\n') {
                    line++;
                }
            }
            span.put("line", line);
            span.put("offset", at);
            spans.add(span);
        }
        return spans;
    }

    public MarkdownProcessor() {
        yamlFrontmatterReader = new YamlFrontmatterReader();
    }

    public PksFile initialParse(PkmsEntry entry, byte[] bytes) {
        // BOM stripped for parsing only — Blake3 hashes the raw bytes, so the
        // stored identity is unaffected (BUGS.md B16)
        String content = BomStripper.strip(new String(bytes, StandardCharsets.UTF_8));

        Map<String, Object> frontmatter = new HashMap<>();
        try {
            frontmatter = yamlFrontmatterReader.getFrontMatterProperties(content);
        } catch (Exception ex) {
            logger.error("Error reading YAML frontmatter for file {}", entry.relativePath(), ex);
        }

        PksFile pksFile = new PksFile(entry.relativePath(), frontmatter);
        try {
            String hash = io.pskenny.pkspkms.io.Blake3Util.hashBytes(bytes);
            pksFile.setHash(hash);
        } catch (Exception e) {
            logger.error("Error computing Blake3 hash for {}", entry.relativePath(), e);
        }
        // Body for the FTS index: the rendered plain text (v6) — over-cap
        // bodies store as NULL (unindexed). The RAW slice stays the
        // extraction input below: spans/outline are source-shape facts.
        String body = extractBody(content);
        pksFile.setBody(plainBody(body));

        // One spec-grammar parse produces all extractions. Input = the body
        // (post-frontmatter) — the frontmatter block must not leak into
        // outline/heading shapes (it would parse as a setext heading) and
        // matches the FTS body semantics. Over-cap notes extract from the
        // full content (links stay indexed even when the body doesn't).
        String extractionInput = body != null ? body : content;
        MarkdownExtractor.Extraction extraction = extractor.extract(extractionInput);
        putIfNotEmpty(pksFile, "links", extraction.links());
        // v7: frontmatter wikilink-shaped values join the graph — their raw
        // inner texts merge into the same `wikilinks` contract (display/
        // heading-split cleaning happens downstream, same as body entries)
        List<String> wikiTargets = new ArrayList<>(extraction.wikilinks());
        List<Map<String, Object>> fmSpans = frontmatterLinkSpans(content);
        for (Map<String, Object> fm : fmSpans) {
            String target = String.valueOf(fm.get("target"));
            if (!wikiTargets.contains(target)) {
                wikiTargets.add(target);
            }
        }
        putIfNotEmpty(pksFile, "wikilinks", wikiTargets);
        putIfNotEmpty(pksFile, "tasks", extraction.tasksOpen());
        putIfNotEmpty(pksFile, "tasksDone", extraction.tasksDone());
        putIfNotEmpty(pksFile, "bases", extraction.bases());
        putIfNotEmpty(pksFile, "luabases", extraction.luabases());
        putIfNotEmpty(pksFile, "outline", extraction.outline());
        // Span facts: the extractor emits body-relative offsets/lines — the
        // persisted property stores full-content coordinates (the provenance
        // consumer slices the raw file, frontmatter included).
        List<Object> allSpans = new ArrayList<>(adjustSpans(extraction.spans(), content, body));
        allSpans.addAll(fmSpans);
        putIfNotEmpty(pksFile, "wikilinkSpans", allSpans);
        // linkSpans ride the same body-relative → full-content adjust (the
        // original code stored them RAW — body-relative coordinates under a
        // full-content contract; the pinned ServerTest fixture hid it)
        putIfNotEmpty(pksFile, "linkSpans", adjustSpans(extraction.linkSpans(), content, body));
        // v6 span facts ride the same body-relative → full-content adjust:
        // TASK line/offset for editor digests, EMBED view provenance
        putIfNotEmpty(pksFile, "taskSpans", adjustSpans(extraction.taskSpans(), content, body));
        putIfNotEmpty(pksFile, "embedSpans", adjustSpans(extraction.embedSpans(), content, body));

        return pksFile;
    }

    // Span facts are body-relative (the extraction parse input); persisted
    // coordinates are full-content (frontmatter included) — provenance
    // consumers slice the raw file. One adjuster for every span family.
    // Package-private for the frontmatter/over-cap pin tests.
    static List<Object> adjustSpans(List<Map<String, Object>> raws, String content, String body) {
        if (raws == null || raws.isEmpty()) {
            return raws == null ? null : new ArrayList<>(raws);
        }
        if (body == null) {
            // Extraction ran on the FULL content (over-cap body, H3): spans
            // are already full-content coordinates — never re-shift them
            return new ArrayList<>(raws);
        }
        int bodyStart = content.length() - body.length();
        if (bodyStart <= 0) {
            return new ArrayList<>(raws);
        }
        List<Object> adjusted = new ArrayList<>();
        for (Object spanObj : raws) {
            if (spanObj instanceof Map<?, ?> span) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> spanEntry : span.entrySet()) {
                    copy.put(String.valueOf(spanEntry.getKey()), spanEntry.getValue());
                }
                int offset = Integer.parseInt(String.valueOf(copy.get("offset"))) + bodyStart;
                copy.put("offset", offset);
                int line = 1;
                for (int i = 0; i < Math.min(offset, content.length()); i++) {
                    if (content.charAt(i) == '\n') {
                        line++;
                    }
                }
                copy.put("line", line);
                adjusted.add(copy);
            } else {
                adjusted.add(spanObj);
            }
        }
        return adjusted;
    }

    private static void putIfNotEmpty(PksFile pksFile, String key, Object values) {        if (values instanceof List<?> list && !list.isEmpty()) {
            pksFile.getMutableProperties().put(key, list);
        }
    }

    public List<String> getBases(String content) {
        Matcher matcher = BASE_PATTERN.matcher(content);

        List<String> base = new ArrayList<>();
        while (matcher.find()) {
            String obsidianBaseYaml = matcher.group(1).trim();  // Extract YAML content between ```base and ```
            base.add(obsidianBaseYaml);
        }

        return base;
    }

    public List<String> getLuaBases(String content) {
        Matcher matcher = LUABASE_PATTERN.matcher(content);

        List<String> luabase = new ArrayList<>();
        while (matcher.find()) {
            String luabaseYaml = matcher.group(1).trim();
            luabase.add(luabaseYaml);
        }

        return luabase;
    }
}
