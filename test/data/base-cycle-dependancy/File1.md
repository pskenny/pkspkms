How many links are in the files:



```base
views:
  - type: table
    name: table
  - filters:
    - and:
      - 'hasPropertyValue(file, "filePath", "File2.md")'
  - order:
    - '"[[" .. getPropertyValue(file, "filePath") .. "]]", "Path"'
    - 'getPropertyValue(file, "links"), "Links"'
```

(1)
Returns:

| Path             | Links                              |
|------------------|------------------------------------|
| \[\[File2.md\]\] | \[\[File1.md\]\], \[\[File3.md\]\] |