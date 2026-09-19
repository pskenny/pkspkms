# FEATURES

What PKSPKMS does. Architecture: ARCHITECTURE.md · Known bugs: BUGS.md

## Vault loading
- Single-directory vault; all file types tracked; only `.md` parsed in depth
- Incremental: mtime diff, changed files only; parallel parsing (2x cores, max 16)
- Blake3 content hashes — binaries are stream-hashed, never materialized in memory
- Virtual vaults: `--virtual-vault alias:/path` mounts extra directories as `@alias/`-prefixed paths
- OPML vaults: `--opml-vault alias:file.opml` — outliner notes per node, `xmlUrl` outlines mounted as fetched feeds (mixed trees allowed)
- Feed vaults: `--feed-vault alias:<url-or-file>` — RSS 2.0, Atom 1.0 and podcast feeds as read-only vaults (channel note + date-prefixed item notes); items carry tags/seconds/episode/season/image/comments/guid, channels language/modified/tags/website/image, audio-video enclosures embed as `![]()`; entries group by alias and failures degrade per source
- Obsidian compatibility: `.obsidian/types.json` property types drive comparisons;
  tab-indented YAML tolerated
- Obsidian Excluded files honored: `.obsidian/app.json` `userIgnoreFilters` drive
  exclusions (folders, paths, `*` wildcards); `.obsidian`/`.trash` always skipped

## Query language
Lucene-style property queries — `field:value`, wildcards, ranges, phrases, boolean
clauses, bare-word all-property search, typed comparisons via types.json.
Full syntax: [docs/core/Querying.md](docs/core/Querying.md)

## HTTP server
- `GET /ping` — liveness
- `GET /files/list?query=…` / `/files/search` — property queries, JSON streamed
- `GET /files/list/graph?query=…` — reduced to filePath/links/backlinks/tags
- `GET /cache/{address}/{location}` — pull a file into the cache from a virtual vault
- Web UI: query editor with syntax highlighting, saved queries, dark mode

## Bases rendering
- ` ```base ` / ` ```luabase ` blocks render as Markdown tables or lists
- Obsidian Bases filter vocabulary: contains/containsAny/containsAll, startsWith,
  inFolder, isEmpty, numeric comparisons (typed via types.json), array equality, negation
- Top-level `filters:` shared across views; `cards`/`map` views degrade to tables
- Sort with type-tolerant comparator (nulls last)

## Export
- `export --type markdown`: text transclusion for markdown embeds (1 MB inline cap);
  media embeds → file copied + `![](path)` link; embeds inside frontmatter → bare
  YAML value; linked non-markdown files copied; `%XX` link destinations decoded;
  alias wikilinks resolve with alias as display text; one failing note skips, not aborts
- `--type copy`: raw copies of matched files plus their linked non-markdown files
- `--dryrun`: full pass, nothing written

## Storage
- SQLite (WAL): FILES (properties as JSON), LINKS, EMBEDS, VAULT_ALIASES
- Backlinks derived at query time from LINKS (resolved wikilinks + markdown links); served on /files/list and /files/list/graph
- Expression indexes on tags/type; Blake3 column for change detection
- Tables rebuilt from the vault each run (see BUGS.md B8)

## CLI
- `pkspkms server --directory <vault> [--port 3000] [--db file] [--tray] [--virtual-vault a:/path]…`
- `pkspkms export --directory <vault> --query "…" --output <dir> --type markdown|copy [--dryrun]`
- System tray (desktop environments): status, open-in-browser, quit; degrades gracefully headless
- Logging: SLF4J/simple, per-package debug via `-Dorg.slf4j.simpleLogger.log.io.pskenny.pkspkms=debug`
