# PKSPKMS Android Integration

This directory contains reference files for integrating `pkspkms-core` into a Kotlin Android app. The files are **reference only** — copy them into your app's source tree as needed.

## Why this works

`pkspkms-core` is pure Java with no Android-unsafe dependencies:
- No `org.sqlite` in main source (moved to `pkspkms-desktop`; Android brings its own via xerial)
- No `java.nio.file` (replaced with `java.io.File` — works on API < 26)
- No `slf4j-simple` at compile scope (test-only in core)
- No Java records (replaced with plain final classes — no D8 dexing errors)
- File I/O is abstracted behind `VaultFileSystem` / `VaultEntry` interfaces
- A default `JavaFileVaultFileSystem` (wrapping `java.io.File`) is provided in core
- All compile-scope dependencies are pure Java: `commonmark`, `jackson-databind`, `luaj-jse`, `snakeyaml`, `nanohttpd`, `commons-codec`, `slf4j-api`

The one piece that needs xerial's `sqlite-jdbc` is `SQLiteLuaConnector` — it uses `org.sqlite.Function.create()` to register `lua_eval` as a SQLite custom function via JNI. xerial bundles Android-native `.so` files (`aarch64`, `arm`, `x86`, `x86_64`) inside the JAR. The JNI call to `sqlite3_create_function` works identically on Android and desktop — **true parity, no fallback**.

## Prerequisites

- Android **API 21+** (SQLite JSON1 functions `json_extract`/`json_each` require API 21)
- Desugared Java 8+ library support enabled
- Gradle with AGP 8.0+

## Files in this directory

| File | Purpose |
|------|---------|
| `SQLiteLuaConnector.java` | Copy into `src/main/java/io/pskenny/pkspkms/repo/sqlite/` — registers `lua_eval` as a SQLite custom function |
| `PkspkmsAndroid.kt` | Reference Kotlin utility object — shows driver registration, repository init, vault load, server start |
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

Core's transitive deps (`commonmark`, `jackson`, `luaj`, `snakeyaml`, `nanohttpd`, `commons-codec`) should resolve automatically via Maven. If your build excludes transitive deps, declare them explicitly (see `build.gradle.example`).

### Step 3: Extract native libraries

xerial's `sqlite-jdbc` JAR contains Android `.so` files under `org/sqlite/native/Linux-Android/`. Android needs these in `src/main/jniLibs/{abi}/`. Copy the `extractSqliteNatives` task from `build.gradle.example` into your `build.gradle`.

The task maps xerial's directory names to Android ABI names:

| xerial JAR directory | Android `jniLibs` directory |
|---|---|
| `aarch64` | `arm64-v8a` |
| `arm` | `armeabi-v7a` |
| `x86` | `x86` |
| `x86_64` | `x86_64` |

### Step 4: Add SQLiteLuaConnector.java

Copy `SQLiteLuaConnector.java` from this directory into your app's source tree at:
```
src/main/java/io/pskenny/pkspkms/repo/sqlite/SQLiteLuaConnector.java
```

No changes needed — it's the same file used by `pkspkms-desktop`.

### Step 5: Initialize

See `PkspkmsAndroid.kt` for the full reference. The essential pattern:

```kotlin
// Register the JDBC driver (Android doesn't auto-discover via SPI)
Class.forName("org.sqlite.JDBC")

// Create database path and ensure the directory exists
val dbPath = "/data/data/${packageName}/databases/pkspkms.db"
File(dbPath).parentFile?.mkdirs()

// Construct vault filesystem
// For path-based access (app-internal storage), use JavaFileVaultFileSystem:
val vaultFs: VaultFileSystem = JavaFileVaultFileSystem(File(vaultPath))
// For SAF (user-picked trees), implement VaultFileSystem yourself — see below.

// Construct repository with Lua function registration + vault filesystem
val repository = SQLitePksFileRepository(
    "jdbc:sqlite:$dbPath",
    SQLiteLuaConnector.luaFunctionRegistrar(),
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

To access user-picked trees (required for scoped storage on Android 10+), implement `VaultFileSystem` yourself using `DocumentFile` + `ContentResolver`. pkspkms-core provides only the interface; the Android app provides the implementation.

Sketch:
```kotlin
class SafVaultFileSystem(
    private val resolver: ContentResolver,
    private val treeUri: Uri
) : VaultFileSystem {

    override fun listFiles(excluded: List<String>?): List<VaultEntry> {
        val root = DocumentFile.fromTreeUri(/* context */ ctx, treeUri)!!
        val result = mutableListOf<VaultEntry>()
        walkSaf(root, "", excluded ?: emptyList(), result)
        return result
    }

    private fun walkSaf(dir: DocumentFile, prefix: String, excluded: List<String>, out: MutableList<VaultEntry>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                if (name in excluded) continue
                walkSaf(child, rel, excluded, out)
            } else if (child.isFile) {
                out.add(SafVaultEntry(rel, name, child.lastModified()))
            }
        }
    }

    override fun resolve(relativePath: String): VaultEntry { /* ... */ }
    override fun openInput(relativePath: String): InputStream = resolver.openInputStream(resolveUri(relativePath))!!
    override fun exists(relativePath: String): Boolean = resolveUri(relativePath) != null
    override fun openOutput(relativePath: String): OutputStream { /* create parents, open output */ }
    override fun writeString(relativePath: String, content: String) { /* ... */ }
    override fun copyFrom(source: InputStream, destRelativePath: String) { /* buffer-loop copy */ }

    private fun resolveUri(relativePath: String): Uri? { /* walk segments */ }

    private class SafVaultEntry(
        private val rel: String,
        private val name: String,
        private val mtime: Long
    ) : VaultEntry {
        override fun relativePath() = rel
        override fun name() = name
        override fun lastModified() = mtime
    }
}
```

The Android app picks a tree via `ACTION_OPEN_DOCUMENT_TREE`, persists the URI permission, then constructs `SafVaultFileSystem(resolver, uri)`. Pass it to `SQLitePksFileRepository` as the `vaultFs` constructor argument.

### Step 5c: Virtual vaults with SAF

If your app needs multiple SAF trees (e.g. a main vault + a referenced vault), construct a `VaultFileSystem` per tree, then call:

```kotlin
val aliasFs = SafVaultFileSystem(resolver, secondTreeUri)
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
| `FILES` | `id`, `vault_alias_id`, `file_path`, `file_name`, `file_name_ext`, `file_created`, `file_last_modified`, `last_sync`, `properties` (JSON), `frontmatter` (JSON), `blake3` | One row per file. `properties` is the primary query target — a JSON blob of frontmatter + extracted links/wikilinks/embeds |
| `LINKS` | `source_file_id`, `target_file_id`, `link_type` | Graph edges between files. `link_type` is `outgoing`, `wikilink`, etc. Backlinks are computed from this table |
| `EMBEDS` | `id`, `vault_alias_id`, `file_id`, `type`, `original_match`, `target_file`, `target_header`, `generated_content`, `computed_props` | Embed records (`base`/`luabase`/`embed`). `generated_content` holds rendered Markdown |
| `VAULT_ALIASES` | `id`, `alias`, `directory`, `is_virtual`, `created_at` | Virtual vault mappings (`@alias/` prefixes in file paths) |

