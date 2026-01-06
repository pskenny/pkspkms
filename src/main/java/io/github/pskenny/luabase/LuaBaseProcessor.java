package io.github.pskenny.luabase;

import io.github.pskenny.io.PksFile;
import io.github.pskenny.luabase.renderer.ListRenderer;
import io.github.pskenny.luabase.renderer.TableRenderer;

import java.util.*;
import java.util.stream.Collectors;

// It's important to note this doesn't do anything to handle the various types YAML has that Lua doesn't even
// have a  default 1-to-1 representation for
public class LuaBaseProcessor {
    private final LuaBaseInterpreter luaBaseInterpreter;

    public LuaBaseProcessor() {
        luaBaseInterpreter = new LuaBaseInterpreter();
    }

    public String process(Map<String, Object> spec, Map<String, PksFile> files) {
        addFormulas(spec);
        Map<String, PksFile> filteredFiles = applyFilters(spec, files);
        applyOrder(spec, filteredFiles);
        Map<String, PksFile> sortedFiles = applySort(spec, filteredFiles);
        return render(spec, sortedFiles);
    }

    Map<String, PksFile> applyFilters(Map<String, Object> spec, Map<String, PksFile> files) {
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

    void addFormulas(Map<String, Object> spec) {
        Map<String, String> formulas = (Map<String, String>) spec.get("formulas");
        if (formulas == null) return;
        for (Map.Entry<String, String> formula : formulas.entrySet()) {
            String name = formula.getKey();
            String func = formula.getValue();
            addFormula(name, func);
        }
    }

    private void addFormula(String name, String function) {
        luaBaseInterpreter.addFunction(name, function);
    }

    boolean evaluateFilterTree(Map<String, Object> filter, Map<String, Object> file) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String operator = entry.getKey();
            List<Object> conditions = (List<Object>) entry.getValue();

            if ("and".equals(operator)) {
                return conditions.stream()
                        .allMatch(cond -> {
                            if (cond instanceof String) {
                                return luaBaseInterpreter.evaluateLuaExpression((String) cond, file).toboolean();
                            } else {
                                return evaluateFilterTree((Map<String, Object>) cond, file);
                            }
                        });
            } else if ("or".equals(operator)) {
                return conditions.stream()
                        .anyMatch(cond -> {
                            if (cond instanceof String) {
                                return luaBaseInterpreter.evaluateLuaExpression((String) cond, file).toboolean();
                            } else {
                                return evaluateFilterTree((Map<String, Object>) cond, file);
                            }
                        });
            } else if ("not".equals(operator)) {
                return !conditions.stream()
                        .allMatch(cond -> {
                            if (cond instanceof String) {
                                return luaBaseInterpreter.evaluateLuaExpression((String) cond, file).toboolean();
                            } else {
                                return evaluateFilterTree((Map<String, Object>) cond, file);
                            }
                        });
            } else {
                return luaBaseInterpreter.evaluateLuaExpression(operator, file).toboolean();
            }
        }
        return false;
    }

    private void applyOrder(Map<String, Object> spec, Map<String, PksFile> files) {
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        List<String> orderSpec = (List<String>) viewSpec.get("order");

        if (orderSpec == null || orderSpec.isEmpty()) {
            return;
        }
    }

    private Map<String, PksFile> applySort(Map<String, Object> spec, Map<String, PksFile> files) {
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

                // this applies sort based on the pksfile, not the property made from the luabase order
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

    private String render(Map<String, Object> spec, Map<String, PksFile> files) {
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        List<String> orderSpec = (List<String>) viewSpec.get("order");

        if (orderSpec == null || orderSpec.isEmpty()) {
            //show all
        }

        if(viewSpec.get("type").equals("list")) {
            ListRenderer listRenderer = new ListRenderer();
            return listRenderer.render(spec, files, luaBaseInterpreter);
        } else if (viewSpec.get("type").equals("table")) {
            TableRenderer tableRenderer = new TableRenderer();
            return tableRenderer.render(spec, files, luaBaseInterpreter);
        }
        return "ERROR I don't know how to handle LuaBase type: " + viewSpec.get("type");
    }
}
