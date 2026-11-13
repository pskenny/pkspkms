---
title: Server
---

```shell
pkspkms server --directory <vault> [--port 3000] [--db file] [--bind 127.0.0.1]
               [--enable-auth [--token s | --token-file f]] [--cors-origin o | '*']
               [--tray] [--reset-db] [--virtual-vault a:/path]...
               [--opml-vault a:file.opml]... [--feed-vault a:<url-or-file>]...
```

- Serves the HTTP API over NanoHTTPD (see [Endpoints](../core/Endpoints.md))
- Indexes the vault at startup — there is no reload after that. The SQLite
  index persists across starts (versioned schema; legacy DBs are adopted);
  `--reset-db` drops and rebuilds it, forcing a full rescan
- Default port is 3000; `--db` sets the SQLite file (default `pkspkms.db` in
  the working directory)
- Binds `127.0.0.1` by default; `--bind` opts out (keep `--enable-auth` on then)
- Auth: **opt-in** — `--enable-auth` writes a fresh random token 0600 to
  `~/.config/pkspkms/token` each start; `--token` overrides (argv leaks via
  `ps` — prefer reading the file), `--token-file` picks where the fresh
  token is written (see [Security](../core/Security.md))
- Cross-origin: **closed by default** — `--cors-origin <o>` allow-lists a
  remote origin (browser extension; repeatable), `*` requires
  `--enable-auth`
- MCP surface is **read-only** — the vault is never mutated over the network;
  edits happen in your editor and the index refreshes on the next
  server start
- `--virtual-vault` mounts extra directories, `--opml-vault` and
  `--feed-vault` mount read-only syndication vaults (see
  [Virtual PKMS](../core/Virtual%20PKMS.md))
- With `--tray`, a system tray icon is added (see [Tray](Tray.md))
