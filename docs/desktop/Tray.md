---
title: Tray
---

Start with `--tray` to show a system tray icon with a context menu:

- Status, port, vault path and the last log line
- **Open in Browser** — opens the web UI
- **Quit** — graceful shutdown

If the tray cannot be initialized (e.g. on a headless server), PKSPKMS logs a
warning and continues without it.
