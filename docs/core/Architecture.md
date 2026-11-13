# Architecture

The load-bearing invariants, in one place. Each is pinned by test batteries;
when code breaks one, the rule is the thing to re-read — and update honestly
if the design moves.

## Canon vs. cache

Properties JSON is canon. Everything else is a cache or a derivation:

| Thing | Role | Refresher |
|---|---|---|
| `FILES.properties` JSON | **canon** — frontmatter + extracted link/task/alias structures | loader parse (`RepositoryFileLoader` → `updateFileProperties`) |
| `FILES.file_path` | row identity (also cached in the JSON's `filePath` key) | loader |
| `FILES.file_last_modified` | mtime cache (drives the diff) | loader; **migration nulls it** to force a one-shot reparse |
| `FILES.body` | FTS5 index feed (`''` = unindexed binary) | loader; `files_au`/`files_ai`/`files_ad` triggers sync `files_fts` |
| `modified` (ISO) in JSON | generated-key write of the mtime (recency queries ride the property machinery) | loader |

**Write path discipline**: the loader is the *only* write path into
FILES/LINKS/EMBEDS rows — file edits on disk re-enter through the same parse
pipeline as an initial scan. No raw SQL writers exist.

## Derived at query time (never stored)

| Key | Derived how | Cost note |
|---|---|---|
| `backlinks` | one batched LINKS pass per result page (512-id chunks) | removed the per-hit N+1 |
| `modified` | ISO of `file_last_modified` | free |
| `textSnippet` | FTS5 `snippet(...)` + bm25 ordering on `text:` probes | index-side |
| facets | the compiled WHERE fragment re-aimed at GROUP BY counts | same fragment as `search` — parity invariant |

## Interfaces

- **`PksFileRepository`** — composite of role interfaces (VaultLoader /
  VaultReader / VaultGraph / VaultMedia / VaultRegistry). Role methods are
  abstract; no default-throw stubs (semantic defaults only:
  `getPropertyTypes`, `blake3Of`, `searchRegular` streaming bridge,
  `loadDirectoryIntoRepository(EmbedMode)`, `processEmbeds` no-op).
- **`SqliteFiles`** — the FILES row SQL contract (loader-facing); `FilesDao`
  owns every statement. Repository-level code never touches `Connection` (P3).
- **`Server`** routes live in one dispatch table; the security gate order
  (host allow-list → CORS preflight → bearer auth) sits *outside* the table.
- **`McpDispatcher`** is transport-agnostic (JSON-RPC in/out): HTTP is one
  transport; a future stdio server feeds the same handler.
- **`LinkPlanner`** — the load-time wikilink resolution (merge, precedence
  path > filename-ext > name (B7 vault-scoped collision order; frontmatter
  aliases are data only — the alias resolution tier is retired), B6 vault
  scoping, B10 attribution,
  dangler caps) is a pure function: parsed files + indexes in, properties
  mutations out, no JDBC.

## Extraction engine (one spec parse, no regex gadgets)

`MarkdownExtractor` is the load-time extraction seam implemented by
`FlexmarkMarkdownExtractor`: **one flexmark 0.64.8 parse per note** produces
links/images, wikilinks (official `flexmark-ext-wikilink`), checkbox tasks
(official `flexmark-ext-gfm-tasklist`), base/luabase fenced YAML (fenced
interiors stay visible to the spec scanners — the worst-case of the
BlockMasker exemption preserved), heading `outline` (`[{text, level}]`), and
parse-time span facts: `wikilinkSpans` (target + line + char-offset; the
backlink provenance reads these — the ScanProvenance query-time re-scan is
retired) and `linkSpans` (markdown link/image destinations with the same
{kind, target, line, offset} shape; provenance for `links`, not backlinks).
Inline/fenced/indented code is opaque by construction — demo content can no
longer fake links, tasks, or outlines.

`LegacyMarkdownExtractor` (the old BlockMasker + regex stack) is the G1
parity oracle in tests; production code never touches it.

Whitelisted bug-fix classes from the G1 parity gate: inline/fenced/indented
code ghost links and tasks eliminated, titles no longer leak into link
destinations, blockquote/ordered task lists count, htmGrelref-style
shortcodes no longer splatter into the links list.

Accepted deviation catalogue (flex vs legacy, reviewed via the G1 diff):
angle-bracket (`<url>`) and quoted destinations extract nothing; reference
links (`[text][label]`) extract nothing; `mailto:`/bare autolinks are inert;
the flexmark wikilink ext refuses some legacy-regex shapes (`:space:`,
empty targets, unbalanced brackets) and drops them; result is fewer false
positives per note — the real-vault diff settled at 24/1791 notes with
diffs, all inside these classes.

## Known triggers

- Tool-count > 20 in `PkspkmsTools` → introduce a tool-spec DSL (schema-map
  boilerplate is the current threshold).
- Vault > ~10k files → revisit unbounded `search` payload materialization
  (`sort`/`limit` already exist; add `offset`-keyed cursor if needed).
