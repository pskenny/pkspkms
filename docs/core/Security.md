---
title: Security
---

PKSPKMS is a single-user, local tool. Current posture (post-hardening 1):

**In force:**

- Loopback-only bind: the server accepts connections from 127.0.0.1 unless
  `--bind` says otherwise — LAN devices cannot reach it
- Lua sandbox: note-controlled expressions run in an **allow-list
  environment, fresh per render pass**. Only the comparison builtins
  (`type`/`tostring`/`tonumber`), the `string`/`table`/`math` libraries and
  the trusted helpers of `functions.lua` exist. There is no `package`
  global (its searchers could re-arm `os`/`luajava` from classpath classes),
  no `load`/`loadstring`/`dofile`/`loadfile`/`require`, no `os`/`io`/
  `luajava`, no `print`, and `_G` is a frozen snapshot so a note cannot
  poison helper globals for other notes. Each note's view gets its own
  throwaway environment: user formulas cannot outlive the note they came
  from, and a malformed formula costs its column, not the embed
- Embed render watchdog: every embedded view renders under a per-embed
  wall-clock budget (`pkspkms.embed.timeoutMs`, default 15 s) on a daemon
  worker pool; a view that loops forever is marked "ERROR" and the mount
  continues. Hostile feed/peer content that stalls one worker leaves a
  bounded daemon thread burning CPU until restart (residual, below)
- Token auth (**opt-in**: `--enable-auth`): when the flag is on, a random
  token is generated at startup and written 0600 to
  `~/.config/pkspkms/token`; every route except `/ping` and the `/webui`
  pages requires it (`--token`/`--token-file` override). When the flag is
  off, no Authorization is checked and no token file is written — the
  loopback bind + Host allow-list are the only gates
- Token/DB hygiene: the SQLite DB and its WAL/-shm sidecars plus the token
  file are owner-only (0600). Access logs print to stdout (SLF4J) and touch
  no disk files. Note body text is indexed inside that same DB — covered by
  the owner-only posture
- Feed fetching: http(s) sources only — operator-configured local files are
  read directly, but subscription targets must be http(s) URLs resolving to
  public addresses; loopback, link-local and private ranges are refused at
  every redirect hop; bodies capped at 10 MB
- `/cache` traversal: rejected — locations are contained inside the virtual
  vault and the cache directory inside the vault; rejected paths never
  create directories on disk. The alias must be a **registered virtual
  vault** — a vault-path hit alone never serves an unknown alias
- Drive-by resistance: `/cache` is POST-only and requires the custom header
  `X-Pkspkms-Cache-Request: 1` (HTML form POSTs cannot set it; browser JS
  can only add it after a preflight, answered for allow-listed origins
  only), and a `Host`-header allow-list rejects DNS-rebinding requests
- CORS: default closed; `--cors-origin` allow-list grants that origin's pages
  API access — data still gated by auth when on; `*` requires auth
- Browser hardening: CSP `default-src 'self'; script-src 'self';
  frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`,
  `Referrer-Policy: no-referrer` on webui responses
- Log hygiene: access logs record paths only, never query strings

**Residual risks (documented, accepted):**

- The Lua engine has no instruction-level watchdog on compiled chunks
  (luaj 3.0.1 never checks debug hooks while executing): a note-controlled
  *pure* loop (`while true do end` with no function calls) keeps one worker
  thread spinning until process restart even after the embed marks itself
  "ERROR". Bounded to the worker pool size (2–4 daemon threads). Revisit if
  luaj gains instruction accounting; today the budget surface note content
  can actually reach (loops containing calls, giant allocations, recursion)
  is covered by workers+catches
- A note's Lua can still spend CPU within its own render pass — expression
  length capped at 4 KB, per-embed wall clock bounded, per-cell output
  capped at 64 KB
- No TLS: loopback traffic never touches a wire; expose beyond localhost only
  through a reverse proxy with real certs
- Programs running as your own user can read the database and vault directly —
  HTTP-layer security cannot help there; that class is OS-level (disk
  encryption, sandboxing untrusted software)
- Employer policy for work machines (personal tools, cloud sync) is outside
  the program's scope

**Security fixes rolled in:** slowloris (production start now uses a socket
read timeout), log injection (user-derived log values are scrubbed through
`LogUtil.oneLine`), and the Lua sandbox rebuild (S1-S3/M2, 2026-11-08):
allow-list environment rebuilt per render pass (no `package` searchers, no
`loadfile`, frozen `_G`, disposable formula scope) plus the per-embed
watchdog — pinned by `LuaBaseInterpreterSandboxTest`,
`LuaBaseProcessorIsolationTest`, `LuaBaseProcessorDoSTest` and
`EmbedWatchdogTest`.
