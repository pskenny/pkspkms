How many links are in the files:

```base
views:
  - type: table
    name: table
  - order:
    - '"[[" .. getPropertyValue(file, "filePath") .. "]]", "Path"'
    - 'getPropertyValue(file, "links"), "Path"'
```

Returns:

| Path             | Links                                  |
|------------------|----------------------------------------|
| \[\[File1.md\]\] | \["File1.md", "File2.md", "File3.md"\] |
| \[\[File2.md\]\] | \["File1.md", "File3.md"\]             |
| \[\[File3.md\]\] |                                        |