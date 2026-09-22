---
title: Security
---

PKSPKMS is a single-user, local tool. Current posture (post-hardening 1):

**In force:**

- Loopback-only bind: the server accepts connections from 127.0.0.1 unless
  `--bind` says otherwise — LAN devices cannot reach it
- Lua sandbox: note-controlled expressions run in an allow-list environment
  (base/package/string/table/math only); `os`, `io`, `luajava`, `load`,
  `loadstring`, `dofile`, `require` are denied — hostile notes fail in-sandbox
- Token auth: a random token is generated at startup and written 0600 to
  `~/.config/pkspkms/token`; every route except /ping requires it
  (`--token`/`--token-file` override). The webui accepts a one-time
  `?token=` link and stores it in an HttpOnly cookie
- File hygiene: the SQLite DB (and WAL/-shm sidecars), the token file, and
  access logs are owner-only (0600)
- `/cache` traversal: rejected — locations are contained inside the virtual
  vault and the cache directory inside the vault (BUGS.md B1)
- Drive-by resistance: `/cache` is POST-only, and a `Host`-header allow-list
  rejects DNS-rebinding requests
- Browser hardening: CSP `default-src 'self'; script-src 'self';
  frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`,
  `Referrer-Policy: no-referrer` on webui responses
- Log hygiene: access logs record paths only, never query strings

**Residual risks (documented, accepted):**

- A note's Lua can still loop forever — one stalled filter stalls one request
  thread (expression length capped at 4 KB as a cheap partial guard)
- No TLS: loopback traffic never touches a wire; expose beyond localhost only
  through a reverse proxy with real certs
- Programs running as your own user can read the database and vault directly —
  HTTP-layer security cannot help there; that class is OS-level (disk
  encryption, sandboxing untrusted software)
- Employer policy for work machines (personal tools, cloud sync) is outside
  the program's scope

**Staged follow-up:** see [SECURITY HARDENING 1](../../SECURITY%20HARDENING%201.md)
