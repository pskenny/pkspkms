# PKSPKMS Android Integration

This directory contains reference files for integrating `pkspkms-core` into a Kotlin Android app. The files are **reference only** — copy them into your app's source tree as needed.

## Why this works

`pkspkms-core` is pure Java with no Android-unsafe dependencies:
- No `org.sqlite` in main source — Android brings its own driver via xerial's bundled Native `.so`
- No `java.nio.file` in main source (replaced with `java.io.File` — works on API < 26)
- No `slf4j-simple` at compile scope (test-only in core)
- File I/O is abstracted behind the `PkmsFileSystem` / `PkmsEntry` interfaces (`io.pskenny.pkspkms.io.fs`)
- A default `JavaFileSystem` (wrapping `java.io.File`) is provided in core
- Uses Java records (`CompiledQuery`, `QueryNode`, `SynthFile`, …) — fine with
  AGP 8 toolchains; verify your desugaring setup
- All compile-scope dependencies are pure Java: `flexmark` (+ wikilink/gfm-tasklist), `jackson-databind`, `luaj-jse`, `snakeyaml`, `nanohttpd`, `commons-codec`, `slf4j-api`

Embed filters evaluate **in-process** (`LuaBaseProcessor` + `LuaBaseInterpreter`, pure-Java luaj —
no JNI Lua hook). You still need xerial's `sqlite-jdbc` — it is the SQLite driver itself, with
Android `.so` files (`aarch64`, `arm`, `x86`, `x86_64`) bundled inside the JAR.

## Prerequisites

