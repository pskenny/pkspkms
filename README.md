# THIS IS ALPHA, DON'T USE

**PK**'**S** **P**ersonal **K**nowledge **M**anagement **S**ystem

A program that provides a single user HTTP API and command-line export tool for a single directory Zettelkasten-style personal knowledge 
management system (like an Obsidian Vault).

## Structure

This is a multi-module Maven project:

- **`pkspkms-core`** — Reusable library containing domain objects, parsers, the query engine, repository abstractions, and the HTTP server.
- **`pkspkms-desktop`** — Executable desktop launcher with CLI parsing and optional system tray.

## Build And Run

Make sure you have Maven and JDK 17 installed and running correctly. The following commands use the example directory 
in this repo (`test/data/pkms-examples/example`), you can try it out on your own data by replacing that value with your own directory.

For reference:

```text
test/data/pkms-examples/example
├── Example.md
├── Notes
│   ├── Backlink-for-Resolved-Wikilink.md
│   ├── PKSPKMS.md
│   ├── Resolved Wikilink.md
│   └── Tasks
│       └── Task.md
├── README.md
└── Resources
    └── Neumann.jpg
```

### Quick Start (build + install)

```shell
# Clone and build
git clone git@github.com:pskenny/pkspkms.git ~/pkspkms
cd pkspkms

# Build the fat JAR (checks Java 17+ and Maven 3.6+) and installs to ~/.local (creates a 'pkspkms' command)
./build.sh && ./install.sh
```

### Try Out Exporting

```shell
mkdir temp-dir
pkspkms export --directory test/data/pkms-examples/example --query "" --output temp-dir --type "markdown"
```

### Try out the server

```shell
# Start server at port 23467 using test directory (default port is 3000)
pkspkms server --directory test/data/pkms-examples/example --port 23467
# In another terminal
curl GET "http://localhost:23467/files/list" | jq .
```

### Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /ping` | Liveness check |
| `GET /files/list?query=<q>` | Query files by property (Lucene-style syntax); empty `query` matches all |
| `GET /files/search?query=<q>` | Alias of `/files/list` |
| `GET /files/list/graph?query=<q>` | Same, reduced to `filePath`, `links`, `backlinks`, `tags` |
| `GET /files/manifest` | Per-virtual-vault `{filePath, blake3}` pairs for `pkspkms://` targets |
| `GET /webui/` | Browser UI |
| `GET /cache/{address}/{location}?directory=<dir>` | Copy a file from a virtual vault into the cache directory |
| `GET /openapi.json` | OpenAPI 3.0 spec (`servers` URL rewritten to the running port) |
| `GET /webui/swagger` | Swagger UI rendering the spec |

Query syntax (Lucene-style): `tags:PKSPKMS`, `filePath:*.md`, `NOT tags:Archive`, `price:[1 TO 5]`, `status:(todo OR done)`, quoted phrases, wildcards `* ?`. Malformed queries return HTTP 400.

### Virtual vaults

Mount other directories alongside the main vault with `@alias/`-prefixed paths:

```shell
pkspkms server --directory <vault> --virtual-vault gwern:/path/to/other/vault --port 3000
```

### System Tray

On desktop environments with a system tray, you can add `--tray` to show an icon with a right-click menu:

```shell
java -jar pkspkms-desktop/target/pkspkms-desktop-0.1.0.jar server --directory test/data/pkms-examples/example --port 9239 --tray
```

The tray icon shows the server status, port, vault path, and the last log line. The menu includes an **"Open in Browser"** action and a **"Quit"** action for graceful shutdown. If the tray cannot be initialized (e.g. on a headless server), the application logs a warning and continues normally.

Returns:

```json
{
  "resultSize": 6,
  "files"     : [
    {
      "title"   : "README",
      "tags"    : ["PKSPKMS", "Development", "Documentation"],
      "filePath": "README.md"
    },
    {
      "shouldBeANumber"    : 42,
      "filePath"           : "Notes/PKSPKMS.md",
      "links"              : ["Notes/Resolved Wikilink.md", "../Resources/Neumann.jpg"],
      "title"              : "Different title than file name",
      "anotherYamlProperty": "test data",
      "tags"               : ["Meta", "PKSPKMS", "Tag"]
    },
    { "tags": ["Task"], "filePath": "Notes/Tasks/Task.md" },
    {
      "aliases" : ["Resolved Wikilink Defined By YAML Frontmatter alias"],
      "title"   : "Resolved Wikilink Defined By YAML Frontmatter title",
      "tags"    : ["Example"],
      "filePath": "Notes/Resolved Wikilink.md"
    },
    {
      "links"   : ["Resources/Neumann.jpg"],
      "title"   : "Example Title",
      "tags"    : ["Example"],
      "filePath": "Example.md"
    },
    {"filePath": "Resources/Neumann.jpg"}
  ]
}
```

You can also query it, such as `curl GET "http://localhost:9239/files/list?query=tags=Tag" | jq .` returns:

```json
{
  "resultSize": 1,
  "files"     : [
    {
      "shouldBeANumber"    : 42,
      "filePath"           : "Notes/PKSPKMS.md",
      "links"              : ["Notes/Resolved Wikilink.md", "../Resources/Neumann.jpg"],
      "title"              : "Different title than file name",
      "anotherYamlProperty": "test data",
      "tags"               : ["Meta", "PKSPKMS", "Tag"]
    }
  ]
}
```

### Testing

```shell
# Run all tests across both modules
mvn clean test
# Run core tests only
mvn test -pl pkspkms-core
# Generate Jacoco report (core module)
mvn test -pl pkspkms-core
open pkspkms-core/target/site/jacoco/index.html
```

## Logging

Logging is SLF4J/simple, `INFO` by default. Turn on debug output per package:

```shell
java -Dorg.slf4j.simpleLogger.log.io.pskenny.pkspkms=debug -jar pkspkms-desktop/target/pkspkms-desktop-0.1.0.jar server --directory <vault> --port 3000
```

## Known Issues

- See [BUGS.md](BUGS.md) for the tracked list (~28 open)

## Quirks

- For compatibility uses same types for Markdown frontmatter as Obsidian: `date`, `datetime`, `number`, `text`, 
  `multitext`, `checkbox`