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


## OPML vaults

Mount an OPML file as a read-only peer vault:

```shell
pkspkms server --directory "/pkms/" --opml-vault "scy:/path/to/peer.opml"
```

node's own path (channel note + date-prefixed item notes). Mixed trees are
allowed. Outliner notes carry the OPML file's mtime, so unchanged files are
not re-parsed on the next load. Subscriptions fetch in parallel; a feed that
fails to fetch or parse is skipped with a warning — the rest of the vault
still mounts.

## Feed vaults

Mount a single syndication feed — RSS 2.0, Atom 1.0, or a podcast feed — by
URL or local file:

```shell
pkspkms server --directory "/pkms/" --feed-vault "corecursive:https://corecursive.com/rss"
pkspkms server --directory "/pkms/" --feed-vault "local:/path/to/feed.xml"
```

Each feed materializes as a channel note (with `rss:` frontmatter, like your
own channel bookmarks) plus one `YYYY-MM-DD Title.md` note per item. Podcast
episodes carry the enclosure as a `media:` property; audio itself is not
downloaded. Feeds are fetched once per server start and 301-style redirects
(http → https) are followed; a feed that fails to fetch or parse logs an
error and the vault is skipped — the server keeps running. These vaults are
read-only: the `/cache` endpoint copies items into your vault as usual.

> [!NOTE] These flags are separate from `--virtual-vault` because URLs contain
> colons, which collide with the `alias:/path` split.

Once loaded, the vault's files are stored in the database with `@alias/`-prefixed
paths (e.g. `@bob/git.md`), and wikilinks resolve across all loaded vaults by
file name and path. See [Querying](Querying.md) for searching across vaults.

> [!NOTE] Wikilink resolution across vaults currently has caveats — duplicate
> names and cross-vault collisions resolve nondeterministically (BUGS.md B16/B17).
