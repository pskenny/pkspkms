---
title: Obsidian
---

Obsidian is a local-first Markdown editor. PKSPKMS aims to read the same vault
you edit in Obsidian.

What PKSPKMS understands today:

- **Frontmatter properties** — parsed with the same value types Obsidian uses
  (`date`, `datetime`, `number`, `text`, `multitext`, `checkbox`); timestamps
  keep their original text
- **`.obsidian/types.json`** — declared property types drive query comparisons
  (see [Querying](Querying.md)); e.g. a property declared `number` matches ranges even when
  a note stores the value as text
- **Bases blocks** — ` ```base ` and ` ```luabase ` blocks render as Markdown
  tables; the filter vocabulary follows Obsidian Bases expressions
  (contains/containsAny/containsAll, startsWith, inFolder, isEmpty, comparisons,
  array equality, negation)
- **Tab-indented YAML** — tolerated where Obsidian tolerates it

Not yet shared with Obsidian:

- No Obsidian plugin exists yet (planned) — the cache endpoint and clickable
  `pkspkms://` links are designed for it but nothing consumes them yet
