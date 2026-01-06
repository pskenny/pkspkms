package io.github.pskenny.luabase.renderer;

import io.github.pskenny.io.PksFile;
import io.github.pskenny.luabase.LuaBaseInterpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TableRenderer {
    public String render(Map<String, Object> spec, Map<String, PksFile> files, LuaBaseInterpreter luaBaseInterpreter) {
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        List<String> orderSpec = (List<String>) viewSpec.get("order");

        if (orderSpec == null || orderSpec.isEmpty()) {
            //show all
        }

        List<String> headers = new ArrayList<>();
        if (orderSpec == null || orderSpec.isEmpty()) {
            // what do you do when you aren't given an order? every file has a filePath
            orderSpec = new ArrayList<>(1);
            orderSpec.add("getPropertyValue(file, \"filePath\", \"\"), \"Path\"");
        }
        orderSpec.forEach(colSpec ->
                headers.add(colSpec.substring(colSpec.lastIndexOf(",") + 3, colSpec.length() - 1))
        );

        StringBuilder sb = new StringBuilder();
        sb.append("| " + String.join(" | ", headers) + " |")
                .append("\n")
                .append("|" + "---|".repeat(headers.size() - 1) + "---|")
                .append("\n");

        List<String> finalOrderSpec1 = orderSpec;
        files.values().forEach(pksFile -> {
            StringBuilder row = new StringBuilder("|");
            Map<String, Object> fileProperties = pksFile.getProperties();
            finalOrderSpec1.forEach(colSpec -> {
                String expression = colSpec.substring(0, colSpec.lastIndexOf(","));
                String propValue = luaBaseInterpreter.evaluateLuaExpression(expression, fileProperties).tojstring();
                row.append(" ").append(propValue).append(" |");
            });
            sb.append(row);
            sb.append("\n");
        });
        return sb.toString();
    }
}
