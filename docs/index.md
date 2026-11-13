---
title: PKSPKMS Documentation
---

> [!NOTE] This may or may not be up-to-date. I try but things get old and forgotten.

PKSPKMS is a single-user HTTP API and CLI for a Zettelkasten-style vault of Markdown notes (see [PKSPKMS](core/PKSPKMS.md)).

What you can do with it:

- [Query](core/Querying.md) your Markdown files by their properties (frontmatter + generated keys) — Lucene-style syntax, not full text search
- Serve your vault over HTTP with a browser UI
- Query the vault from AI clients over MCP (`POST /mcp`: `search`, `read_file`, `graph`, `manifest`, `resolve_wikilink`)
- Render [Obsidian](core/Obsidian.md) Bases blocks (` ```base ` / ` ```luabase `) as Markdown tables
- Export to Markdown or plain copies from the CLI
- [Virtual vaults](core/Virtual%20PKMS.md): mount other directories alongside your vault with `@alias/`-prefixed paths, plus OPML outlines and RSS/Atom/podcast feeds as read-only peers
- Optional `--enable-auth` bearer auth and `--cors-origin` allow-list for explicit cross-origin access (browser extensions)

The docs:

- [Install](core/Install.md)
- [PKSPKMS](core/PKSPKMS.md)
- [Querying](core/Querying.md)
- [Endpoints](core/Endpoints.md)
- [Security](core/Security.md)
- [Performance](core/Performance.md)
- [Markdown](core/Markdown.md)
- [Obsidian](core/Obsidian.md)
- [Lonely Vault Problem](core/Lonely%20Vault%20Problem.md)

Repo-level docs (not in Obsidian): [FEATURES](../FEATURES.md), [ARCHITECTURE](../ARCHITECTURE.md), [BUGS](../BUGS.md).

It is meant to be long-lived: start it when you start your computer and leave
it running. It indexes the vault at startup — there is no runtime reload. The
SQLite index persists across starts; unchanged files skip re-parsing, and
`--reset-db` forces a full rebuild.

Planned extensions (things that would use it):

- Static site generator (planned)
- Obsidian plugin (planned)
- Firefox plugin (planned)
- Android app (planned)
