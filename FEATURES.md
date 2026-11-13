# FEATURES

What PKSPKMS does. Architecture: ARCHITECTURE.md · Known bugs: BUGS.md

## Vault loading
- Single-directory vault; all file types tracked; only `.md` parsed in depth
- Incremental: mtime diff, changed files only; parallel parsing (2x cores, max 16)
- Blake3 content hashes — binaries are stream-hashed, never materialized in memory
- Virtual vaults: `--virtual-vault alias:/path` mounts extra directories as `@alias/`-prefixed paths
- OPML vaults: `--opml-vault alias:file.opml` — outliner notes per node, `xmlUrl` outlines mounted as fetched feeds (mixed trees allowed)
- Feed vaults: `--feed-vault alias:<url-or-file>` — RSS 2.0, Atom 1.0 and podcast feeds as read-only vaults (channel note + date-prefixed item notes); items carry tags/seconds/episode/season/image/comments/guid, channels language/modified/tags/website/image, audio-video enclosures embed as `![]()` and YouTube item links embed a normalized player; entries group by alias and failures degrade per source; per-host adaptive backoff honors Retry-After (2 retries) so subscription bursts aren't rate limited
- Obsidian compatibility: `.obsidian/types.json` property types drive comparisons;
  tab-indented YAML tolerated
- Obsidian Excluded files honored: `.obsidian/app.json` `userIgnoreFilters` drive
  exclusions (folders, paths, `*` wildcards); `.obsidian`/`.trash` always skipped

## Query language
Lucene-style property queries — `field:value`, wildcards, ranges, phrases, boolean
clauses, bare-word all-property search, typed comparisons via types.json.
Search hits carry the generated keys `filePath`/`links`/`backlinks`/`modified`
(ISO mtime, so recency ranges work) and `tasks`/`tasksDone` (checkbox lines),
plus `outline` (headings) and `wikilinkSpans` (parse-time `[[…]]` facts).
Full syntax: [docs/core/Querying.md](docs/core/Querying.md)

## HTTP server
- Loopback-only bind by default (`--bind` opt-out); **bearer-token auth is
  opt-in via `--enable-auth`**; CORS default closed with `--cors-origin`
  allow-lists
- Body-text full-text search via `text:` (FTS5, ranked) rides the same
  routes/auth/CORS as property queries; **bearer-token auth is
  opt-in via `--enable-auth`** — when on, every route except `/ping`
  and the `/webui` pages requires it (a fresh random token per start,
  written 0600 to `~/.config/pkspkms/token`; `--token` overrides,
  `--token-file` picks the location; both require the flag); when off, no
  Authorization is checked and nothing is written to disk
- CORS: default closed (same B3 posture). `--cors-origin <o>` repeatable
  allow-list grants that origin's pages API access (still auth-gated when
  `--enable-auth` is on); `*` emits the wildcard grant but requires auth;
  preflight `OPTIONS` answers without the token (CORS spec), the data request
  keeps the gate; no allow-list value ever echoes a header-injection payload
- `GET /ping` — liveness
- `GET /files/list?query=…` — property queries, JSON streamed
- `GET /files/list/graph?query=…` — reduced to filePath/links/backlinks/tags
- `GET /files/manifest` — per-virtual-vault `{filePath, blake3}` pairs
- `POST /cache/{address}/{location}?directory=…` — pull a file into the cache
  from a virtual vault (POST only — drive-by GETs can't mutate the vault)
- `GET /openapi.json` + `/webui/swagger` — OpenAPI 3.0 spec and Swagger UI
- `POST /mcp` — MCP (Model Context Protocol) endpoint for AI clients:
  `search` (with `text:` full-text and the `modified` ISO timestamp),
  `read_file`, `graph`, `manifest`, `resolve_wikilink` plus read-only
  drill-down tools — `facets` (value counts over a filtered result set),
  `related` (one-hop link neighborhood), `orphans` (no-edge notes), `tasks`
  (open checkboxes, `tasksDone` queryable); JSON responses, no SSE stream, no
  sessions; behind the bearer token
- Host-header allow-list (DNS-rebinding defense); no CORS
- Web UI: query editor with syntax highlighting, saved queries, dark mode;
  CSP + `X-Frame-Options: DENY` + `nosniff` on webui responses; access logs
  record paths only

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
- SQLite (WAL, slim v3): FILES (properties as JSON + body for FTS), LINKS, EMBEDS, VAULT_ALIASES, files_fts (FTS5 external-content full-text index over note bodies)
- Backlinks derived at query time from LINKS (resolved wikilinks + markdown links); served on /files/list and /files/list/graph
- Expression indexes on tags/type; Blake3 column for change detection; FTS5 `unicode61` full-text with `text:` queries + bm25 rank + snippets
- Versioned schema: tables persist across runs (`META.schema_version`); a
  legacy pre-versioning DB is adopted (v1 → v2 dead columns dropped), an
  unknown newer version is rejected with a clear message, and `--reset-db`
  drops and rebuilds the index (B1)

## CLI
- `pkspkms server --directory <vault> [--port 3000] [--db file] [--tray] [--bind 127.0.0.1] [--enable-auth [--token s | --token-file f]] [--cors-origin o | '*.'] [--virtual-vault a:/path]… [--opml-vault a:file.opml]… [--feed-vault a:<url-or-file>]…`
- `pkspkms export --directory <vault> [--db file] --query "…" --output <dir> --type markdown|copy [--dryrun]`
- `pkspkms add directory --directory d --alias a` — register a virtual vault in
  an existing DB (persists across runs; duplicate aliases are rejected)
- System tray (desktop environments): status, open-in-browser, quit; degrades gracefully headless
- Logging: SLF4J/simple, per-package debug via `-Dorg.slf4j.simpleLogger.log.io.pskenny.pkspkms=debug`; access logs record paths only
