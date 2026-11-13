package io.pskenny.pkspkms.luabase;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.renderer.ListRenderer;
import io.pskenny.pkspkms.luabase.renderer.TableRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.pskenny.pkspkms.io.LogUtil.oneLine;

import java.util.*;

// It's important to note this doesn't do anything to handle the various types YAML has that Lua doesn't even
// have a  default 1-to-1 representation for
public class LuaBaseProcessor {
    private static final Logger logger = LoggerFactory.getLogger(LuaBaseProcessor.class);

    // User formula names must be Lua identifiers: addFunction string-concatenates
    // the name into `function NAME(file) ... end`, so an unsanitized name is a
    // script-injection channel. Per-pass sessions make the residue harmless, but
    // the guard stays cheap and structural.
    private static final java.util.regex.Pattern FORMULA_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // Body cap mirrors the filter expression cap (passes LuaBaseInterpreter's
    // own MAX_EXPRESSION_LENGTH at runtime once the wrapper is assembled).
    private static final int MAX_FORMULA_BODY_CHARS = LuaBaseInterpreter.MAX_EXPRESSION_LENGTH;

    // Single-pass embed rendering: one corpus (already-parsed PksFiles), one
    // compiled filter, evaluated in-process per file. Same expression semantics
    // as the old lua_eval SQL path — minus the JNI round-trip and per-row JSON
    // re-parsing. Invalid filters render empty, mirroring the old logged skip.
    //
    // Tier 8: recognized filters evaluate natively in Java (FilterEval);
    // unrecognized filters keep the Lua interpreter with partial-coercion
    // pruning via the analyzer's referenced set, and unrecognized* users
    // always fall back to full coercion. Parity is pinned by
    // FilterEvalDifferentialTest.
 public String processOverCorpus(Map<String, Object> spec, List<PksFile> corpus) {
        // One disposable sandbox session per render pass: formulas, compile
        // caches and globals live and die with this note's view (BUGS.md S3 —
        // a note must not be able to poison the helpers used by another).
        LuaBaseInterpreter interpreter = new LuaBaseInterpreter();
        addFormulas(spec, interpreter);
        ViewSpec viewSpec = ViewSpec.from(spec);
        String luaFilter = filterYamlToExpression(viewSpec.filters());
        try {
            interpreter.validateExpression(luaFilter);
        } catch (RuntimeException e) {
            logger.warn("Invalid Lua filter syntax, rendering headers only: {}", e.getMessage());
            return render(viewSpec, new ArrayList<>(), interpreter);
        }

        FilterAnalyzer.Result analysis = FilterAnalyzer.analyze(luaFilter);

        // NOTE: formula definitions are added to the interpreter at this point;
        // a recognized filter must not depend on them, so recognized-only
        // shapes are safe. Anything referencing user formulas is excluded by
        // the whitelist (only converter-emitted calls are recognized).
        FilterExpr plan = analysis.recognized() ? analysis.ast() : null;
        List<PksFile> matches = new ArrayList<>();
        if (plan != null) {
            for (PksFile file : corpus) {
                try {
                    if (FilterEval.eval(plan, file.getMutableProperties())) {
                        matches.add(file);
                    }
                } catch (Throwable t) {
                    // per-row failure = non-match, same as Lua path; catching
                    // Throwable keeps a Lua runtime StackOverflowError (an
                    // Error) from aborting the whole batch (BUGS.md M2)
                }
            }
            return render(viewSpec, applySort(viewSpec, matches), interpreter);
        }

        Set<String> referenced = null; // unrecognized → full coercion, old behavior
        for (PksFile file : corpus) {
            try {
                if (interpreter.evaluateExpression(luaFilter, file.getMutableProperties(), referenced)) {
                    matches.add(file);
                }
            } catch (Throwable t) {
                // per-row failure = non-match, exactly as the JNI callback did
            }
        }
        return render(viewSpec, applySort(viewSpec, matches), interpreter);
    }

    public void validateLuaFilter(String luaFilter) {
        new LuaBaseInterpreter().validateExpression(luaFilter);
    }

    // Filter YAML -> Lua boolean expression.
    //
    // Semantics (BUGS.md B3/B4/B5):
    // - EVERY map entry is evaluated; entry results AND-combine
    // - logical keys: and/all -> AND, or/any -> OR, not -> NONE-OF
    //   (`not: [c1, c2]` ≡ `not (c1 or c2)`: any hit excludes the row)
    // - non-logical keys carry direct expressions: String passes through; a
    //   Map or List leaf recurses (B4 — no more String.valueOf of a Map, the
    //   old truthy-through hole); any other value is warned and skipped
    String filterYamlToExpression(Object spec) {
        return filterExpression(spec, 0, false);
    }