- Android **API 21+** (the SQLite engine is xerial's own bundled modern build — JSON1 and FTS5 come from that engine, not from the Android platform)
- Desugared Java 8+ library support enabled
- Gradle with AGP 8.0+

## Files in this directory

| File | Purpose |
|------|---------|
| `PkspkmsAndroid.kt` | Reference Kotlin utility object — driver registration, repository init, vault load, server start |
| `build.gradle.example` | Reference Gradle snippet — dependencies + native `.so` extraction task |

## Integration steps

### Step 1: Publish pkspkms-core to local Maven

From the PKSPKMS repo root:
```bash
mvn -pl pkspkms-core install -DskipTests
```

This installs the core JAR into `~/.m2/repository/io/pskenny/pkspkms/pkspkms-core/0.1.0/`.

### Step 2: Add dependencies

Copy the `dependencies` block from `build.gradle.example` into your app module's `build.gradle`. Key dependencies:

```groovy
implementation 'io.pskenny.pkspkms:pkspkms-core:0.1.0'
implementation 'org.xerial:sqlite-jdbc:3.51.3.0'
implementation 'org.slf4j:slf4j-simple:2.0.17'
```

Core's transitive deps (`flexmark` and its extensions, `jackson`, `luaj`, `snakeyaml`, `nanohttpd`, `commons-codec`) should resolve automatically via Maven. If your build excludes transitive deps, declare them explicitly (see `build.gradle.example`).

### Step 3: Extract native libraries

xerial's `sqlite-jdbc` JAR contains Android `.so` files under `org/sqlite/native/Linux-Android/`. Android needs these in `src/main/jniLibs/{abi}/`. Copy the `extractSqliteNatives` task from `build.gradle.example` into your `build.gradle`.

The task maps xerial's directory names to Android ABI names:

| xerial JAR directory | Android `jniLibs` directory |
|---|---|
| `aarch64` | `arm64-v8a` |
| `arm` | `armeabi-v7a` |
| `x86` | `x86` |
| `x86_64` | `x86_64` |

### Step 4: Construct the repository with a `null` registrar

Core evaluates base/luabase embed filters in-process (`LuaBaseProcessor`, pure-Java luaj), so nothing
is registered on the connection. The constructor's registrar argument takes `null`.

### Step 5: Initialize

See `PkspkmsAndroid.kt` for the full reference. The essential pattern:

```kotlin
// Register the JDBC driver (Android doesn't auto-discover via SPI)
Class.forName("org.sqlite.JDBC")

// Create database path and ensure the directory exists
val dbPath = "/data/data/${packageName}/databases/pkspkms.db"
File(dbPath).parentFile?.mkdirs()

// Construct vault filesystem
// For path-based access (app-internal storage), use JavaFileSystem:
val vaultFs: PkmsFileSystem = JavaFileSystem(File(vaultPath))
// For SAF (user-picked trees), implement PkmsFileSystem yourself — see below.

// Construct repository (null registrar — embed filters run in-process)
val repository = SQLitePksFileRepository(
    "jdbc:sqlite:$dbPath",
    null,
    vaultFs
)

// Load vault (no path argument — the filesystem is already rooted)
repository.loadDirectoryIntoRepository()

// Optional: start HTTP server
val server = Server(3000, repository)
server.start(60000, false)
```

**Run initialization on a background thread**, never on the UI thread — `loadDirectoryIntoRepository` walks the filesystem and parses files.

### Step 5b: SAF (Storage Access Framework) — user-picked directory access

To access user-picked trees (required for scoped storage on Android 10+), implement `PkmsFileSystem` yourself using `DocumentFile` + `ContentResolver`. pkspkms-core provides only the interface; the Android app provides the implementation.

Sketch:
```kotlin
class SafFileSystem(
    private val resolver: ContentResolver,
    private val treeUri: Uri
) : PkmsFileSystem {

    override fun listFiles(excluded: List<String>?): List<PkmsEntry> {
        val root = DocumentFile.fromTreeUri(/* context */ ctx, treeUri)!!
        val result = mutableListOf<PkmsEntry>()
        walkSaf(root, "", excluded ?: emptyList(), result)
        return result
    }

    private fun walkSaf(dir: DocumentFile, prefix: String, excluded: List<String>, out: MutableList<PkmsEntry>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                if (name in excluded) continue
                walkSaf(child, rel, excluded, out)
            } else if (child.isFile) {
                out.add(SafEntry(rel, name, child.lastModified()))
            }
        }
    }

    override fun resolve(relativePath: String): PkmsEntry { /* ... */ }
    override fun openInput(relativePath: String): InputStream = resolver.openInputStream(resolveUri(relativePath))!!
    override fun exists(relativePath: String): Boolean = resolveUri(relativePath) != null
    override fun openOutput(relativePath: String): OutputStream { /* create parents, open output */ }
    override fun writeString(relativePath: String, content: String) { /* ... */ }
    override fun copyFrom(source: InputStream, destRelativePath: String) { /* buffer-loop copy */ }

    private fun resolveUri(relativePath: String): Uri? { /* walk segments */ }

    private class SafEntry(
        private val rel: String,
        private val name: String,
        private val mtime: Long
    ) : PkmsEntry {
        override fun relativePath() = rel
        override fun name() = name
        override fun lastModified() = mtime
    }
}
```

The Android app picks a tree via `ACTION_OPEN_DOCUMENT_TREE`, persists the URI permission, then constructs `SafFileSystem(resolver, uri)`. Pass it to `SQLitePksFileRepository` as the `vaultFs` constructor argument.

### Step 5c: Virtual vaults with SAF

If your app needs multiple SAF trees (e.g. a main vault + a referenced vault), construct a `PkmsFileSystem` per tree, then call:

```kotlin
val aliasFs = SafFileSystem(resolver, secondTreeUri)
repository.loadVirtualVault(aliasFs, "alias_name")
```

The repository holds each alias fs internally and uses it for `cacheFile` cross-fs copies.

### Step 6: (Optional) Configure logging

Add `simplelogger.properties` to `src/main/resources/`:
```
org.slf4j.simpleLogger.showLogName=false
org.slf4j.simpleLogger.showThreadName=false
org.slf4j.simpleLogger.defaultLogLevel=warn
```

`slf4j-simple` writes to `System.err`, which Android redirects to logcat. No `slf4j-android` needed (it's incompatible with slf4j-api 2.0.x).

## Schema & database details

The database is SQLite, created automatically by `SQLitePksFileRepository`'s constructor. Four tables:

| Table | Columns | Purpose |
|---|---|---|
| `FILES` | `id`, `vault_alias_id`, `file_path`, `file_name`, `file_name_ext`, `file_last_modified`, `properties` (JSON), `blake3`, `body` (note text, `''` for binaries) | One row per file. `properties` is the primary query target — a JSON blob of frontmatter + extracted links/wikilinks/embeds; `body` feeds the FTS5 `files_fts` full-text index (`text:` queries; ranked with `textSnippet`) |
| `LINKS` | `source_file_id`, `target_file_id`, `link_type` | Graph edges between files. `link_type` is `outgoing`, `wikilink`, etc. Backlinks are computed from this table |
| `EMBEDS` | `id`, `file_id`, `type`, `original_match`, `target_file`, `target_header`, `generated_content` | Embed records (`base`/`luabase`). `generated_content` holds rendered Markdown; the trimmed `original_match` block is the cache key |
| `VAULT_ALIASES` | `id`, `alias`, `directory`, `is_virtual`, `created_at` | Virtual vault mappings (`@alias/` prefixes in file paths) |

### Lifecycle

`SQLiteSchema` is versioned (`META.schema_version`) and persists data across
constructions (versioned schema — see `SQLiteSchema`). Still keep the repository as an
app-lifetime singleton — recreating it re-walks and re-parses the vault for
nothing.

### Multiple connections

`SqliteEmbedProcessor` (the base/luabase render pass) opens its own JDBC connection via `DriverManager.getConnection(dbUrl)` in addition to the repository's primary connection (wikilink resolution rides the repository's shared connection through `SqliteFiles`). WAL mode (set by the constructor's `PRAGMA journal_mode = WAL`) makes concurrent reads safe. This is normal — two connections to the same SQLite file.

## Query API

The repository's query interface takes a **compiled query**, built from a
Lucene-style property-query string — not SQL:

```kotlin
import io.pskenny.pkspkms.repo.query.CompiledQuery
import io.pskenny.pkspkms.repo.query.QueryParser
import io.pskenny.pkspkms.repo.query.QueryCompiler

val compiled: CompiledQuery = QueryCompiler().compile(QueryParser().parse("tags:foo"))
val results = repository.searchRegular(compiled)   // List<PksFile>
```

```text
tags:foo                 property equals (scalar or any array element)
filePath:*.md            wildcards * and ?
tags:*                   property exists
price:[1 TO 5]           range ({1 TO 5} exclusive, * = open bound)
status:(todo OR done)    grouped values over one field
"multi word"             quoted phrase
foo bar                  bare words substring-match any property value
NOT tags:archive         exclusion (field-absent rows match too)
```

Malformed input throws `QueryParseException` (offset + message). Declared
property types from `.obsidian/types.json` drive typed comparisons on
Android too — the vault filesystem you mount just needs to serve
`.obsidian/types.json` at its root like any vault.

## How embed filter evaluation works on Android

Core evaluates base/luabase embed filters **in-process per file**:
`LuaBaseProcessor` hands the block's filter script to `LuaBaseInterpreter`
(pure-Java luaj — Android-safe), and caches rendered Markdown in
`EMBEDS.generated_content`. The SQLite engine ships inside xerial's driver
(`jniLibs/` `.so` files), so there is no SQLite-side Lua function and no
per-row JNI bridge to manage. Passing `null` as the repo constructor's
registrar matches desktop.

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `No suitable driver found for jdbc:sqlite:...` | JDBC driver not registered | Add `Class.forName("org.sqlite.JDBC")` before `DriverManager.getConnection` |
| `UnsatisfiedLinkError: ... libsqlitejdbc...` | Native `.so` files not in `jniLibs/` | Run the `extractSqliteNatives` Gradle task, or verify `jniLibs/` contains the ABI directories |
| `SQLITE_ERROR: no such function: json_extract` | Bundled engine build missing | Verify `jniLibs/` libs match the pinned xerial version and the driver loads from the `.so` |
| Index upgrade fails (`Unsupported legacy database schema`) | The database predates in-place migration and its layout is unrecognized | Rename or delete the database file and re-index (the vault is untouched — the DB is a rebuildable cache) |
| Slow first load | Parsing all files, expected | Unchanged files are skipped on later start-ups (mtime diff) |