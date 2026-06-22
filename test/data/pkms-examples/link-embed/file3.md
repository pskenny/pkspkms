```base
views:
  - type: list
    name: List
    filters:
      and:
        - file.tags.contains("file2")
    order:
      - file.name
```
