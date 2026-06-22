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
```

Returns:

| Path             |
|------------------|
| \[\[File3.md\]\] |