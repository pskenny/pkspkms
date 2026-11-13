---
title: Endpoints
---

All endpoints are GET-only except the POST-only `/mcp` and `/cache/{address}/{location}`. The query syntax
is Lucene-style property queries — see [Querying](Querying.md). Malformed queries return HTTP 400 with a
JSON error body.

## Authentication

Auth is **opt-in** (`--enable-auth`). When it is on, every route except
`/ping` and the `/webui` pages requires a bearer token:

```shell
curl -H "Authorization: Bearer $(cat ~/.config/pkspkms/token)" "http://localhost:3000/files/list"
```

A fresh random token is generated at every server start and written 0600 to
`~/.config/pkspkms/token` (`--token`/`--token-file` override). Missing or
invalid tokens answer `401` with a JSON error body. When auth is off no
header is needed, nothing is written to disk, and the protections are the
loopback-only bind plus the Host allow-list. **CORS:** cross-origin reads
(a browser extension) require a `-- Cors-origin` allow-list entry — see
[Security](Security.md). **Webui gap:** the browser
UI itself cannot authenticate — its search calls carry no token; use the
bearer header or Swagger UI until that flow ships.

## `/ping`

Liveness check. Returns `200` with an empty body.

## `/files/list`

Query the vault. `?query=` filters by properties; an empty or missing `query`
matches every file. Responses stream JSON:

```json
{
  "files": [
    {
      "filePath": "Notes/PKSPKMS.md",
      "tags": ["Meta", "PKSPKMS"],
      "...any frontmatter keys": "..."
    }
  ],
  "resultSize": 1
}
```

## `/files/list/graph`

Same as `/files/list` but each file is reduced to `filePath`, `links`,
`backlinks` and `tags` — shaped for graph drawing.

## `/files/manifest`

Groups `{filePath, blake3}` pairs per virtual-vault alias — the
autocomplete/hydration source for `pkspkms://` targets (Obsidian plugin).
Main-vault rows are excluded: `/cache` only serves registered aliases.

## `/cache/{address}/{location}`

Pulls a file from a virtual vault into a cache directory. If not present it
copies the file into the directory in the same `{address}/{location}` format
and stores a BLAKE3 hash.

| Parameter   | Comment                            |
|-------------|------------------------------------|
| `directory` | Directory to cache into            |
| `address`   | the alias of a virtual vault       |
| `location`  | file location in the virtual vault |

POSTs must also carry the header `X-Pkspkms-Cache-Request: 1` — HTML form
POSTs cannot set custom headers and browser JS needs a preflight (answered
only for `--cors-origin` allow-list entries), so this blocks cross-site
drive-by submissions; non-browser clients (plugin, mobile, scripts) just
include it.

> [!NOTE] Path traversal is rejected: locations are contained inside the
> virtual vault and the cache directory inside the vault; rejected paths
> never create directories on disk (pinned by `CacheEndpointTest`). The
> alias must be a registered virtual vault — even when a same-named path
> already exists in the vault (H7 pin: `testCacheUnknownAliasRejectedEvenWhenFileExists`).

## `/mcp`

POST-only [Model Context Protocol](https://modelcontextprotocol.io) endpoint for AI clients (OpenCode,
Claude Code, Cline, ...). One JSON-RPC 2.0 request or notification per POST; requests answer with a single
JSON object (no SSE stream), notifications answer `202 Accepted` with no body, and `GET` answers `405`.
When `--enable-auth` is on, `POST /mcp` requires the bearer token like every other route (see
[Authentication](#authentication)), and the Host allow-list serves as the DNS-rebinding
defense MCP requires. CORS `OPTIONS` preflights are answered for registered
`--cors-origin` allow-list entries without the token (the CORS spec requires
it); the actual `POST` keeps the auth gate.

Tools exposed:

| Tool | Description |
|------|-------------|
| `search` | Lucene-style property query → `{files, resultSize, totalSize}` (`text:` FTS, `sort`/`limit`/`offset`) |
| `read_file` | Raw note markdown, vault-relative or `@alias/`-prefixed; optional head caps |
| `graph` | Search reduced to `filePath`, `links`, `backlinks`, `tags` |
| `manifest` | Per-virtual-vault `{filePath, blake3}` pairs |
| `resolve_wikilink` | Wikilink target → vault path (path, filename.ext, or filename) |
| `facets` | Value counts over a filtered result set (drill-down) |
| `related` / `orphans` / `graph_hops` | One-hop neighborhood; no-edge notes; 1–2-hop link walks |
| `backlinks` / `broken_links` | Inbound-edge provenance report with `{source, line, offset}` span facts; refcount-ranked dangling targets with per-occurrence instances |
| `tasks` | Open checkbox tasks, optional scope query |
| `search_and_read` / `recent` | Search + capped heads in one call; newest first |
| `binary_file` | stat / bounded-Base64 reads |

Full reference incl. generated keys, facets semantics and caps:
[docs/core/MCP.md](MCP.md).

Dispatch/security-gate order lives in [Architecture](Architecture.md).
| `orphans` | Notes with no link edges in either direction |
| `tasks` | Open checkbox tasks (`- [ ]`) as a flat list, optional scope query |

`read_file` rejects path traversal, `.obsidian`/`.trash` and unknown aliases. The dispatcher
(`io.pskenny.pkspkms.mcp.McpDispatcher`) is transport-agnostic: a future stdio transport would feed it
stdin lines instead of HTTP bodies.

## `/openapi.json`

OpenAPI 3.0 spec for the API. The `servers` URL is rewritten to the running
port, so Swagger UI *Try it out* always targets the live server.

## `/webui/swagger`

Swagger UI rendering `/openapi.json` (Try it out enabled).

## `/webui`

Query your vault in the browser: query editor with syntax highlighting,
saved queries and dark mode. `/webui` redirects to `/webui/index.html`; the
page is served with `/webui/app.js` (external script — CSP `script-src 'self'`
forbids inline) and `/webui/pk.png`. Responses carry
`Content-Security-Policy`, `X-Frame-Options: DENY`, `nosniff` and
`Referrer-Policy: no-referrer`. See [Authentication](#authentication) for the
webui token gap.
