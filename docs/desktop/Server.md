---
title: Server
---

```shell
pkspkms server --directory <vault> [--port 3000] [--db file] [--tray] [--virtual-vault a:/path]...
```

- Serves the HTTP API over NanoHTTPD (see [Endpoints](../core/Endpoints.md))
- Loads the vault at startup and reloads changed files on demand
- Default port is 3000; `--db` sets the SQLite file (default `pkspkms.db` in the
  working directory)
- `--virtual-vault` mounts extra directories (see [Virtual PKMS](../core/Virtual%20PKMS.md))
- With `--tray`, a system tray icon is added (see [Tray](Tray.md))
