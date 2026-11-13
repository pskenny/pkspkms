---
title: Export
---

Export runs from the CLI only:

```shell
pkspkms export --directory <vault> [--db file] --query "…" --output <dir>
               --type markdown|copy [--dryrun]
```

The query selects subjects (Lucene-style syntax, see [Querying](../core/Querying.md));
empty `query` matches every file. Querying matches virtual-vault files too —
note B12 below under virtual vaults works only when the file lives in the main
vault.

## `--type markdown`

- Text transclusion: `![[note#heading]]` embeds expand to the target's
  frontmatter-stripped content (heading-scoped by level); references to
  markdown notes resolve through the wikilink resolver
- Media embeds (`![[file.mp4]]`, case-insensitive) stay links — the file is
  copied byte-exactly into the output and the embed becomes `![](path)`
- Embeds appearing inside frontmatter become a bare YAML value (path only —
  `!` would start a YAML tag); text embeds above 1 MB inline cap become links
- Non-markdown subjects and linked non-markdown files are always copied
  byte-exactly, never run through the UTF-8 content pipeline
- Wikilinks resolve to `[display](path)` markdown links; `[[target|alias]]`
  emits the alias as display text; unresolvable links stay raw
- `%XX` sequences in link destinations decode before copying (`My%20Image.png`
  → `My Image.png`), while literal percent filenames (`50%.txt`) survive
- Linked markdown notes export themselves and are not duplicated by the copy
  pass

## `--type copy`

Raw byte copies of matched files plus their linked non-markdown files. No
content transformation at all.

## `--dryrun`

Full pass over matches and linked files, nothing written to disk.

## Failure semantics

One failing subject or linked file logs and is skipped — the rest of the
export continues. Existing output files are skipped without a content
comparison (stale-copy hazard).

## By design: virtual vaults are not export targets

Virtual-vault subjects (`@alias/…` files) are skipped with an explicit log
line instead of being run through the export pipeline — the content pipeline
only understands the main vault's filesystem. To ship a virtual-vault file,
materialize it into your vault with the [cache endpoint](../core/Endpoints.md#cacheaddresslocation)
(`directory: .pkspkms-cache`, designed for the planned Obsidian plugin) and
export it from the main vault.
