package io.pskenny.pkspkms.mcp;

import io.pskenny.pkspkms.Export;
import io.pskenny.pkspkms.io.JsonUtil;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.QueryCompiler;
import io.pskenny.pkspkms.repo.query.QueryParseException;
import io.pskenny.pkspkms.repo.query.QueryParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The MCP tool set, built over an existing {@link PksFileRepository}. Tools are
 * thin wrappers — the vault semantics stay in the repository, same as the HTTP
 * routes.
 */
public final class PkspkmsTools {

    private PkspkmsTools() {}

    public static List<McpTool> create(PksFileRepository repository) {
        return List.of(
                searchTool(repository),
                readFileTool(repository),
                graphTool(repository),
                manifestTool(repository),
                healthTool(repository),
                resolveWikilinkTool(repository),
                facetsTool(repository),
                relatedTool(repository),
                backlinksTool(repository),
                outlineTool(repository),
                orphansTool(repository),
                brokenLinksTool(repository),
                tasksTool(repository),
                searchAndReadTool(repository),
                recentTool(repository),
                graphHopsTool(repository),
                binaryFileTool(repository),
                embedsTool(repository));
    }

    // Shared query-compile step for search/graph: same behavior as the HTTP
    // layer — malformed input surfaces as a tool error carrying the parser's
    // offset + message
    private static CompiledQuery compile(String query, PksFileRepository repository) {
        try {
            return new QueryCompiler().compile(new QueryParser().parse(query), repository.getPropertyTypes());
        } catch (QueryParseException e) {
            throw new IllegalArgumentException("Bad query: " + e.getMessage());
        }
    }

