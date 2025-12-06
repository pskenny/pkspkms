package io.github.pskenny.luabase;

import io.github.pskenny.io.PksFile;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.*;
import java.util.stream.Collectors;

// It's important to note this doesn't do anything to handle the various types YAML has that Lua doesn't even
// have a  default 1-to-1 representation for
public class LuaBaseProcessor {
    private final Globals globals;
    private final Map<String, Object> spec;

    public LuaBaseProcessor(Map<String, Object> spec) {
        this.globals = JsePlatform.standardGlobals();

        // Load standard Lua functions and custom utility functions
        this.globals.load("""
function hasProperty(file, prop_name)
    return file:get(prop_name) ~= nil
end
                """).call();
        this.globals.load("""
-- doesn't work for arrays
function hasPropertyValue(file, prop_name, value)
    local val = file:get(prop_name)
    if type(val) == "userdata" then
        return val:contains(value)
    end
    return val == value
end
                """).call();
        this.globals.load("""
function getPropertyValue(file, prop_name, default_value)
  local val = file:get(prop_name)
  if val == nil then
      return default_value
  end
  return val
end
""").call();
        this.globals.load("""
function toFixed(num, decimals) return string.format("%." .. decimals .. "f", num) end
        """).call();
        this.spec = spec;
    }

    public String process(Map<String, PksFile> files) {
        addFormulas();
        Map<String, PksFile> filteredFiles = applyFilters(files);
        applyOrder(filteredFiles);
        Map<String, PksFile> sortedFiles = applySort(filteredFiles);
        return renderTable(sortedFiles);
    }

