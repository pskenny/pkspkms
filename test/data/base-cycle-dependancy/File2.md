[link1](File1.md)

Base with a link:

```base
views:
  - type: table
    name: table
  - filters:
    - and:
      - 'hasPropertyValue(file, "filePath", "File3.md")'
  - order:
    - '"[[" .. getPropertyValue(file, "filePath") .. "]]", "Path"'
    - 'getPropertyValue(file, "links"), "Links"'
```

(1)
Returns:

| Path             | Links                                  |
|------------------|----------------------------------------|
| \[\[File3.md\]\] | \["File1.md", "File3.md"\]             |