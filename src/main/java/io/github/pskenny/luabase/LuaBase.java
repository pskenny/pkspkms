package io.github.pskenny.luabase;

import io.github.pskenny.io.PksFile;

import java.util.*;

public class LuaBase {
    public static void main(String[] args) {
        String yamlCode = """
formulas:
  formatted_price: 'return string.format("$%.2f", getPropertyValue(file, "price", 0))'
  ppu: 'return getPropertyValue(file, "price", 0) * 5'
views:
  - type: table
    name: "My table"
    limit: 10
    filters:
      and:
        - 'not hasPropertyValue(file, "tag", "book")'
    order:
      - '"[[" .. getPropertyValue(file, "filePath") .. "]]", "Path"'
      - 'formatted_price(file), "Price"'
      - 'ppu(file), "PPU"'
      - 'toFixed(ppu(file), 0), "toFixed"'
                """;

        Map<String, PksFile> files = new HashMap<>();
        files.put("/notes/my_book_note.md", new PksFile("/notes/my_book_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/my_book_note.md");
            // Change from Arrays.asList to a new ArrayList
            put("tag", new ArrayList<>(Arrays.asList("book", "textbook")));
            put("status", "done");
            put("price", 10.5095);
            put("age", 2);
        }}));
        files.put("/notes/another_book_note.md", new PksFile("/notes/another_book_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/another_book_note.md");
            put("tag", new ArrayList<>(Arrays.asList("book", "fiction")));
            put("status", "ne");
            put("price", 3.00);
            put("age", 4);
        }}));
        files.put("/notes/a_project_done.md", new PksFile("/notes/a_project_done.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/a_project_done.md");
            put("tag", new ArrayList<>(Arrays.asList("project", "work")));
            put("status", "de");
            put("age", 1);
        }}));
        files.put("/notes/Required Reading/a_required_reading_note.md", new PksFile("/notes/Required Reading/a_required_reading_note.md", new HashMap<String, Object>() {{
            put("filePath", "/notes/Required Reading/a_required_reading_note.md");
            put("tag", new ArrayList<>(Arrays.asList("book", "required")));
            put("status", "in-progress");
            put("price", 6.00);
            put("age", 6);
        }}));

        Map<String, Object> yaml = new YamlBaseParser().parse(yamlCode);
        LuaBaseProcessor processor = new LuaBaseProcessor(yaml);
        System.out.println(processor.process(files));
    }
}
