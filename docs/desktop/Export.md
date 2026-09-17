---
title: Export
---

Export runs from the CLI only:

```shell
pkspkms export --directory <vault> --query "…" --output <dir> --type markdown|copy [--dryrun]
```

- `--type markdown` — transcludes markdown embeds, copies media, resolves
  wikilinks (see FEATURES.md for the full behavior list)
- `--type copy` — raw copies of matched files plus linked non-markdown files
- `--dryrun` — full pass, nothing written

If a virtual vault is enabled and the cache endpoint is hit, a copy of the
virtual vault file is created in your vault. This endpoint is intended for the
PKSPKMS Obsidian plugin (planned).
