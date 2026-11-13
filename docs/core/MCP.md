---
title: MCP
---

# MCP (Model Context Protocol)

> [!NOTE]
> MCP is read-only? Why? I think analysis is fine to give to an agent but your 
> PKMS should be an imprint of your brain. A PKMS consists of things I 
> know or want to know or remember. I also do not have the discipline 
> to not let agents mess up my own PKMS making huge additions and changes that 
> I would decide flippantly.

`POST /mcp` turns the vault into tools for AI clients (OpenCode, Claude Code,
Cline, …). Streamable HTTP, JSON responses — no SSE stream, no sessions.
Protocol: `2025-11-25` through `2024-11-05`, negotiated at `initialize`.
Behind the same bearer token as every data route when `--enable-auth` is on.

```shell
opencode mcp add pkspkms --type remote --url http://localhost:3000/mcp \
  --header "Authorization: Bearer $(cat ~/.config/pkspkms/token)"
```

Tool failures surface as results with `isError: true` carrying the reason;
protocol failures (unknown tool, bad params) are JSON-RPC errors.

## Tools

Read-only surface (16). There are no write tools: the vault stays immutable
over MCP — edits happen in your editor, the index refreshes on the next
server start.

| Tool | What it does |
|---|---|
| `search` | Lucene-style property query → `{files, rows_returned, totalSize}` — `rows_returned` is the post-clamp row count, `totalSize` the true match count. Optional `sort:"key:asc/desc"`, `limit`, `offset`. `text:` searches note bodies (FTS); hits carry `modified`. Empty query matches all; `limit: 0` returns totals only (the count idiom) |
| `read_file` | Raw markdown. Optional `section`: heading-bounded slice (heading line in, next same-or-higher heading out, first match wins, fences can't fake boundaries; unknown heading errors with outline hints). Optional `head_lines` / `head_chars` caps truncate with a visible marker. Missing note throws (text tool) |
| `health` | Read-only diagnostics snapshot `{status: ok\|degraded, server: {name, version, schemaVersion}, vaults: [{alias, files}], index: {links, embedSeeds, embedErrors, unparsedRows, newestNoteModified?}, fts: {probe}}` — degraded when the FTS probe fails, generated-content ERROR rows exist, or rows wait unparsed (cache-shaped/mid-load). Never writes, no integrity runs; < 5 ms. Use `ping` for transport liveness |
| `graph` | Search reduced to `{filePath, links, backlinks, tags}` capped at 100 rows (max 500) with `truncated`/`next_offset` continuation; `shape:"ids"` returns bare paths; ±`textSnippet` on `text:` probes |
| `manifest` | Per-virtual-vault `{filePath, blake3}` pairs |
| `resolve_wikilink` | Wikilink target → vault path (path, filename.ext, or filename) |
| `facets` | Value counts over a filtered result set: `{resultCount, facets: {key: [{value, count}]}}` |
| `related` | One-hop neighborhood `{path, exists, outgoing, incoming}` — path or wikilink input; a missing target zero-results with `exists:false` (never an error) |
| `backlinks` | Inbound links with provenance: `{path, inbound_count, links: [{source, line, via, context}]}` — `via` is `canonical`; provenance reads parse-time `wikilinkSpans` (no query-time re-scan, no stale cache, code-span ghosts excluded by construction). Input: `path`. Missing target zero-results with `exists:false` |
| `outline` | A note's heading structure: `{path, items: [{text, level}]}` in document order (frontmatter never counts). Input: `path`. Missing note → `{path, exists: false, items: []}` |
| `orphans` | Notes with no link edges in either direction — paged `{orphans, count, offset, has_more}`; `count` is the total island count |
| `broken_links` | Vault-wide link-hygiene report in three sections: `targets` = dangling wikilinks (+ `instances` parse facts ≤ 50/bucket), `md_targets` = unresolvable markdown destinations (same path/name ladder), `anchor_rot` = `[[note#anchor]]` with existing note and no matching heading (`headings` hints ride each bucket). Near-miss spellings cluster per normalized bucket; refcount-desc default (`sort=target/source/refcount`, `min_refs` floor, `limit` caps each section, max 500); supersedes the manual `danglingLinks:*` recipe |
| `tasks` | Open checkbox tasks `[{file, text, line, offset}]` (v6 line facts ride the spans; legacy pre-v6 rows list bare), optional Lucene scope |
| `search_and_read` | Search + capped heads in one round trip (≤ 20 files) |
| `recent` | Newest first — shorthand for `sort:modified:desc` |
| `graph_hops` | 1–2 hop link neighborhood (≤ 200 ids, `truncated:true` when the cap bites); missing targets zero-result with `exists:false` |
| `binary_file` | `mode=stat` → `{path, size, modified, ext}`; `mode=base64` → bounded Base64 (≤ 5 MB) |
| `embeds` | Embedded views (base/luabase fences) per note, line-precise: `{embeds: [{filePath, kind, line, offset}], count, offset, has_more}`; optional `kind` filter |

## Generated keys

Alongside frontmatter, every search hit carries:

| Key | Source |
|---|---|
| `filePath` | vault-relative path (`@alias/`-prefixed for virtual vaults) |
| `links` | resolved outgoing targets (markdown links + wikilinks) |
| `backlinks` | incoming targets, derived at query time |
| `aliases` | frontmatter alias texts — `aliases:Finances` finds the note |
| `modified` | file mtime as ISO-8601 → recency ranges like `modified:[2026-11-01 TO *]` |
| `tasks` / `tasksDone` | checkbox lines from the body (open / checked) |
| `danglingLinks` | unresolved wikilink targets — `danglingLinks:*` is the link-hygiene report |
| `outline` | `[{text, level}]` heading entries in document order (spec-grammar parse; frontmatter never leaks in) |
| `wikilinkSpans` | parse-time facts per wikilink `[{kind, target, anchor?, display?, line, offset}]` — `kind` is `wikilink` (body, incl. base/luabase interiors) or `frontmatter` (v7: wikilink-shaped values in frontmatter entries join the graph); backlink-provenance backing; code spans excluded by construction |
| `linkSpans` | same shape for markdown links/images `[{kind, target, line, offset}]` — parse facts per `link`/`image` destination; code spans excluded by construction |
| `taskSpans` | `[{text, open, line, offset}]` per checkbox occurrence — `line` is the checkbox's line (1-based), `offset` is the char index of the task TEXT (after the checkbox marker, not the line start); the `tasks` tool's line facts |
| `embedSpans` | `[{kind: "base"/"luabase", line, offset}]` per embedded-view fence — the `embeds` tool backing |

#### Span coordinates — the basis every span and instance fact uses

All coordinate facts in this document (`wikilinkSpans`, `linkSpans`, `taskSpans`,
`embedSpans`, and the `{source, line, offset}` instances of `broken_links`,
its `md_targets` and `anchor_rot` sections, plus `backlinks` provenance) are
**full-content coordinates** on the BOM-stripped raw file:

- `offset` is a **CHAR offset** (UTF-16 code units — not UTF-8 bytes; a `grep -b`
  comparison needs a byte↔char translation), anchored at the **markdown
  MARKER**: `[` for links, `!` for images/embeds — not at the URL text inside
  the parens or the target text. The documented exception: `taskSpans.offset`
  = start of the task TEXT (after the checkbox).
- `line` is the **raw-file line**, 1-based, frontmatter counted — it is not
  body-relative and not index-relative.
- `frontmatter` spans sit inside the same file frame; nothing needs offsetting
  to be addressed by a downstream patcher (line+offset address the exact
  marker in the raw note).
| `textSnippet` | FTS snippet (`==highlight==`) on `text:` hits — v6 indexes the RENDERED body (plain text; code content/labels kept, markup markers dropped) |

## Facets semantics

- Keys: `tags`, `folder`, `type`, `modified`, `vault`, or any property key —
  `text`, `body`, `filePath`, `links`, `backlinks` rejected
- Multi-value properties count **per element** (a note with 3 tags counts in
  all 3 buckets), so a bucket sum can exceed `resultCount`
- A missing property lands in the `(missing)` bucket; absent keys are never
  serialized, but the bucket appears for notes inside the result set
- `limit` = values per facet (default 10, max 100)
- **Parity invariant**: the facet WHERE fragment *is* the search WHERE
  fragment — for any exact-value query, the search hit count equals that
  value's facet bucket (list-form and scalar-form properties both match)
- Counts run on the load-time index, same staleness contract as search

## Query quick-sheet

```text
type:Recipe                     exact value (scalar or any list element)
tags:"Diary/Daily"              quoted values with slashes
NOT tags:Archive, -a            exclusions (absent rows match)
price:[1 TO 5] / {1 TO 5}       ranges, * = open bound
status:(todo OR done)           grouped values
text:"data races"               body full-text (FTS5, whole words, bm25 rank)
text:own*                       suffix wildcards only
modified:[2026-11-01 TO *]      recency
filePath:Notes/*                paths
```

Full grammar: [Querying](Querying.md).

## Caps and limits

| Surface | Cap |
|---|---|
| `facets` limit | 100 / facet |
| `search_and_read` | 20 files; 100k chars total budget |
| `binary_file` base64 | 5 MB (stat unbounded) |
| `tasks` / `orphans` limit | 500 |
| `backlinks` limit / context | 500 hits / 200 chars |
| `broken_links` limit / sources / instances | 500 targets / 10 sources listed / 50 span facts |
| `danglingLinks` | 50 per note |
| task extraction | 200 per note |

## Staleness

The index is a load-time snapshot: the vault files are the source of truth,
and every MCP tool (search, facets, graph reductions) reads the same
load-time index. Notes written since server start become visible on the
next server start — there is no reload and no write path over MCP.
