package io.pskenny.pkspkms.luabase;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.renderer.ListRenderer;
import io.pskenny.pkspkms.luabase.renderer.TableRenderer;
import io.pskenny.pkspkms.repo.PksFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

// It's important to note this doesn't do anything to handle the various types YAML has that Lua doesn't even
// have a  default 1-to-1 representation for
public class LuaBaseProcessor {
    private static final Logger logger = LoggerFactory.getLogger(LuaBaseProcessor.class);
    private final LuaBaseInterpreter luaBaseInterpreter;

    public LuaBaseProcessor() {
        luaBaseInterpreter = new LuaBaseInterpreter();
    }

    public String process(Map<String, Object> spec, Map<String, PksFile> files) {
        addFormulas(spec);
        Map<String, PksFile> filteredFiles = applyFilters(spec, files);
        Map<String, PksFile> sortedFiles = applySort(spec, filteredFiles);
        return render(spec, sortedFiles.values());
    }

    public String process(Map<String, Object> spec, PksFileRepository repository) {
        // add the functions to as SQLite user defined functions
        addFormulas(spec);
        // generate yaml obsidian base filter to sql query
        Map filters = null;
        List filtersList = null;
        ArrayList views = (ArrayList) spec.get("views");
        for (Object view : views) {
            if (view instanceof Map mapView) {
                if (mapView.containsKey("filters")) {
                    var obj = (LinkedHashMap) mapView.get("filters");
                    filters = obj;
                }
            }
        }
        String luaFilter = filterYamlToExpression(filters);
        List<PksFile> files = new ArrayList<>();
        try {
            files = repository.searchWithLuaFilter(luaFilter);
        } catch (SQLException e) {
            logger.error("Error searching with Lua filter: {}", luaFilter);
            return "Error searching with Lua filter: " + luaFilter;
        }

//        Map<String, PksFile> sortedFiles = applySort(spec, filteredFiles);
        return render(spec, files);
    }

    String filterYamlToExpression(Object spec) { // Changed input to Object for recursion
        if (spec == null) return "true";

        // 1. Handle if the spec is a Map (The standard case)
        if (spec instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) spec;
            List<String> parts = new ArrayList<>();

            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (isLogical(key) && value instanceof List) {
                    String operator = (key.equalsIgnoreCase("or") || key.equalsIgnoreCase("any")) ? " or " : " and ";
                    List<?> subFilters = (List<?>) value;

                    List<String> children = subFilters.stream()
                            .map(this::filterYamlToExpression) // Recursive call
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());

                    if (children.size() > 1) {
                        parts.add("(" + String.join(operator, children) + ")");
                    } else if (!children.isEmpty()) {
                        parts.add(children.get(0));
                    }
                } else {
                    // Leaf node: Simple property check
                    parts.add(String.valueOf(value));
                }
            }
            if (parts.isEmpty()) return "true";
            return parts.size() == 1 ? parts.get(0) : "(" + String.join(" and ", parts) + ")";
        }

        // 2. Handle if the spec is a plain String (The fix for your Exception)
        if (spec instanceof String strSpec) {
            return strSpec; // Or whatever default field you want for raw strings
        }

        return "true";
    }

    private boolean isLogical(String key) {
        return key.equalsIgnoreCase("and") || key.equalsIgnoreCase("all") ||
                key.equalsIgnoreCase("or") || key.equalsIgnoreCase("any");
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
                return conditions.stream().allMatch(cond -> evaluateCondition(cond, file));
            } else if ("or".equals(operator)) {
                return conditions.stream().anyMatch(cond -> evaluateCondition(cond, file));
            } else if ("not".equals(operator)) {
                return !conditions.stream().allMatch(cond -> evaluateCondition(cond, file));
            } else {
                return luaBaseInterpreter.evaluateLuaExpression(operator, file).toboolean();
            }
        }
        return false;
    }

    private boolean evaluateCondition(Object condition, Map<String, Object> file) {
        if (condition instanceof String expression) {
            return luaBaseInterpreter.evaluateLuaExpression(expression, file).toboolean();
        }
        return evaluateFilterTree((Map<String, Object>) condition, file);
    }

    private Map<String, PksFile> applySort(Map<String, Object> spec, Map<String, PksFile> files) {
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);
        List<String> sortSpec = (List<String>) viewSpec.get("sort");
        if (sortSpec == null || sortSpec.isEmpty()) {
            return files;
        }

        LinkedHashMap<String, String> sort = (LinkedHashMap<String, String>) sortSpec.toArray()[0];
        String property = sort.get("property");
        String direction = sort.get("direction");

        List<Map.Entry<String, PksFile>> sortedEntries = new java.util.ArrayList<>(files.entrySet());
        sortedEntries.sort(buildComparator(property, direction));

        Map<String, PksFile> sortedMap = new java.util.LinkedHashMap<>(files.size());
        for (Map.Entry<String, PksFile> entry : sortedEntries) {
            sortedMap.put(entry.getKey(), entry.getValue());
        }

        return sortedMap;
    }

    private Comparator<Map.Entry<String, PksFile>> buildComparator(String property, String direction) {
        return (a, b) -> {
            Comparable valueA = (Comparable) a.getValue().getProperties().get(property);
            Comparable valueB = (Comparable) b.getValue().getProperties().get(property);

            if (valueA == null || valueB == null) {
                return 0;
            }

            if (valueA.compareTo(valueB) != 0) {
                return "desc".equalsIgnoreCase(direction) ? valueB.compareTo(valueA) : valueA.compareTo(valueB);
            }

            return 0;
        };
    }

    private String render(Map<String, Object> spec, Collection<PksFile> files) {
        Map<String, Object> viewSpec = ((List<Map<String, Object>>) spec.get("views")).get(0);

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