    private Map<String, PksFile> applyFilters(Map<String, PksFile> files) {
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        Map<String, Object> filtersSpec = (Map<String, Object>) viewSpec.get("filters");
        if (filtersSpec == null || filtersSpec.isEmpty()) {
            return files;
        }

        // Filters the map by evaluating the filter tree on each PksFile's properties
        return files.entrySet().stream()
                .filter(entry -> evaluateFilterTree(filtersSpec, entry.getValue().getProperties()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void addFormulas() {
        Map<String, String> formulas = (Map<String, String>) spec.get("formulas");
        if (formulas == null) return;
        for (Map.Entry<String, String> formula : formulas.entrySet()) {
            String name = formula.getKey();
            String func = formula.getValue();
            addFormula(name, func);
        }
    }

    private void addFormula(String name, String func) {
        String script = "function " + name + "(file) " + func + " end";
        globals.load(script).call();
    }

    private boolean evaluateFilterTree(Map<String, Object> filter, Map<String, Object> file) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String operator = entry.getKey();
            List<Object> conditions = (List<Object>) entry.getValue();

            if ("and".equals(operator)) {
                return conditions.stream()
                        .allMatch(cond -> {
                            if (cond instanceof String) {
                                return evaluateLuaExpression((String) cond, file).toboolean();
                            } else {
                                return evaluateFilterTree((Map<String, Object>) cond, file);
                            }
                        });
            } else if ("or".equals(operator)) {
                return conditions.stream()
                        .anyMatch(cond -> {
                            if (cond instanceof String) {
                                return evaluateLuaExpression((String) cond, file).toboolean();
                            } else {
                                return evaluateFilterTree((Map<String, Object>) cond, file);
                            }
                        });
            } else if ("not".equals(operator)) {
                return !conditions.stream()
                        .allMatch(cond -> {
                            if (cond instanceof String) {
                                return evaluateLuaExpression((String) cond, file).toboolean();
                            } else {
                                return evaluateFilterTree((Map<String, Object>) cond, file);
                            }
                        });
            } else {
                return evaluateLuaExpression(operator, file).toboolean();
            }
        }
        return false;
    }

    private LuaValue evaluateLuaExpression(String expression, Map<String, Object> file) {
        LuaValue luaFile = CoerceJavaToLua.coerce(file);
        String script = "return function(file) return " + expression + " end";
        LuaValue functionChunk = globals.load(script);
        LuaValue function = functionChunk.call();
        LuaValue result = function.call(luaFile);
        return result;
    }

    private void applyOrder(Map<String, PksFile> files) {
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        List<String> orderSpec = (List<String>) viewSpec.get("order");

        if (orderSpec == null || orderSpec.isEmpty()) {
            return;
        }
    }

    private Map<String, PksFile> applySort(Map<String, PksFile> files) {
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        List<String> sortSpec = (List<String>) viewSpec.get("sort");
        if (sortSpec == null || sortSpec.isEmpty()) {
            return files;
        }

        List<Map.Entry<String, PksFile>> sortedEntries = new java.util.ArrayList<>(files.entrySet());

        sortedEntries.sort((a, b) -> {
            LinkedHashMap<String, String> sort = (LinkedHashMap<String, String>) sortSpec.toArray()[0];

                String property = sort.get("property");
                String direction = sort.get("direction");

                Comparable valueA = (Comparable) a.getValue().getProperties().get(property);
                Comparable valueB = (Comparable) b.getValue().getProperties().get(property);

                if (valueA == null || valueB == null) {
                    return 0;
                }

                int comparison = valueA.compareTo(valueB);
                if (comparison != 0) {
                    return "desc".equalsIgnoreCase(direction) ? -comparison : comparison;
                }

            return 0;
        });

        Map<String, PksFile> sortedMap = new java.util.LinkedHashMap<>(files.size());
        for (Map.Entry<String, PksFile> entry : sortedEntries) {
            sortedMap.put(entry.getKey(), entry.getValue());
        }

        return sortedMap;
    }

    public String renderTable(Map<String, PksFile> files) {
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        List<String> orderSpec = (List<String>) viewSpec.get("order");

        if (orderSpec == null || orderSpec.isEmpty()) {
            //show all
        }

        // check view type
        if(viewSpec.get("type").equals("list")) {
            StringBuilder sb = new StringBuilder();

            List<String> finalOrderSpec = orderSpec;
            files.values().forEach(pksFile -> {
                StringBuilder row = new StringBuilder(" -");
                Map<String, Object> fileProperties = pksFile.getProperties();
                finalOrderSpec.forEach(colSpec -> {
                    String expression = colSpec.substring(0, colSpec.lastIndexOf(","));
                    String propValue = evaluateLuaExpression(expression, fileProperties).tojstring();
                    row.append(" ").append(propValue);
                });
                sb.append(row);
                sb.append("\n");
            });
            return sb.toString();
        } else if (viewSpec.get("type").equals("table")) {
            List<String> headers = new ArrayList<>();
            if (orderSpec == null || orderSpec.isEmpty()) {
                // what do you do when you aren't given an order?
                orderSpec = new ArrayList<>(1);
                orderSpec.add("getPropertyValue(file, \"filePath\", \"\"), \"Path\"");
            }
            orderSpec.forEach(colSpec -> {
                headers.add(colSpec.substring(colSpec.lastIndexOf(",") + 3, colSpec.length() - 1));
            });

            StringBuilder sb = new StringBuilder();
            sb.append("| " + String.join(" | ", headers) + " |");
            sb.append("\n");
            sb.append("|" + "---|".repeat(headers.size() - 1) + "---|");
            sb.append("\n");

            List<String> finalOrderSpec1 = orderSpec;
            files.values().forEach(pksFile -> {
                StringBuilder row = new StringBuilder("|");
                Map<String, Object> fileProperties = pksFile.getProperties();
                finalOrderSpec1.forEach(colSpec -> {
                    String expression = colSpec.substring(0, colSpec.lastIndexOf(","));
                    String propValue = evaluateLuaExpression(expression, fileProperties).tojstring();
                    row.append(" ").append(propValue).append(" |");
                });
                sb.append(row);
                sb.append("\n");
            });
            return sb.toString();
        }
        return "ERROR in type";
    }
}
