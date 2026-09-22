package io.pskenny.pkspkms.luabase;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.renderer.ListRenderer;
import io.pskenny.pkspkms.luabase.renderer.TableRenderer;
import io.pskenny.pkspkms.repo.PksFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        ViewSpec viewSpec = ViewSpec.from(spec);
        List<PksFile> filteredFiles = applyFilters(viewSpec, files);
        List<PksFile> sortedFiles = applySort(viewSpec, filteredFiles);
        return render(viewSpec, sortedFiles);
    }

    // Single-pass embed rendering: one corpus (already-parsed PksFiles), one
    // compiled filter, evaluated in-process per file. Same expression semantics
    // as the old lua_eval SQL path — minus the JNI round-trip and per-row JSON
    // re-parsing. Invalid filters render empty, mirroring the old logged skip.
    public String processOverCorpus(Map<String, Object> spec, List<PksFile> corpus) {
        addFormulas(spec);
        ViewSpec viewSpec = ViewSpec.from(spec);
        String luaFilter = filterYamlToExpression(viewSpec.filters());
        try {
            luaBaseInterpreter.validateExpression(luaFilter);
        } catch (RuntimeException e) {
            logger.warn("Invalid Lua filter syntax, rendering headers only: {}", e.getMessage());
            return render(viewSpec, new ArrayList<>());
        }

        List<PksFile> matches = new ArrayList<>();
        for (PksFile file : corpus) {
            try {
                if (luaBaseInterpreter.evaluateExpression(luaFilter, file.getMutableProperties())) {
                    matches.add(file);
                }
            } catch (RuntimeException e) {
                // per-row failure = non-match, exactly as the JNI callback did
            }
        }
        return render(viewSpec, applySort(viewSpec, matches));
    }

    public void validateLuaFilter(String luaFilter) {
        luaBaseInterpreter.validateExpression(luaFilter);
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

        throw new IllegalArgumentException("Unexpected filter spec type: " + spec.getClass().getName());
    }

    private boolean isLogical(String key) {
        return key.equalsIgnoreCase("and") || key.equalsIgnoreCase("all") ||
                key.equalsIgnoreCase("or") || key.equalsIgnoreCase("any");
    }

    List<PksFile> applyFilters(ViewSpec viewSpec, Map<String, PksFile> files) {
        Map<String, Object> filtersSpec = viewSpec.filters();
        if (filtersSpec == null || filtersSpec.isEmpty()) {
            return new ArrayList<>(files.values());
        }

        // Filters the map by evaluating the filter tree on each PksFile's properties
        return files.values().stream()
                .filter(file -> evaluateFilterTree(filtersSpec, file.getMutableProperties()))
                .collect(Collectors.toList());
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

    private List<PksFile> applySort(ViewSpec viewSpec, Collection<PksFile> files) {
        List<Map<String, Object>> sortSpec = viewSpec.sort();
        if (sortSpec == null || sortSpec.isEmpty()) {
            return new ArrayList<>(files);
        }

        Map<String, Object> sort = sortSpec.get(0);
        String property = (String) sort.get("property");
        String direction = (String) sort.get("direction");

        List<PksFile> sorted = new ArrayList<>(files);
        sorted.sort(buildComparator(property, direction));
        return sorted;
    }

    private Comparator<PksFile> buildComparator(String property, String direction) {
        return (a, b) -> {
            Object valueA = a.getMutableProperties().get(property);
            Object valueB = b.getMutableProperties().get(property);

            if (valueA == null && valueB == null) return 0;
            if (valueA == null) return 1;
            if (valueB == null) return -1;

            int result = compareValues(valueA, valueB);
            return "desc".equalsIgnoreCase(direction) ? -result : result;
        };
    }

    // Same-type values compare naturally; cross-type values fall back so one exotic
    // property value can't abort the whole base render. ISO dates compare correctly
    // as strings.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a.getClass() == b.getClass() && a instanceof Comparable comparableA) {
            try {
                return comparableA.compareTo(b);
            } catch (ClassCastException e) {
                // fall through
            }
        }
        if (a instanceof Number numberA && b instanceof Number numberB) {
            return Double.compare(numberA.doubleValue(), numberB.doubleValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private String render(ViewSpec viewSpec, Collection<PksFile> files) {
        String type = viewSpec.type();
        if ("cards".equals(type) || "map".equals(type)) {
            logger.debug("View type '{}' cannot be rendered, defaulting to table", type);
            type = "table";
        }
        if ("list".equals(type)) {
            ListRenderer listRenderer = new ListRenderer();
            return listRenderer.render(viewSpec.order(), files, luaBaseInterpreter);
        } else if ("table".equals(type)) {
            TableRenderer tableRenderer = new TableRenderer();
            return tableRenderer.render(viewSpec.order(), files, luaBaseInterpreter);
        }
        throw new IllegalArgumentException("Unknown view type: " + type);
    }

    static final class ViewSpec {
        private final String type;
        private final Map<String, Object> filters;
        private final List<String> order;
        private final List<Map<String, Object>> sort;

        ViewSpec(String type, Map<String, Object> filters, List<String> order, List<Map<String, Object>> sort) {
            this.type = type;
            this.filters = filters;
            this.order = order;
            this.sort = sort;
        }

        String type() { return type; }
        Map<String, Object> filters() { return filters; }
        List<String> order() { return order; }
        List<Map<String, Object>> sort() { return sort; }

        @SuppressWarnings("unchecked")
        static ViewSpec from(Map<String, Object> spec) {
            List<Map<String, Object>> views = (List<Map<String, Object>>) spec.get("views");
            if (views == null || views.isEmpty()) {
                throw new IllegalArgumentException("Spec must contain at least one view");
            }
            Map<String, Object> view = views.get(0);
            return new ViewSpec(
                    (String) view.get("type"),
                    (Map<String, Object>) view.get("filters"),
                    (List<String>) view.get("order"),
                    (List<Map<String, Object>>) view.get("sort")
            );
        }
    }
}
