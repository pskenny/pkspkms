package io.pskenny.pkspkms.luabase.renderer;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.LuaBaseInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TableRenderer {
    private static final Logger logger = LoggerFactory.getLogger(TableRenderer.class);

    public String render(List<String> orderSpec, Collection<PksFile> files, LuaBaseInterpreter luaBaseInterpreter) {
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
        // Render-time failures are counted, not spammed: one row's failing
        // expression used to log a full stack trace per row (thousands of lines)
        final AtomicInteger failures = new AtomicInteger();
        files.forEach(pksFile -> {
            StringBuilder row = new StringBuilder("|");
            java.util.Map<String, Object> fileProperties = pksFile.getMutableProperties();
            try {
                finalOrderSpec1.forEach(colSpec -> {
                    String expression = colSpec.substring(0, colSpec.lastIndexOf(","));
                    String propValue = luaBaseInterpreter.evaluateLuaExpression(expression, fileProperties).tojstring();
                    row.append(" ").append(propValue).append(" |");
                });
            } catch(Exception e) {
                failures.incrementAndGet();
                row.append(" error |");
            }
            sb.append(row);
            sb.append("\n");
        });
        if (failures.get() > 0) {
            logger.warn("Table render: {} row(s) had failing expressions", failures.get());
        }
        return sb.toString();
    }
}