### Critical: singleton lifecycle

`SQLiteSchema.init()` currently **drops and recreates** all four tables on every `new SQLitePksFileRepository(...)` construction. This means:

- **Do NOT** construct the repository inside an `Activity` or `ViewModel` — it will wipe the database on every launch/rotation
- **DO** keep the repository as an app-lifetime singleton (e.g., in `Application.onCreate()` or a DI container)

This schema-drop is a known issue planned for a future fix (`CREATE TABLE IF NOT EXISTS` + migration). Until then, singleton is mandatory.

### Multiple connections

`EmbedProcessor` and `WikilinkService` each open their own JDBC connections via `DriverManager.getConnection(dbUrl)` in addition to the repository's primary connection. WAL mode (set by the constructor's `PRAGMA journal_mode = WAL`) makes concurrent reads safe. This is normal — three connections to the same SQLite file.

## Query DSL

`PksFileRepository.searchRegular(String)` takes a **DSL string**, not SQL. `SqlQueryParser` translates it to SQLite SQL internally:

```
# Equality
tags = 'foo'
filePath = 'notes/Markdown.md'

# Wildcard (becomes LIKE)
filePath = *.md         →  filePath LIKE '%.md'
filePath : notes/*      →  filePath LIKE 'notes/%'

# Boolean operators
tags = 'foo' AND NOT type = 'bar'
(tags = 'a' OR tags = 'b') AND type = 'note'

# Key existence (has the property)
tags                    →  json_type(properties, '$.tags') IS NOT NULL
```

Operators: `=`, `!=`, `>`, `<`, `>=`, `<=`, `LIKE`, `:` (LIKE shorthand)

Supports `AND` / `OR` / `NOT` and parentheses for grouping.

Query values with special characters (spaces, parens) must be quoted: `filePath:"notes/My (Special) File.md"`

## How lua_eval works on Android

On both desktop and Android:

1. `SQLitePksFileRepository` constructor calls `SQLiteLuaConnector.luaFunctionRegistrar().accept(conn)`
2. `SQLiteLuaConnector.registerLuaFunction(conn)` calls `org.sqlite.Function.create(conn, "lua_eval", ...)` — a xerial JNI binding to SQLite's C `sqlite3_create_function`
3. `searchWithLuaFilter(luaScript)` runs `SELECT ... WHERE lua_eval(?, properties) = 1` — the `lua_eval` SQL function is evaluated inside SQLite on each row
4. Inside `lua_eval`, the Lua expression is evaluated against the row's `properties` JSON using `LuaBaseInterpreter` (pure Java luaj — Android-safe)

The `.so` native libraries from xerial's JAR (extracted into `jniLibs/` by the Gradle task) provide the JNI bridge. No extra work needed — same code path as desktop.

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `No suitable driver found for jdbc:sqlite:...` | JDBC driver not registered | Add `Class.forName("org.sqlite.JDBC")` before `DriverManager.getConnection` |
| `No such function: lua_eval` | `SQLiteLuaConnector` not passed to constructor | Pass `SQLiteLuaConnector.luaFunctionRegistrar()` as the second constructor arg |
| `UnsatisfiedLinkError: ... libsqlitejdbc...` | Native `.so` files not in `jniLibs/` | Run the `extractSqliteNatives` Gradle task, or verify `jniLibs/` contains the ABI directories |
| `SQLITE_ERROR: no such function: json_extract` | API < 21 (no JSON1) | Confirm `minSdkVersion 21` or higher |
| Database wiped on app restart | Repository constructed multiple times | Keep the repository as an app singleton — see "Critical: singleton lifecycle" above |
| Slow first load | Parsing all files, expected | Subsequent loads are incremental (only changed files re-parsed) |