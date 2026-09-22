# Architecture

Bugs are tracked in [BUGS.md](BUGS.md).

## Modules

- **`pkspkms-core`** — library: domain objects, parsers, query engine, repository, HTTP server, Bases rendering
- **`pkspkms-desktop`** — executable: CLI (`Application`), system tray (`TrayManager`, `TrayFactory`), and 
  `SQLiteLuaConnector`

`SQLiteLuaConnector` lives in desktop, not core: it registers the `lua_eval` SQLite function via `org.sqlite.Function`, 
and core declares `sqlite-jdbc` test-scoped so an Android app can consume core with its own driver (reference module: 
`docs/android/`). Core accepts a `Consumer<Connection>` registrar instead — without one, Lua-backed Bases queries are 
skipped. `TrayFactory` builds the tray on a worker thread with a 5-second timeout: tray initialization can block 
indefinitely (dorkbox desktop-detection subprocess), so a stalled tray degrades the app headless instead of hanging 
startup. `io/fs` is the filesystem plumbing (disk, plus `SynthesizedFileSystem` for read-only in-memory vaults); 
`io/feed` mounts OPML outlines and RSS/Atom/podcast feeds as virtual vaults — fetched once at server start, 
per-vault failures log and skip.

## Load pipeline

```
Application (CLI)
  └─ SQLitePksFileRepository
       ├─ reads .obsidian/types.json -> PropertyTypes (type-aware query comparisons; absent -> heuristic)
       └─ RepositoryFileLoader.load()
            ├─ 1. walk vault (.obsidian .trash always; .obsidian/app.json userIgnoreFilters)
            ├─ 2. read FILES (path, mtime) from SQLite
            ├─ 3. diff -> new/modified vs unchanged
            ├─ 4. delete removed files' rows
            ├─ 5. parse changed files IN PARALLEL (pool: 2x cores, max 16)
            │      .md   -> MarkdownProcessor (frontmatter, wikilinks, links, bases)
            │      other -> Blake3 stream-hash only (binaries never materialize)
            └─ 6. serial on one connection:
                   insertFiles -> resolveWikilinks -> updatePropertiesAndLinks
       └─ SqliteEmbedProcessor.processAll()   (render ```base / ```luabase embeds — once, after every vault mounts;
                                                 corpus single-pass: loadCorpus → processOverCorpus, one JSON parse per file per render pass)
```

Everything touching SQLite is serial on a single JDBC connection (SQLite is single-writer, even in WAL). Only parse/hash runs in parallel — it has no shared mutable state. The DB is a disposable cache today: tables are dropped and recreated on every repository construction (BUGS.md B8), so every load is a full rescan. Embed rendering is global across vaults: vault mounts only seed EMBEDS rows, and `processEmbeds()` runs once after the full mount sequence so a luabase always filters over the complete corpus.

## Query language -> SQL

The query language (`repo/query/`) is Lucene-classic-style property queries:
`field:value`, wildcards `* ?`, ranges `[1 TO 5]`, `field:*` exists, phrases,
grouped values `field:(a OR b)`, bare terms as all-property substring search,
flat boolean clauses (`AND`/`OR`/`NOT`/`+`/`-`), parenthesized sub-queries.

```
query string
  └─ QueryParser.parse()          -> Query (flat clause list: MUST / SHOULD / MUST_NOT)
       └─ QueryCompiler.compile() -> CompiledQuery (parameterized SQL + bound params)
            └─ SQLitePksFileRepository.searchRegular(CompiledQuery)
                 PreparedStatement over FILES, per row: JSON -> Map -> PksFile
```

- **Fully parameterized**: keys are quoted JSON paths (`$."my-key"`) and values are
  bound via `PreparedStatement` — no string interpolation
- **PropertyTypes**: comparisons are compiled per the declared Obsidian type —
  `number` casts both sides to REAL (string-stored values match numeric ranges),
  `date`/`text` compare lexicographically; undeclared fields keep the legacy
  shape-sniffed compare
- **Malformed input** raises `QueryParseException` (offset + message) — the HTTP
  layer maps it to HTTP 400; the old silent `1=1` fallback is gone

Full language spec: [docs/core/Querying.md](docs/core/Querying.md).

## Bases -> Lua pipeline

```
```base / ```luabase block in a note
  └─ MarkdownProcessor extracts YAML text
       └─ NaiveBaseToLuaBaseConverter.convertToMap()
            ├─ tab-indented YAML normalized before parsing (leading whitespace run)
            ├─ top-level filters inherited by views lacking them
            └─ Obsidian expressions -> Lua calls (contains -> hasPropertyContaining/
               startsWith/inFolder/isEmpty/containsAll/array equality/numeric
               comparisons)
                 └─ LuaBaseProcessor
                      ├─ filter: lua_eval(?, properties) per SQLite row, or in-memory
                      ├─ sort: type-tolerant comparator (nulls last)
                      └─ render: Table or List (cards/map degrade to table)
```

Shared Lua helpers live in `resources/luabase/functions.lua` — plain `.contains`
is substring semantics (`hasPropertyContaining`); list types use exact membership
(`hasPropertyValueIn`/`hasPropertyContainingAll`). Tab-indented YAML is
normalized (leading whitespace run, 1 tab = 4 spaces) before parsing — on both
the `YamlParser` (frontmatter) and converter paths.

## Data model (SQLite)

| Table | Purpose |
|-------|---------|
| `FILES` | path, name, ext, properties JSON, blake3, vault_alias_id |
| `LINKS` | source_file_id, target_file_id, link_type |
| `EMBEDS` | original_match, type, generated_content |
| `VAULT_ALIASES` | alias, directory, is_virtual |

Virtual vault rows are prefixed `@alias/`.

`/ping`, `/config`, `/files/list`, `/files/list/graph`, `/files/manifest`, `/cache/{address}/{location}`,
`/webui/*`, `/openapi.json`. Binds 127.0.0.1 by default (`--bind` opt-out). Bearer-token auth on every
route except `/ping` and `/webui/*` (constant-time compare; a fresh SecureRandom token is generated
at every start and written 0600 to `~/.config/pkspkms/token`). Host-header allow-list (DNS-rebinding
defense); no CORS; `/cache` is POST-only. Webui responses carry CSP
(`default-src 'self'; script-src 'self'; frame-ancestors 'none'`), `X-Frame-Options: DENY`,
`nosniff`, `Referrer-Policy: no-referrer`; the UI script is served as `/webui/app.js`
(`script-src 'self'` forbids inline). Access logs record paths only. Responses stream JSON through
a piped thread; malformed queries return HTTP 400 with a JSON error body. Token bootstrap:
`Application.resolveToken`.

## Known layering violations (tracked in BUGS.md P3)

- `io/JsonUtil` imports `repo` — utility reaches up into the repository layer
- `io.processor.markdown.YamlFrontmatterReader` depends on `luabase.YamlParser`
- Services (`WikilinkService`/`WikilinkFinder`) hand-roll SQL against `FILES`; the repo leaks its raw `Connection`
- Circular package dependency `repo <-> luabase`
- `SqliteRepository.getConnection()` exposes the single shared connection
