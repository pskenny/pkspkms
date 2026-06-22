
[[File1]]


```base
views:
  - type: table
    name: table
  - filters:
    - and:
      - 'hasPropertyValue(file, "filePath", "File1.md")'
  - order:
    - '"[[" .. getPropertyValue(file, "filePath") .. "]]", "Path"'
    - 'getPropertyValue(file, "links"), "Links"'
```

(1)
Returns:

| Path             | Links                                  |
|------------------|----------------------------------------|
| \[\[File1.md\]\] | \["File1.md", "File2.md", "File3.md"\] |