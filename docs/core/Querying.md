---
title: Querying
---

PKSPKMS queries filter notes by their properties (YAML frontmatter plus generated
keys like `filePath`, `tags`, `links`). The syntax is Lucene-style: every
comparison is `field:value`, combined with flat boolean clauses.

## Syntax

| Form                | Meaning                                                        |
|---------------------|----------------------------------------------------------------|
| `tags:PKSPKMS`      | property `tags` equals `PKSPKMS` (scalar or any array element) |
| `filePath:*.md`     | wildcards `*` and `?` (LIKE)                                   |
| `tags:*`            | property exists                                                |
| `price:[1 TO 5]`    | inclusive range; `{1 TO 5}` exclusive; `*` as an open bound    |
| `field:(a OR b)`    | grouped values over one field                                  |
| `"multi word"`      | quoted phrase (exact text)                                     |
| bare word           | substring search across every property value                   |
| `a AND b`           | both must match (AND promotes both adjacent clauses)           |
| `a b`               | implicit OR — at least one                                     |
| `NOT a`, `-a`, `!a` | must not match; files without the property match too           |
| `+a`                | must match                                                     |
| `(a b) AND c`       | parenthesized sub-query                                        |

### Generated keys

Alongside frontmatter, every search hit carries derived properties:

| Key | Meaning |
|-----|---------|
| `filePath` | the vault-relative path (or `@alias/…` for virtual vaults) |
| `links` | resolved outgoing link targets (markdown links + wikilinks). Under the flexmark engine, angle-bracket/quoted-destination forms and `[text][label]` reference links extract nothing (documented gaps) |
| `backlinks` | incoming link targets, derived at query time |
| `aliases` | frontmatter alias texts — `aliases:Finances` finds the note (informational data only; note identity resolution uses paths/filenames, not aliases) |
| `modified` | the file's own mtime as ISO-8601 (`2026-11-06T09:41:00Z`) — recency narrowing works as a plain range: `modified:[2026-11-01 TO *]` |
| `tasks`, `tasksDone` | checkbox tasks extracted from the body: open `- [ ]` lines feed `tasks`, checked `[x]`/`[X]` lines feed `tasksDone` (fenced demo checkboxes are excluded; keys are absent when the note has none) |
| `danglingLinks` | unresolved wikilink targets (display-aliases and headings stripped), capped 50/note — `danglingLinks:*` is the link-hygiene report |
| `outline` | `[{text, level}]` heading entries in document order (`level` is the ATX depth) — heading-aware tooling and `#heading` validation ride this; frontmatter never leaks into it |
| `wikilinkSpans` | parse-time facts per wikilink: `[{kind:"wikilink", target, line, offset}]` (1-based line, char offset into the raw file, first-`]]`-wins inner text; v5 adds `anchor` and `display` as separate facts when present; v7 adds `kind:"frontmatter"` entries for wikilink-shaped frontmatter values, which resolve/dangle like body links) — the backlink-provenance backing; inline/fenced code spans excluded by construction |
| `linkSpans` | same parse-fact shape for markdown links and images: `[{kind:"link"/"image", target, line, offset}]` — destinations as written in the file (no resolution); inline/fenced code spans excluded by construction |

Examples:

```shell
notes changed this November        → modified:[2026-11-01 TO *]
with open checkbox tasks, scoped   → tasks:* (see the MCP tasks tool)
completed work                     → tasksDone:*
```

Precedence follows classic Lucene's flat clause list: operators only affect
their adjacent clauses. `a AND b OR c` means a AND b, with c optional — use
parentheses for real grouping. Comparisons are case-sensitive; `LIKE`-style
wildcards are ASCII-case-insensitive. Malformed queries are rejected with a
`QueryParseException` (HTTP 400).

Escape specials with a backslash — `my\:key:x` treats the whole
`my:key` as a field name. `%`, `_` and `%XX` sequences are literal in
values (filename-safe by default). Fuzzy
search (`term~2`) is explicitly rejected with 400 — escape as `\~` for a
literal tilde.

## Text (full-text) search

`text:` queries run against note **bodies** through SQLite FTS5, side-by-side
with property queries in the same Lucene-style syntax. v6 indexes the
**rendered** body (plain text produced from the raw markdown): heading,
emphasis, fence and blockquote markers no longer pollute hits, while
code-block content, link/wikilink label text, and table cells stay searchable.

```shell
# notes whose body mentions a word (whole-word tokens)
curl -H "Authorization: Bearer $(cat ~/.config/pkspkms/token)" \
  "http://localhost:3000/files/list?query=text:ownership"

# compose with property clauses
"tags:rust AND text:ownership"
"NOT text:archive AND filePath:Notes/*"

# phrases (quoted) and suffix wildcards
"text:\"data races\""      → tokens in order
"text:own*"                → prefix match (suffix wildcards only)
```

Semantics:

- Bodies are the raw markdown minus frontmatter; binaries and media cache
  rows are never indexed (they hold no text)
- The `unicode61` tokenizer matches whole words only — `own` does not hit
  `ownership` (use `own*` for prefix matching); trigram substring-mode is a
  possible future variant
- `text:` composes with every clause type: MUST/SHOULD/MUST_NOT, parenthesized
  groups, property probes — the FTS condition is just another well-typed
  probe in the compiled query
- Matches are ordered by FTS `bm25` relevance and each hit carries a
  `textSnippet` (highlighted with `==…==`) as a generated key
- Indexing happens at load time like every other property — edits show up on
  the next server start (or `POST /reload` when that lands)
- Ranges (`[x TO y]`) are rejected on `text:`; interior wildcards (`ru*st`)
  are rejected too — suffix `*` only

`text:*` = "has body text" (matches every markdown note; excludes binaries).

## Facet parity and multi-value counts

`facets` reuses the *same compiled WHERE fragment* as `search`, so any exact-
value query yields a hit count equal to that value's facet bucket — scalar
`type: Recipe` and list-form `type: [Recipe]` both match (dual probe), on
this server and every future one. Multi-value buckets count **per element**:
a note with `tags: [Book, Film]` counts in both buckets, so a bucket sum can
exceed `resultCount`. A property absent from a matching note lands in the
`(missing)` bucket. Buckets are capped by `limit` (default 10, max 100).

## CLI

```shell
pkspkms export --directory "/pkms/" --query "tags:lonelyvaultproblem" --type markdown --output out/
```

## HTTP

```shell
curl "http://localhost:3000/files/list?query=tags:PKSPKMS"
curl -H "Authorization: Bearer $(cat ~/.config/pkspkms/token)" "http://localhost:3000/files/list?query=filePath:*.md%20NOT%20tags:Archive"
```

Empty or missing `query` matches every file.
