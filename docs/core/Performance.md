---
title: Performance
---

**Application-level benchmarks** (test through `Application.java` — full CLI entry point):

```shell
# Export + server startup through the actual Application constructor
mvn test -pl pkspkms-desktop -am -Dtest=EndToEndAppBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

The `-am` flag is needed to build the `pkspkms-core` dependency first. `-Dsurefire.failIfNoSpecifiedTests=false` prevents the core module from erroring when it can't find the benchmark class.

The benchmark suite:

- **Startup & load benchmarks** — scan, parse and index a vault into SQLite,
  measured through the full CLI entry point. Active datasets:

    | Dataset | Files |
    |---------|-------|
    | `test/data/pkms-examples/base` | 3 |
    | `test/data/pkms-examples/luabase` | 6 |
    | `test/data/pkms-examples/links` | 4 |
    | `test/data/base-cycle-dependancy` | 3 |
    | `test/data/pkms-examples/example` | 7 |
    | `docs` | 15 |

    Two long-running datasets are available but commented out in the code
    (do not uncomment unless you have time): `test/temp/knowledge` (100 files)
    and `test/temp/pages` (95 files).

- **Query benchmarks** — HTTP queries against the `pkms-examples` vault

- **Export benchmarks** — markdown and copy export pipeline (rendering, wikilink
  resolution, embed expansion) using `Export.export()` in dry-run mode to
  isolate processing time from disk I/O

Each benchmark runs 3 warmup iterations (discarded) then 10 measured iterations.
Query and export benchmarks run against an empty CLI start, so they measure
steady-state work, not vault load.

Example output (2026-09-06, after parallel loading and the query-language rewrite):

```
============================================================================
  1. Startup & Load E2E Benchmarks (Through Application CLI)
  Warmup: 3 | Measured: 10
============================================================================
Scenario                            Files    Avg (ms)    Min (ms)   Max (ms)
----------------------------------------------------------------------------
base                                    3          34          26         49
luabase                                 6           4           3          8
links                                   4           4           3          6
base-cycle-dependancy                   3           5           3          6
example                                 7           5           3         12
pkspkms docs                           15           3           3          4

============================================================================
  2. HTTP Search API Queries Benchmarks (Through Network HTTP)
============================================================================
Query Endpoint                          Avg (ms)    Min (ms)   Max (ms)
----------------------------------------------------------------------------
filePath:*.md                                 42          42          44
tags:Tag                                      43          42          45
tags (has property)                           43          42          44
NOT tags:Tag                                  43          42          44
tags:Tag1 AND tags:Tag2                       43          42          44
(tags:Tag1) OR (tags:Tag2)                    42          42          44

============================================================================
  3. E2E Export CLI Benchmarks (Through Application CLI)
============================================================================
Scenario                                Avg (ms)    Min (ms)    Max (ms)
----------------------------------------------------------------------------
markdown export                               50          43          59
copy export                                   40          32          50
```

The first-run `base` number is dominated by cold SQLite initialization
(schema creation + WAL setup); warm runs are ~4 ms. Query times are dominated
by HTTP overhead, not query execution.
