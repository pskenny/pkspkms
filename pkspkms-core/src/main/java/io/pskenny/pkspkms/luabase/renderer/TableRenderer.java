package io.pskenny.pkspkms.luabase.renderer;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.LuaBaseInterpreter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class TableRenderer {
    public String render(Map<String, Object> spec, Collection<PksFile> files, LuaBaseInterpreter luaBaseInterpreter) {
        ArrayList viewSpec = (ArrayList) spec.get("views");
        List<String> orderSpec = null;

        for(Object view : viewSpec) {
            if (view instanceof Map mapView) {
                if (mapView.containsKey("order")) {
                    orderSpec = (List<String>) mapView.get("order");
                }
            }
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
        files.forEach(pksFile -> {
            StringBuilder row = new StringBuilder("|");
            Map<String, Object> fileProperties = pksFile.getProperties();
            try {
                finalOrderSpec1.forEach(colSpec -> {
                    String expression = colSpec.substring(0, colSpec.lastIndexOf(","));
                    String propValue = luaBaseInterpreter.evaluateLuaExpression(expression, fileProperties).tojstring();
                    row.append(" ").append(propValue).append(" |");
                });
            } catch(Exception e) {
                System.err.println("Error while evaluating expression: " + e.getMessage());
            }
            sb.append(row);
            sb.append("\n");
        });
        return sb.toString();
    }
}