    private static McpTool searchTool(PksFileRepository repository) {
        return new McpTool(
                "search",
                """
                Search the vault by property. Query syntax is Lucene-style: \
                tags:PKSPKMS, filePath:*.md, NOT tags:Archive, price:[1 TO 5], \
                status:(todo OR done), quoted phrases, wildcards * ?. \
                Empty query matches every file; limit: 0 returns totals only \
                (no rows) — the count idiom. Returns JSON {files, rows_returned, totalSize} — rows_returned = rows returned, totalSize = total matches (never limit-dependent).""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Lucene-style property query. Empty matches all."),
                                "sort", Map.of("type", "string", "description", "\"key:asc\" or \"key:desc\" — any property key, e.g. modified:desc."),
                                "limit", Map.of("type", "integer", "description", "Max files returned (default: all)."),
                                "offset", Map.of("type", "integer", "description", "Skip the first N after sorting (default 0).")),
                        "required", List.of("query")),
                args -> {
                    CompiledQuery compiled = compile(optionalStringArg(args, "query"), repository);
                    List<PksFile> results = repository.searchRegular(compiled);
                    int total = results.size();
                    results = sortAndSlice(results, args);
                    List<Map<String, Object>> files = results.stream()
                            .map(PksFile::getMutableProperties)
                            .collect(Collectors.toList());
                    return JsonUtil.mapToJson(payload(files, results.size(), total));
                });
    }

    private static McpTool readFileTool(PksFileRepository repository) {
        return new McpTool(
                "read_file",
                """
                Read a note's raw markdown. Paths are vault-relative (Notes/foo.md) \
                or prefixed with a virtual-vault alias (@gwern/foo.md). \
                .obsidian/.trash and path traversal are rejected. \
                Optional `section`: a heading's bounded slice — heading line \
                included, next same-or-higher heading excluded, first match \
                wins, fenced code can't fake boundaries; unknown heading errors \
                with candidate hints from the note's outline. \
                Optional head_lines/head_chars caps truncate with a visible marker.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Vault-relative path, or @alias/-prefixed for virtual vaults."),
                                "section", Map.of("type", "string", "description", "Optional heading text (case-insensitive) — see description for slice rules."),
                                "head_lines", Map.of("type", "integer", "description", "Optional: keep only the first N lines."),
                                "head_chars", Map.of("type", "integer", "description", "Optional: keep at most N characters.")),
                        "required", List.of("path")),
                args -> {
                    String path = stringArg(args, "path");
                    String content = repository.readFile(path);
                    String section = optionalStringArg(args, "section");
                    if (!section.isEmpty()) {
                        Export.SectionSlice slice = Export.sectionSlice(
                                content == null ? "" : content, section);
                        if (slice.headingStart() < 0) {
                            // hint candidates from the note's stored outline
                            String candidates = repository
                                    .propertiesForPaths(java.util.List.of(path))
                                    .getOrDefault(path, Map.of())
                                    .get("outline") instanceof List<?> items
                                    ? " — headings: " + items.stream()
                                        .limit(5)
                                        .map(i -> String.valueOf(((java.util.Map<?, ?>) i).get("text")))
                                        .collect(java.util.stream.Collectors.joining("; "))
                                    : "";
                            throw new IllegalArgumentException(
                                    "No heading \"" + section + "\" in " + path + candidates);
                        }
                        content = slice.content();
                    }
                    content = applyHead(content,
                            optionalIntArg(args, "head_lines"), optionalIntArg(args, "head_chars"));
                    return content == null ? "" : content;
                });
    }

    private static McpTool graphTool(PksFileRepository repository) {
        return new McpTool(
                "graph",
                """
                Search the vault and reduce every hit to {filePath, links, backlinks, \
                tags} — shaped for graph questions like "what links here". \
                Same Lucene-style query syntax as search; empty query matches all. \
                Capped: default 100 rows (max 500) — `truncated:true` + `next_offset` \
                continue the walk. `shape:"ids"` returns bare {filePath} rows. \
                Returns JSON {files, rows_returned, totalSize, truncated, next_offset?}.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Lucene-style property query. Empty matches all."),
                                "sort", Map.of("type", "string", "description", "\"key:asc\" or \"key:desc\"."),
                                "limit", Map.of("type", "integer", "description", "Page size (default 100, max 500)."),
                                "offset", Map.of("type", "integer", "description", "Skip the first N (default 0)."),
                                "shape", Map.of("type", "string", "enum", List.of("full", "ids"),
                                        "description", "full = {filePath, links, backlinks, tags}; ids = bare paths.")),
                        "required", List.of("query")),
                args -> {
                    CompiledQuery compiled = compile(optionalStringArg(args, "query"), repository);
                    List<PksFile> hits = repository.searchRegular(compiled);
                    int total = hits.size();
                    // sort via the shared comparator; slice ourselves so the
                    // default cap (100) applies even without a limit arg
                    List<PksFile> sorted = sortOf(hits, args);
                    int limitCap = optionalIntArg(args, "limit") == null
                            ? 100 : Math.min(intArg(args, "limit", 100, 500), 500);
                    int offset = optionalIntArg(args, "offset") == null
                            ? 0 : Math.max(0, optionalIntArg(args, "offset"));
                    int from = Math.min(offset, sorted.size());
                    int to = Math.min(from + limitCap, sorted.size());
                    boolean ids = "ids".equals(args.getOrDefault("shape", "full"));
                    List<Map<String, Object>> files = new ArrayList<>();
                    for (int i = from; i < to; i++) {
                        PksFile hit = sorted.get(i);
                        files.add(ids
                                ? new LinkedHashMap<>(Map.of("filePath", hit.getFilePath()))
                                : graphShape(hit));
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("files", files);
                    payload.put("rows_returned", files.size());
                    payload.put("totalSize", total);
                    boolean truncated = (long) from + files.size() < total;
                    payload.put("truncated", truncated);
                    if (truncated) {
                        payload.put("next_offset", from + files.size());
                    }
                    return JsonUtil.mapToJson(payload);
                });
    }

    private static McpTool manifestTool(PksFileRepository repository) {
        return new McpTool(
                "manifest",
                "Per-virtual-vault {filePath, blake3} pairs (pkspkms:// targets). Main-vault files are excluded.",
                Map.of("type", "object", "properties", Map.of()),
                args -> JsonUtil.mapToJson(repository.manifest()));
    }

    private static McpTool healthTool(PksFileRepository repository) {
        return new McpTool(
                "health",
                "Read-only diagnostics snapshot: {status: ok|degraded, server, vaults, index, fts}. "
                        + "Degraded when the FTS index is missing or can't be probed, generated-content errors "
                        + "exist (embedErrors), or rows wait unparsed (unparsedRows — cache-shaped or mid-load rows). "
                        + "Never writes, never runs integrity checks; sub-5ms. Use ping for transport liveness.",
                Map.of("type", "object", "properties", Map.of()),
                args -> JsonUtil.mapToJson(repository.healthSnapshot()));
    }

    private static McpTool resolveWikilinkTool(PksFileRepository repository) {
        return new McpTool(
                "resolve_wikilink",
                "Resolve a wikilink target (e.g. \"My Note\" or \"Notes/My Note\") to the vault path it points at.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "wikilink", Map.of("type", "string", "description", "Wikilink target without the [[ ]] brackets.")),
                        "required", List.of("wikilink")),
                args -> {
                    String wikilink = stringArg(args, "wikilink");
                    String resolved = repository.resolveWikilink(wikilink);
                    if (resolved == null || resolved.isEmpty()) {
                        throw new IllegalArgumentException("No file matches wikilink: " + wikilink);
                    }
                    return resolved;
                });
    }

    // --- read-only drill-down tools ---

    private static final List<String> DEFAULT_FACET_KEYS = List.of("tags", "folder", "type", "modified");

    private static final String FACET_EXCLUDED_DOC =
            "tags, folder, type, modified, vault, or any property key (text, body, filePath, links, backlinks rejected).";

    private static McpTool facetsTool(PksFileRepository repository) {
        return new McpTool(
                "facets",
                """
                Facet value counts over a filtered result set — drill-down orientation \
                without fetching rows: {resultCount, facets: {key: [{value, count}]}}. \
                Multi-value properties count per element (a note with 3 tags counts in all \
                3 buckets); a missing property lands in the (missing) bucket. \
                Query syntax is the same Lucene-style grammar as search; text: FTS probes \
                compose. Keys: """
                        + FACET_EXCLUDED_DOC,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Lucene-style query. Empty = whole vault."),
                                "keys", Map.of("type", "array", "items", Map.of("type", "string"), "description", FACET_EXCLUDED_DOC),
                                "limit", Map.of("type", "integer", "description", "Max values per facet (default 10, max 100).")),
                        "required", List.of()),
                args -> {
                    String query = optionalStringArg(args, "query");
                    CompiledQuery compiled = compile(query, repository);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("query", query);
                    payload.putAll(repository.facets(compiled, keysArg(args), intArg(args, "limit", 10, 100)));
                    return JsonUtil.mapToJson(payload);
                });
    }

    private static McpTool relatedTool(PksFileRepository repository) {
        return new McpTool(
                "related",
                """
                One-hop link neighborhood of a note: {path, outgoing, incoming} — \
                input accepts a vault-relative path (Notes/foo.md, @alias/foo.md) \
                or a wikilink target ("My Note").""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Vault-relative path or wikilink target.")),
                        "required", List.of("path")),
                args -> JsonUtil.mapToJson(relatedOrMissing(repository, stringArg(args, "path"))));
    }

    /**
     * T1/B1: a missing target is a zero-result, not a throw — parallel batch
     * callers must not fail wholesale on one bad name, and "no note" must
     * stay distinguishable from "note exists, no edges" via {@code exists}.
     */
    private static Map<String, Object> relatedOrMissing(PksFileRepository repository, String path) {
        Map<String, Object> payload;
        try {
            payload = repository.related(path);
        } catch (IllegalArgumentException missing) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("exists", false);
            out.put("outgoing", List.of());
            out.put("incoming", List.of());
            return out;
        }
        payload.put("exists", true);
        return payload;
    }

    private static McpTool outlineTool(PksFileRepository repository) {
        return new McpTool(
                "outline",
                "Note structure: {path, items: [{text, level}]} — the note's headings in document order with their ATX depth (frontmatter never counts). Level-aware, so you can see the subsection layout without reading the note. Input: `path`.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Vault-relative path or wikilink target.")),
                        "required", List.of("path")),
                args -> {
                    String path = stringArg(args, "path");
                    Map<String, Object> payload = relatedOrMissing(repository, path);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("path", payload.get("path"));
                    if (Boolean.FALSE.equals(payload.get("exists"))) {
                        out.put("exists", false);
                        out.put("items", List.of());
                        out.put("rows_returned", 0);
                        return JsonUtil.mapToJson(out);
                    }
                    out.put("exists", true);
                    Map<String, Object> props = repository
                            .propertiesForPaths(List.of(String.valueOf(payload.get("path"))))
                            .getOrDefault(String.valueOf(payload.get("path")), Map.of());
                    List<?> items = props.get("outline") instanceof List<?> list ? list : List.of();
                    out.put("items", items);
                    out.put("rows_returned", items.size());
                    return JsonUtil.mapToJson(out);
                });
    }

    private static McpTool orphansTool(PksFileRepository repository) {
        return new McpTool(
                "orphans",
                """
                Notes with no link edges in either direction — the revisit queue. \
                Markdown notes only. Paged: {orphans, count, offset, has_more} — \
                count is the total island count, orphans the current page. \
                has_more is true while count > offset + rows_returned.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "limit", Map.of("type", "integer", "description", "Page size (default 100, max 500)."),
                                "offset", Map.of("type", "integer", "description", "Skip the first N (default 0).")),
                        "required", List.of()),
                args -> {
                    int limit = intArg(args, "limit", 100, 500);
                    int offset = optionalIntArg(args, "offset") == null
                            ? 0 : Math.max(0, optionalIntArg(args, "offset"));
                    List<String> orphans = repository.orphanPaths(limit, offset);
                    long count = repository.orphanCount();
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("orphans", orphans);
                    payload.put("count", count);
                    payload.put("offset", offset);
                    payload.put("has_more", count > (long) offset + orphans.size());
                    return JsonUtil.mapToJson(payload);
                });
    }

    private static McpTool tasksTool(PksFileRepository repository) {
        return new McpTool(
                "tasks",
                """
                Open checkbox tasks ({@code - [ ]} lines) across the vault as a flat \
                {tasks: [{file, text}]} list. Query composes: pass a Lucene-style \
                query to scope it (e.g. tags:rust). Done checkboxes live in the \
                tasksDone property — query tasksDone:* to list completed work.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Optional Lucene-style scope query. Empty = whole vault."),
                                "limit", Map.of("type", "integer", "description", "Max tasks returned (default 100, max 500).")),
                        "required", List.of()),
                args -> {
                    String userQuery = optionalStringArg(args, "query");
                    String query = userQuery.isEmpty()
                            ? "tasks:*"
                            // composed: open-task probe AND the user's scope
                            : "(tasks:*) AND (" + userQuery + ")";
                    CompiledQuery compiled = compile(query, repository);
                    int limit = intArg(args, "limit", 100, 500);
                    List<Map<String, Object>> tasks = new ArrayList<>();
                    outer:
                    for (PksFile file : repository.searchRegular(compiled)) {
                        String filePath = file.getFilePath();
                        // v6: line/offset facts ride the spans when stamped —
                        // legacy rows (pre-v6 extract) list bare
                        List<Map<String, Object>> spans = new ArrayList<>();
                        if (file.getAsList("taskSpans").size() > 0) {
                            for (Object spanObj : file.getAsList("taskSpans")) {
                                if (spanObj instanceof Map<?, ?> span) {
                                    spans.add((Map<String, Object>) span);
                                }
                            }
                        }
                        for (Object text : file.getAsList("tasks")) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("file", filePath);
                            entry.put("text", String.valueOf(text));
                            String clean = String.valueOf(text);
                            Map<String, Object> match = spans.stream()
                                    .filter(sp -> String.valueOf(sp.get("text")).equals(clean)
                                            && Boolean.TRUE.equals(sp.get("open")))
                                    .findFirst()
                                    .orElse(null);
                            if (match != null) {
                                entry.put("line", match.get("line"));
                                entry.put("offset", match.get("offset"));
                            }
                            tasks.add(entry);
                            if (tasks.size() >= limit) {
                                break outer;
                            }
                        }
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("tasks", tasks);
                    payload.put("rows_returned", tasks.size());
                    return JsonUtil.mapToJson(payload);
                });
    }

    /** v6: embedded views (base/luabase fences) per note, line-precise. */
    private static McpTool embedsTool(PksFileRepository repository) {
        return new McpTool(
                "embeds",
                """
                Embedded views (base/luabase fences) across the vault, \
                line-precise: {embeds: [{filePath, kind, line, offset}], count, \
                offset, has_more}. Facts only — the view's content lives in the \
                source note (read_file section / head caps serve that).""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "kind", Map.of("type", "string", "enum", List.of("base", "luabase"),
                                        "description", "Optional kind filter."),
                                "limit", Map.of("type", "integer", "description", "Page size (default 100, max 500)."),
                                "offset", Map.of("type", "integer", "description", "Skip the first N (default 0).")),
                        "required", List.of()),
                args -> {
                    CompiledQuery probe = compile("embedSpans:*", repository);
                    List<PksFile> rows = repository.searchRegular(probe);
                    List<Map<String, Object>> all = new ArrayList<>();
                    Object kindArg = args.get("kind");
                    String kindFilter = kindArg == null ? "" : kindArg.toString();
                    for (PksFile row : rows) {
                        String filePath = row.getFilePath();
                        for (Object spanObj : row.getAsList("embedSpans")) {
                            if (!(spanObj instanceof Map<?, ?> span)) {
                                continue;
                            }
                            String kind = String.valueOf(span.get("kind"));
                            if (!kindFilter.isEmpty() && !kindFilter.equals(kind)) {
                                continue;
                            }
                            Map<String, Object> embed = new LinkedHashMap<>();
                            embed.put("filePath", filePath);
                            embed.put("kind", kind);
                            embed.put("line", span.get("line"));
                            embed.put("offset", span.get("offset"));
                            all.add(embed);
                        }
                    }
                    int limit = intArg(args, "limit", 100, 500);
                    int offset = optionalIntArg(args, "offset") == null
                            ? 0 : Math.max(0, optionalIntArg(args, "offset"));
                    int from = Math.min(offset, all.size());
                    int to = Math.min(from + Math.max(0, limit), all.size());
                    List<Map<String, Object>> page = all.subList(from, to);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("embeds", page);
                    payload.put("count", all.size());
                    payload.put("offset", offset);
                    payload.put("has_more", to < all.size());
                    return JsonUtil.mapToJson(payload);
                });
    }

    private static McpTool searchAndReadTool(PksFileRepository repository) {        return new McpTool(
                "search_and_read",
                """
                Search and read in one round trip: [{filePath, modified, head}] for \
                the top hits, each head capped at head_chars. Per-file read errors \
                surface as {filePath, error} entries instead of failing the batch.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Lucene-style query. Empty matches all."),
                                "sort", Map.of("type", "string", "description", "\"key:asc\" or \"key:desc\"."),
                                "limit", Map.of("type", "integer", "description", "Max files (default 5, max 20)."),
                                "head_chars", Map.of("type", "integer", "description", "Per-file head cap (default 1200, max 20000).")),
                        "required", List.of("query")),
                args -> {
                    CompiledQuery compiled = compile(optionalStringArg(args, "query"), repository);
                    List<PksFile> hits = sortAndSlice(repository.searchRegular(compiled), args);
                    int limit = intArg(args, "limit", 5, 20);
                    int headChars = intArg(args, "head_chars", 1200, 20000);
                    List<Map<String, Object>> entries = new ArrayList<>();
                    int budget = 100_000;
                    int skipped = 0;
                    for (PksFile hit : hits) {
                        if (entries.size() >= limit || budget <= 0) {
                            skipped++;
                            continue;
                        }
                        String filePath = hit.getFilePath();
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("filePath", filePath);
                        Object modified = hit.getMutableProperties().get("modified");
                        if (modified != null) {
                            entry.put("modified", modified);
                        }
                        try {
                            String content = repository.readFile(filePath);
                            String head = applyHead(content, null, headChars);
                            budget -= head.length();
                            entry.put("head", head);
                        } catch (Exception e) {
                            entry.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
                        }
                        entries.add(entry);
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("entries", entries);
                    payload.put("rows_returned", entries.size());
                    payload.put("totalSize", hits.size());
                    if (skipped > 0) {
                        payload.put("skipped", skipped + " beyond limit/budget");
                    }
                    return JsonUtil.mapToJson(payload);
                });
    }

    private static McpTool recentTool(PksFileRepository repository) {
        return new McpTool(
                "recent",
                "Newest notes first — shorthand for search with sort:modified:desc. [{filePath, modified}] entries.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "limit", Map.of("type", "integer", "description", "Max files (default 10, max 500).")),
                        "required", List.of()),
                args -> {
                    CompiledQuery compiled = compile("", repository);
                    Map<String, Object> paging = new LinkedHashMap<>();
                    paging.put("sort", "modified:desc");
                    paging.put("limit", intArg(args, "limit", 10, 500));
                    List<PksFile> hits = sortAndSlice(repository.searchRegular(compiled), paging);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("entries", hits.stream().map(f -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("filePath", f.getFilePath());
                        Object modified = f.getMutableProperties().get("modified");
                        if (modified != null) {
                            entry.put("modified", modified);
                        }
                        return entry;
                    }).collect(Collectors.toList()));
                    payload.put("rows_returned", hits.size());
                    return JsonUtil.mapToJson(payload);
                });
    }

    private static McpTool graphHopsTool(PksFileRepository repository) {
        return new McpTool(
                "graph_hops",
                """
                Link-graph neighborhood of a note, 1-2 hops in both directions \
                (capped at 200 ids): {path, exists, hops, files: [{filePath, links, \
                backlinks, tags}], truncated} — truncated:true when the 200-id \
                cap bites, next visit should scope the query first. Input accepts \
                a path or wikilink target; missing targets zero-result.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Vault-relative path or wikilink target."),
                                "hops", Map.of("type", "integer", "description", "1 or 2 (default 1, values > 2 clamp).")),
                        "required", List.of("path")),
                args -> {
                    String path = stringArg(args, "path");
                    int hops = optionalIntArg(args, "hops") == null ? 1 : optionalIntArg(args, "hops");
                    Map<String, Object> payload;
                    try {
                        payload = repository.graphHops(path, hops);
                    } catch (IllegalArgumentException missing) {
                        // T1/B1: missing target is a zero-result, not a throw
                        Map<String, Object> miss = new LinkedHashMap<>();
                        miss.put("path", path);
                        miss.put("exists", false);
                        miss.put("hops", hops);
                        miss.put("files", List.of());
                        miss.put("rows_returned", 0);
                        return JsonUtil.mapToJson(miss);
                    }
                    payload.put("exists", true);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> hopFiles = (List<Map<String, Object>>) payload
                            .getOrDefault("files", List.of());
                    payload.put("truncated", hopFiles.size() >= 200);
                    return JsonUtil.mapToJson(payload);
                });
    }

    private static McpTool backlinksTool(PksFileRepository repository) {
        return new McpTool(
                "backlinks",
                """
                Inbound links with provenance: {note, inbound_count, links: [{source, \
                line, via, context}]} for the given vault path or wikilink target. \
                via = "canonical" (page ref or base name). Provenance reads \
                parse-time wikilink spans — inline/fenced code \
                ghosts excluded by construction.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Vault-relative path or wikilink target."),
                                "limit", Map.of("type", "integer", "description", "Max link occurrences (default 50, max 500)."),
                                "context_chars", Map.of("type", "integer", "description", "Chars around each hit (default 80, max 200)."),
                                "vault", Map.of("type", "string", "description", "Optional virtual-vault alias scope (without @).")),
                        "required", List.of("path")),
                args -> {
                    String path = stringArg(args, "path");
                    int limit = intArg(args, "limit", 50, 500);
                    int contextChars = intArg(args, "context_chars", 80, 200);
                    String scope = validateVaultScope(args, repository);
                    Map<String, Object> payload = relatedOrMissing(repository, path);
                    if (Boolean.FALSE.equals(payload.get("exists"))) {
                        // T1/B1: missing target surfaces as zero-result
                        Map<String, Object> miss = new LinkedHashMap<>();
                        miss.put("path", path);
                        miss.put("exists", false);
                        miss.put("inbound_count", 0);
                        miss.put("links", List.of());
                        return JsonUtil.mapToJson(miss);
                    }
                    // provenance candidates: the resolved page ref and its
                    // base name (case-insensitive — the span target stores
                    // the raw page ref)
                    Map<String, String> viaByCandidate = new LinkedHashMap<>();
                    String base = baseNameOf(path);
                    viaByCandidate.put(base.strip().toLowerCase(Locale.ROOT), "canonical");
                    viaByCandidate.put(path.strip().toLowerCase(Locale.ROOT), "canonical");
                    // matches keyed span.target → via; full-content infos ride
                    // the stored spans (parse-time facts, frontmatter-inclusive)

                    List<?> incoming = (List<?>) payload.getOrDefault("incoming", List.of());
                    List<String> sourcePaths = incoming.stream().map(String::valueOf).toList();
                    Map<String, Map<String, Object>> sourceProps = repository.propertiesForPaths(sourcePaths);

                    List<Map<String, Object>> links = new ArrayList<>();
                    int inboundCount = incoming.size();
                    outer:
                    for (String source : sourcePaths) {
                        if (!scope.isEmpty() && !source.startsWith(scope)) {
                            continue;
                        }
                        Map<String, Object> props = sourceProps.get(source);
                        if (props == null || !(props.get("wikilinkSpans") instanceof List<?> spansList)) {
                            continue; // source without span facts: edge listed bare
                        }
                        String content = null; // lazy: only when a span matches
                        for (Object spanObj : spansList) {
                            if (!(spanObj instanceof Map<?, ?> span)) {
                                continue;
                            }
                            String spanTarget = String.valueOf(span.get("target"));
                            String via = viaByCandidate.get(spanTarget.strip().toLowerCase(Locale.ROOT));
                            if (via == null) {
                                continue;
                            }
                            if (content == null) {
                                content = repository.readFile(source);
                            }
                            int offset = Integer.parseInt(String.valueOf(span.get("offset")));
                            int line = Integer.parseInt(String.valueOf(span.get("line")));
                            String linkText = "[[" + spanTarget + "]]";
                            String context = content.substring(
                                    Math.max(0, offset - contextChars),
                                    Math.min(content.length(), offset + linkText.length() + contextChars))
                                    .strip();
                            Map<String, Object> link = new LinkedHashMap<>();
                            link.put("source", source);
                            link.put("line", line);
                            link.put("via", via);
                            link.put("context", context);
                            links.add(link);
                            if (links.size() >= limit) {
                                break outer;
                            }
                        }
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("path", path);
                    out.put("exists", true);
                    out.put("inbound_count", inboundCount);
                    out.put("links", links);
                    return JsonUtil.mapToJson(out);
                });
    }

    private static String baseNameOf(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** "@alias" prefix filter for scoping, or "" for the whole vault. */
    private static String validateVaultScope(Map<String, Object> args, PksFileRepository repository) {
        Object value = args.get("vault");
        if (value == null || value.toString().isEmpty()) {
            return "";
        }
        String alias = value.toString();
        if (alias.startsWith("@")) {
            alias = alias.substring(1);
        }
        if (!repository.vaultAliasExists(alias)) {
            throw new IllegalArgumentException("Unknown virtual vault alias: @" + alias);
        }
        return "@" + alias + "/";
    }

    private static McpTool brokenLinksTool(PksFileRepository repository) {
        return new McpTool(
                "broken_links",
                """
                Vault-wide link-hygiene report in three sections. `targets` \
                = dangling [[wikilinks]]: {target, refcount, sources, instances} \
                — instances carry per-occurrence {source, line, offset} parse \
                facts, near-miss spellings cluster under one normalized bucket. \
                `md_targets` = unresolvable markdown destinations ([t](dead.md)) \
                resolved through the same path/name ladder. `anchor_rot` = \
                [[note#Anchor]] with existing note and no matching heading — \
                buckets carry `headings` repair hints (target's heading slugs). \
                Each section refcount-sorted by default (`sort` also applies), \
                refcount desc so the highest-value targets surface first. \
                Markdown-link and fenced targets are out of scope.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "sort", Map.of("type", "string", "enum", List.of("refcount", "target", "source"),
                                        "description", "Default refcount desc."),
                                "limit", Map.of("type", "integer", "description", "Max targets (default 100, max 500)."),
                                "min_refs", Map.of("type", "integer", "description", "Refcount floor (typo-noise filter)."),
                                "vault", Map.of("type", "string", "description", "Optional virtual-vault alias scope (without @).")),
                        "required", List.of()),
                args -> {
                    Map<String, Object> payload = buildBrokenLinksArgs(repository, args);
                    return JsonUtil.mapToJson(payload);
                });
    }

    private static Map<String, Object> buildBrokenLinksArgs(PksFileRepository repository,
                                                           Map<String, Object> args) {
        return repository.brokenLinks(
                optionalStringArg(args, "sort"),
                intArg(args, "limit", 100, 500),
                optionalIntArg(args, "min_refs") == null ? 0 : optionalIntArg(args, "min_refs"),
                validateVaultScope(args, repository));
    }

    private static Map<String, Object> payload(List<Map<String, Object>> items,
                                               int rowsReturned, int totalSize) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", items);
        out.put("rows_returned", rowsReturned);
        out.put("totalSize", totalSize);
        return out;
    }

    private static McpTool binaryFileTool(PksFileRepository repository) {
        return new McpTool(
                "binary_file",
                """
                Read-only binary access: mode=stat returns {path, size, modified, ext} \
                without content; mode=base64 returns a bounded Base64 payload \
                (rejected over 5 MB — stat first). Same path rules as read_file.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Vault-relative path, or @alias/-prefixed for virtual vaults."),
                                "mode", Map.of("type", "string", "enum", List.of("stat", "base64"), "description", "Default stat.")),
                        "required", List.of("path")),
                args -> {
                    String path = stringArg(args, "path");
                    Object modeArg = args.get("mode");
                    String mode = modeArg == null || modeArg.toString().isEmpty() ? "stat" : modeArg.toString();
                    return JsonUtil.mapToJson(repository.binaryFile(path, mode));
                });
    }

    // --- token-economy helpers, shared by the tools ---

    /**
     * Sorts by "key:asc|desc" (any property key; numeric-looking values
     * compare numerically, nulls last in either direction), then slices
     * limit/offset. Optional for tools without paging.
     */
    private static List<PksFile> sortAndSlice(List<PksFile> results, Map<String, Object> args) {
        List<PksFile> sorted = sortOf(results, args);
        Integer offsetArg = optionalIntArg(args, "offset");
        Integer limitArg = optionalIntArg(args, "limit");
        int from = Math.min(offsetArg == null ? 0 : Math.max(0, offsetArg), sorted.size());
        int to = limitArg == null ? sorted.size()
                : (int) Math.min(sorted.size(), (long) from + Math.max(0, limitArg));
        if (from > to) {
            from = to;
        }
        return sorted.subList(from, to);
    }

    /** Sort-only view of sortAndSlice — capsule without limit/offset, for tools that cap themselves (graph). */
    private static List<PksFile> sortOf(List<PksFile> results, Map<String, Object> args) {
        Object sortArg = args.get("sort");
        if (sortArg != null && !sortArg.toString().isEmpty()) {
            String spec = sortArg.toString();
            int sep = spec.lastIndexOf(':');
            String key = sep > 0 ? spec.substring(0, sep) : spec;
            String dir = sep > 0 ? spec.substring(sep + 1) : "asc";
            final boolean descending = switch (dir) {
                case "asc" -> false;
                case "desc" -> true;
                default -> throw new IllegalArgumentException(
                        "Bad sort direction \"" + dir + "\" (use key:asc or key:desc)");
            };
            final String sortKey = key;
            return results.stream()
                    .sorted((a, b) -> compareValueProps(a, b, sortKey, descending))
                    .toList();
        }
        return results;
    }

    private static int compareValueProps(PksFile a, PksFile b, String key, boolean descending) {
        Object av = a.getMutableProperties().get(key);
        Object bv = b.getMutableProperties().get(key);
        if (av == null && bv == null) return 0;
        if (av == null) return 1;  // nulls last, both directions
        if (bv == null) return -1;
        int c = compareValues(av, bv);
        return descending ? -c : c;
    }

    /** Number-looking values compare numerically; everything else as text. */
    private static int compareValues(Object a, Object b) {
        Double na = numericOf(a), nb = numericOf(b);
        if (na != null && nb != null) {
            return Double.compare(na, nb);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static Double numericOf(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && s.matches("-?\\d+(\\.\\d+)?")) {
            return Double.parseDouble(s);
        }
        return null;
    }

    /** head_lines/head_chars truncation with a visible marker. */
    private static String applyHead(String content, Integer headLines, Integer headChars) {
        if (content == null) {
            return "";
        }
        if (headLines != null && headLines >= 0) {
            String[] lines = content.split("\n", -1);
            if (lines.length > headLines) {
                content = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, headLines))
                        + "\n… [truncated " + (lines.length - headLines) + "/" + lines.length + " lines]";
            }
        }
        if (headChars != null && headChars >= 0 && content.length() > headChars) {
            content = content.substring(0, headChars) + "… [truncated at " + headChars + " chars]";
        }
        return content;
    }

    // Same reduction as GET /files/list/graph; textSnippet rides along when
    // the query carries a text: probe (generated at search time)
    private static Map<String, Object> graphShape(PksFile pksFile) {
        pksFile.filterProperties(List.of("links", "backlinks", "tags", "filePath", "textSnippet"), List.of());
        return pksFile.getMutableProperties();
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || value.toString().isEmpty()) {
            throw new IllegalArgumentException("Missing argument: " + key);
        }
        return value.toString();
    }

    private static String optionalStringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : value.toString();
    }

    private static int intArg(Map<String, Object> args, String key, int fallback, int max) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Argument must be an integer: " + key);
        }
        return Math.max(1, Math.min(parsed, max));
    }

    private static Integer optionalIntArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || value.toString().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Argument must be an integer: " + key);
        }
    }
    private static List<String> keysArg(Map<String, Object> args) {
        Object value = args.get("keys");
        if (value == null) {
            return DEFAULT_FACET_KEYS;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("keys must be an array of strings");
    }
}
