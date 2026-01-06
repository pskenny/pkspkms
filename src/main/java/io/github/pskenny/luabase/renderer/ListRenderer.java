package io.github.pskenny.luabase.renderer;

import io.github.pskenny.io.PksFile;
import io.github.pskenny.luabase.LuaBaseInterpreter;

import java.util.List;
import java.util.Map;

public class ListRenderer {
    public String render(Map<String, Object> spec, Map<String, PksFile> files, LuaBaseInterpreter luaBaseInterpreter) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        List<String> orderSpec = (List<String>) viewSpec.get("order");

        List<String> finalOrderSpec = orderSpec;
        files.values().forEach(pksFile -> {
            StringBuilder row = new StringBuilder(" -");
            Map<String, Object> fileProperties = pksFile.getProperties();
            finalOrderSpec.forEach(colSpec -> {
                String expression = colSpec.substring(0, colSpec.lastIndexOf(","));
                String propertyValue = luaBaseInterpreter.evaluateLuaExpression(expression, fileProperties).tojstring();
                row.append(" ").append(propertyValue);
            });
            sb.append(row);
            sb.append("\n");
        });
        return sb.toString();
    }
}
