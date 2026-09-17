---
title: Querying
---

PKSPKMS queries filter notes by their properties (YAML frontmatter plus generated
keys like `filePath`, `tags`, `links`). The syntax is Lucene-style: every
comparison is `field:value`, combined with flat boolean clauses.

## Syntax

| Form | Meaning |
|------|---------|
| `tags:PKSPKMS` | property `tags` equals `PKSPKMS` (scalar or any array element) |
| `filePath:*.md` | wildcards `*` and `?` (LIKE) |
| `tags:*` | property exists |
| `price:[1 TO 5]` | inclusive range; `{1 TO 5}` exclusive; `*` as an open bound |
| `field:(a OR b)` | grouped values over one field |
| `"multi word"` | quoted phrase (exact text) |
| bare word | substring search across every property value |
| `a AND b` | both must match (AND promotes both adjacent clauses) |
| `a b` | implicit OR — at least one |
| `NOT a`, `-a`, `!a` | must not match; files without the property match too |
| `+a` | must match |
| `(a b) AND c` | parenthesized sub-query |

Precedence follows classic Lucene's flat clause list: operators only affect
their adjacent clauses. `a AND b OR c` means a AND b, with c optional — use
parentheses for real grouping. Comparisons are case-sensitive; `LIKE`-style
wildcards are ASCII-case-insensitive. Malformed queries are rejected with a
`QueryParseException` (HTTP 400).

## CLI

```shell
pkspkms export --directory "/pkms/" --query "tags:lonelyvaultproblem" --type markdown --output out/
```

## HTTP

```shell
curl "http://localhost:3000/files/list?query=tags:PKSPKMS"
curl "http://localhost:3000/files/search?query=filePath:*.md%20NOT%20tags:Archive"
```

Empty or missing `query` matches every file.
