---
title: Virtual Vaults
tags:
  - lonelyvaultproblem
---

I want to be able to write and click a like such as `pkspkms://@bob/git.md` to refer to another persons vault. It 
would be even more useful if when I clicked it in Obsidian, it opened! 

We can load the same directory as your Obsidian vault to PKSPKMS. PKSPKMS loads the directory given at startup as your "main" PKMS. You can also add "virtual vaults" with the CLI
command `--virtual-vault "alias:/path/"`.

```shell
# Example
pkspkms server --directory "/home/you/pkms/" --virtual-vault "bob:/home/you/git/bob-knowledge" --port 9776
```

It loads the virtual vault after your vault (defined in the `--directory` flag). So now it has your PKMS mapped and 
stored in the database and the virtual vault files stored and mapped in the database. 

This allows for resolving (by creating the file in your own PKMS/vault)

> [!NOTE] The directory which you point to cache the PKSPKMS files should be ignored from indexing


Once loaded, the vault's files are stored in the database with `@alias/`-prefixed
paths (e.g. `@bob/git.md`), and wikilinks resolve across all loaded vaults by
file name and path. See [Querying](Querying.md) for searching across vaults.

> [!NOTE] Wikilink resolution across vaults currently has caveats — duplicate
> names and cross-vault collisions resolve nondeterministically (BUGS.md B16/B17).