    private String filterExpression(Object spec, int depth, boolean nested) {
        if (spec == null) {
            return "true";
        }
        if (depth > 32) {
            // filter YAML is author content; a pathological nesting depth is a
            // filter the author never wanted to render
            logger.warn("Filter nesting exceeds 32 levels, ignoring it");
            return "true";
        }
        if (spec instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) spec;
            List<String> entryResults = new ArrayList<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (isLogical(key)) {
                    List<String> children = childExpressions(value, depth);
                    if (children.isEmpty()) {
                        continue; // empty logical entry: nothing said
                    }
                    boolean negative = key.equalsIgnoreCase("not");
                    // none-of: not [c1, c2] ≡ not (c1 or c2)
                    String oper = (key.equalsIgnoreCase("or") || key.equalsIgnoreCase("any") || negative)
                            ? " or " : " and ";
                    String inner = String.join(oper, children);
                    String group = "(" + inner + ")";
                    entryResults.add(negative ? "not " + group : group);
                } else {
                    String leaf = leafExpression(key, value, depth, nested);
                    // M8: blank leaves condition nothing — same skip as nulls
                    if (leaf != null && !leaf.isBlank()) {
                        entryResults.add(leaf);
                    }
                }
            }
            if (entryResults.isEmpty()) {
                return "true";
            }
            return entryResults.size() == 1
                    ? entryResults.get(0)
                    : "(" + String.join(" and ", entryResults) + ")";
        }

        if (spec instanceof String strSpec) {
            // M8: a blank string spec conditions nothing — same as a null child
            return strSpec.isBlank() ? "true" : strSpec;
        }

        logger.warn("Filter ignores unexpected specimen of type {}", spec.getClass().getName());
        return "true";
    }

    // Children of a logical key: a list of conditions, or a single condition.
    // M8: blank-string children (`""`, whitespace) are skipped like nulls —
    // an empty join term used to compile into a Lua syntax error that turned
    // the WHOLE filter into headers-only.
    private List<String> childExpressions(Object value, int depth) {
        List<String> children = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                addChild(children, item, depth);
            }
        } else if (value != null) {
            addChild(children, value, depth);
        }
        return children;
    }

    private void addChild(List<String> children, Object condition, int depth) {
        if (condition == null) {
            return; // YAML null child: not a condition
        }
        if (condition instanceof String s && s.isBlank()) {
            return; // blank child: not a condition (M8)
        }
        children.add(filterExpression(condition, depth + 1, true));
    }
    // Non-logical map entry: value is the condition payload. Raw-expression
    // shorthand is honored only at the filter's top level; nested positions
    // carry logical containers (B4 — the old String.valueOf(Map) hole).
    private String leafExpression(String key, Object value, int depth, boolean nested) {
        if (value instanceof String strValue) {
            if (nested) {
                logger.warn("Filter ignores non-logical map leaf in nested position (key={})", key);
                return null;
            }
            return strValue;
        }
        if (value instanceof Map || value instanceof List) {
            return filterExpression(value, depth + 1, true);
        }
        logger.warn("Filter ignores non-string leaf (key={}, type={})", key,
                value == null ? "null" : value.getClass().getSimpleName());
        return null;
    }

    // Redundant keys alias the same shape: and=alias all, or=alias any. `not`
    // is logical too — value semantics = none-of (B5).
    private boolean isLogical(String key) {
        return key.equalsIgnoreCase("and") || key.equalsIgnoreCase("all") ||
                key.equalsIgnoreCase("or") || key.equalsIgnoreCase("any") ||
                key.equalsIgnoreCase("not");
    }

    void addFormulas(Map<String, Object> spec, LuaBaseInterpreter interpreter) {
        Map<?, ?> formulas = (Map<?, ?>) spec.get("formulas");
        if (formulas == null) return;
        for (Map.Entry<?, ?> formula : formulas.entrySet()) {
            String name = String.valueOf(formula.getKey());
            // B13: non-string formula values are skipped, not cast
            if (!(formula.getValue() instanceof String func)) {
                logger.warn("Formula '{}' skipped: value is not a string", name);
                continue;
            }
            // Name validates as a Lua identifier; body length mirrors the
            // filter cap; a failing formula is skipped per-formula (it costs
            // one column, not the whole embed).
            if (!FORMULA_NAME.matcher(name).matches()) {
                logger.warn("Formula '{}' skipped: name is not a Lua identifier", oneLine(name));
                continue;
            }
            if (func.length() > MAX_FORMULA_BODY_CHARS) {
                logger.warn("Formula '{}' skipped: body exceeds {} characters", oneLine(name), MAX_FORMULA_BODY_CHARS);
                continue;
            }
            try {
                interpreter.addFunction(name, func);
            } catch (RuntimeException e) {
                logger.warn("Formula '{}' skipped: {}", oneLine(name), oneLine(String.valueOf(e.getMessage())));
            }
        }
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

    private String render(ViewSpec viewSpec, Collection<PksFile> files, LuaBaseInterpreter interpreter) {
        String type = viewSpec.type();
        if ("cards".equals(type) || "map".equals(type)) {
            logger.debug("View type '{}' cannot be rendered, defaulting to table", type);
            type = "table";
        }
        if ("list".equals(type)) {
            ListRenderer listRenderer = new ListRenderer();
            return listRenderer.render(viewSpec.order(), files, interpreter);
        } else if ("table".equals(type)) {
            TableRenderer tableRenderer = new TableRenderer();
            return tableRenderer.render(viewSpec.order(), files, interpreter);
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
