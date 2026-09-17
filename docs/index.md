---
title: PKSPKMS Documentation
---

> [!NOTE] This may or may not be up-to-date. I try but things get old and forgotten.

PKSPKMS is a single-user HTTP API and CLI for a Zettelkasten-style vault of Markdown notes (see [PKSPKMS](core/PKSPKMS.md)).

What you can do with it:

- [Query](core/Querying.md) your Markdown files by their properties (frontmatter + generated keys) — Lucene-style syntax, not full text search
- Serve your vault over HTTP with a browser UI
- Render [Obsidian](core/Obsidian.md) Bases blocks (` ```base ` / ` ```luabase `) as Markdown tables
- Export to Markdown or plain copies from the CLI
- [Virtual vaults](core/Virtual%20PKMS.md): mount other directories alongside your vault with `@alias/`-prefixed paths

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

It is meant to be long-lived: start it when you start your computer and leave it running. It reloads changed files on each query run.

Planned extensions (things that would use it):

- Static site generator (planned)
- Obsidian plugin (planned)
- Firefox plugin (planned)
- Android app (planned)
