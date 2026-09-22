## P1 — Wrong results / data integrity

| ID | Bug                                                                                                                                                                                                 | Location                                                                                                        |
|----|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| 1  | All tables dropped on every startup — `--db` persistence illusory, full rescan                                                                                                                      | SQLiteSchema.java (init)                                                                                        |
| 2  | `pkspkms add` destroys the DB (consequence of 1) — feature cannot work                                                                                                                              | Application.java (add command)                                                                                  |
| 3  | `evaluateFilterTree` evaluates only the FIRST filter-map entry (HashMap order)                                                                                                                      | LuaBaseProcessor.java (evaluateFilterTree)                                                                      |
| 4  | Map-valued filter leaves become Lua table literals, always truthy (no filtering)                                                                                                                    | LuaBaseProcessor.java (filterYamlToExpression)                                                                  |
| 5  | `not` over list means NOT(AND), not per-condition negation                                                                                                                                          | LuaBaseProcessor.java (evaluateFilterTree)                                                                      |
| 6  | Wikilink resolution cross-vault + unordered SELECT (main <-> `@alias` contamination)                                                                                                                | RepositoryFileLoader.java (resolveWikilinks)                                                                    |
| 7  | Duplicate names resolve nondeterministically (`LIMIT 1` no `ORDER BY`)                                                                                                                              | WikilinkService.java, WikilinkFinder.java                                                                       |
| 8  | `resolveEmbed` strips all backslashes from embedded content                                                                                                                                         | Export.java (resolveEmbed)                                                                                      |
| 9  | Literal `"ERROR"` / raw YAML leak into exported markdown; converter lacks `fileFieldStartsWith` and table-literal filter support → headers-only renders ("Invalid Lua filter syntax" startup noise) | SqliteEmbedProcessor.java, NaiveBaseToLuaBaseConverter.java, SQLitePksFileRepository.java (getGeneratedContent) |

## P2 — Functional bugs

| ID | Bug                                                                                                                                                                         | Location                                                                  |
|----|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| 10 | Every link stored as BOTH `outgoing` and `wikilink` in LINKS (query-side `DISTINCT` collapses backlinks, but storage stays doubled)                                         | RepositoryFileLoader.java (updatePropertiesAndLinks)                      |
| 11 | Directory containment missing trailing separator — sibling dirs pass (`/vault-backup`)                                                                                      | PksFile.java (getFilePath), JavaFileSystem.java (resolve/getRelativePath) |
| 12 | Virtual-vault files can't export (content read on wrong FS)                                                                                                                 | Export.java (readContent/copyFileViaFs)                                   |
| 13 | `addFormulas` CCE on non-string formula values                                                                                                                              | LuaBaseProcessor.java (addFormulas)                                       |
| 14 | `[[wikilinks]]` in code fences + `![[embed]]` contents counted as wikilinks                                                                                                 | MarkdownWikilinkReader.java                                               |
| 15 | `base` blocks inside code fences processed as real bases                                                                                                                    | MarkdownProcessor.java (getBases/getLuaBases)                             |
| 16 | BOM-prefixed files silently lose all frontmatter                                                                                                                            | YamlFrontmatterReader.java                                                |
| 17 | TableRenderer: comma in header breaks parsing; vertical bar/newline corrupts cells                                                                                          | TableRenderer.java                                                        |
| 18 | CLI: no-args NPE (`switch(null)`), uncaught `RepositoryException` stack trace, "could start" typo                                                                           | Application.java (main/ctor)                                              |
| 19 | Base lookup by exact `original_match` — whitespace divergence leads to raw-YAML fallback                                                                                    | SQLitePksFileRepository.java (getGeneratedContent)                        |
| 20 | Export skips existing output files without content comparison — stale exports                                                                                               | Export.java (copyFileViaFs)                                               |
| 21 | Embed targets don't handle the vertical bar (`targetFile = "Note\|alias"`)                                                                                                  | SQLitePksFileRepository.java (addEmbedsToTable)                           |
| 22 | Untrusted OPML subscriptions fetch arbitrary sources: non-http `xmlUrl` reads local files into the vault; remote redirects can hit loopback/link-local/private hosts (SSRF) | FeedFetcher.java, OpmlFileSystem.java                                     |
| 23 | Production `server.start()` has no socket read timeout (tests set it explicitly) — slowloris stalls request threads                                                         | Application.java                                                          |
| 24 | Log injection: filesystem/user-derived values (embed YAML, error messages) reach log lines unscrubbed — CR/LF forges entries                                                | SqliteEmbedProcessor.java, Server.java                                    |

## P3 — Debt (bug-adjacent)

- Dead code: unused FILES columns (`file_created`, `last_sync`, `frontmatter`)
- SQL hardcoded in service layer; `SqliteRepository.getConnection()` leaks raw `Connection`
- Boolean params instead of enums (`Export`, `addVaultAlias`)
- `wikilinks` property semantics inconsistent: raw texts (initialParse) vs resolved paths (parseToMap)
- `SQLiteLuaConnector.getCache()` public — test-only access in main API
- EMBEDS.vault_alias_id never populated (file_id only); `computed_props` stored but unconsumed by queries

## Fix order

1. Bases-correctness pass: 3 + 4 + 5 + 9 + 19 (+ 13) — the failed-embed and headers-only renders
2. 1 + 2 — persistence; root cause of full rescans
3. 6 + 7 — resolver determinism and cross-vault hygiene
4. P2 opportunistically
