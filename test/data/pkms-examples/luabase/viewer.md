---
title: Viewer
---

Should have 2 rows.

```luabase
formulas:
  ppu: 'return getPropertyValue(file, "price", 0) * 10'
views:
  - type: table
    name: "My table"
    limit: 10
    filters:
      and:
        - 'hasProperty(file, "price")'
        - 'hasPropertyValue(file, "tags", "tag1")'
    order:
      - 'getPropertyValue(file, "filePath"), "Path"'
      - 'getPropertyValue(file, "price"), "Price"'
      - 'ppu(file), "PPU"'
```
