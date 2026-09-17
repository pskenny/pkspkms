---
title: Security
---

PKSPKMS is a single-user, local tool. The honest current posture:

**True today:**

- File *content* is never transferred over HTTP — queries return properties
  (frontmatter + generated keys) only
- Query exports are CLI-only; HTTP responses are read-only JSON
- The Lua execution environment is being sandboxed (see BUGS.md B2 and the
  pending Design A plan): `luajava`, `os`, `io`, `load`, `dofile`, `require`
  are denied to note-controlled expressions

**Open gaps (see BUGS.md):**

- No authentication; the server binds all interfaces; CORS `*` (B3)
- Webui XSS via note-controlled property values (B4)

> [!NOTE] Do not expose the server beyond localhost until B2–B4 are closed.
