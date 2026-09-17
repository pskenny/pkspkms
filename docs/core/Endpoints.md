---
title: Endpoints
---

All endpoints are GET-only. The query syntax is Lucene-style property queries —
see [Querying](Querying.md). Malformed queries return HTTP 400 with a JSON error body.

## `/ping`

Liveness check. Returns `200` with an empty body.

## `/files/list` and `/files/search`

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

`/files/search` is an alias of `/files/list`.

## `/files/list/graph`

Same as `/files/list` but each file is reduced to `filePath`, `links`,
`backlinks` and `tags` — shaped for graph drawing.

## `/cache/{address}/{location}`

Pulls a file from a virtual vault into a cache directory. If not present it
copies the file into the directory in the same `{address}/{location}` format
and stores a BLAKE3 hash.

| Parameter   | Comment                            |
|-------------|------------------------------------|
| `directory` | Directory to cache into            |
| `address`   | the alias of a virtual vault       |
| `location`  | file location in the virtual vault |

> [!NOTE] Path traversal is rejected: locations are contained inside the
> virtual vault and the cache directory inside the vault (BUGS.md B1, fixed).

## `/openapi.json`

OpenAPI 3.0 spec for the API. The `servers` URL is rewritten to the running
port, so Swagger UI *Try it out* always targets the live server.

## `/webui/swagger`

Swagger UI rendering `/openapi.json` (Try it out enabled).

## `/webui`

Query your vault in the browser: query editor with syntax highlighting,
saved queries and dark mode.
