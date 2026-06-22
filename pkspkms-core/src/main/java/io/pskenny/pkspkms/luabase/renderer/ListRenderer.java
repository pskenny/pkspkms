package io.pskenny.pkspkms.luabase.renderer;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.LuaBaseInterpreter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ListRenderer {
    public String render(Map<String, Object> spec, Collection<PksFile> files, LuaBaseInterpreter luaBaseInterpreter) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        List<String> orderSpec = (List<String>) viewSpec.get("order");

        if (orderSpec == null || orderSpec.isEmpty()) {
            // what do you do when you aren't given an order? every file has a filePath
            orderSpec = new ArrayList<>(1);
            orderSpec.add("getPropertyValue(file, \"filePath\", \"\"), \"\"");
        }

        List<String> finalOrderSpec = orderSpec;
        files.forEach(pksFile -> {
            StringBuilder row = new StringBuilder("-");
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
