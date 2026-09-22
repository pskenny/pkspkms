package io.pskenny.pkspkms.luabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Naively converts Obsidian Base text to LuaBase.
 */
public class NaiveBaseToLuaBaseConverter {
    private static final Logger logger = LoggerFactory.getLogger(NaiveBaseToLuaBaseConverter.class);

    // --- SnakeYAML instance reuse (SafeConstructor is not thread-safe; one per thread) ---
    private static final ThreadLocal<Yaml> YAML_TL =
            ThreadLocal.withInitial(() -> new Yaml(new SafeConstructor(new LoaderOptions())));

    // --- Regex Patterns ---
    private static final Pattern CONTAINS_ANY_PATTERN = Pattern.compile("([\\w\\.]+)\\.contains(?:All|Any)?\\((.*)\\)");
    private static final Pattern VALUE_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern EQUALS_PATTERN = Pattern.compile("([\\w\\.]+)\\s*==\\s*\"([^\"]+)\"");
    private static final Pattern ARRAY_EQUALS_PATTERN =
            Pattern.compile("([\\w\\.]+)\\s*==\\s*\\[([^\\]]*)\\]");
    private static final Pattern STARTS_WITH_PATTERN =
            Pattern.compile("([\\w.]+)\\.startsWith\\(\"([^\"]*)\"\\)");
    private static final Pattern IS_EMPTY_PATTERN =
            Pattern.compile("([\\w.]+)\\.isEmpty\\(\\)");
    private static final Pattern IN_FOLDER_PATTERN =
            Pattern.compile("([\\w.]+)\\.inFolder\\(\"([^\"]*)\"\\)");
    private static final Pattern COMPARISON_PATTERN =
            Pattern.compile("([\\w\\.]+)\\s*(>=|<=|>|<)\\s*(-?\\d+(?:\\.\\d+)?)");

    // --- Embedded Lua Code Block Templates ---
    private static final String LUA_TAGS_TEMPLATE =
            "'table.concat( (function() " +
                    "local t = {}; " +
                    "local tags_array = getPropertyValue(file, \"tags\", nil); " +
                    "if tags_array then " +
                    "for i=1, #tags_array do " +
                    "local v = tags_array[i]; " +
                    "table.insert(t, \"#\" .. tostring(v)) " +
                    "end " +
                    "end; " +
                    "return t " +
                    "end)(), \" \"), \"tags\"'";
    private static final String LUA_FILE_PATH_VALUE_TEMPLATE =
            "'\"[[\" .. getPropertyValue(file, \"filePath\", \"\") .. \"]]\", \"filePath\"'";

    // --- Domain Property Rule Engine ---
    private enum PropertyMapping {
        TAGS("file.tags", "tags"),
        FILE_PATH("file.name", "filePath"),
        BACKLINKS("file.backlinks", "backlinks"),
        CREATION_DATE("file.ctime", "creationDate"),
        EXTENSION("file.ext", "ext"),
        FOLDER("file.folder", "folder"),
        MODIFICATION_DATE("file.mtime", "modificationDate"),
        PATH("file.path", "path"),
        SIZE("file.size", "size");

        private final String obsidianKey;
        private final String pksKey;

        PropertyMapping(String obsidianKey, String pksKey) {
            this.obsidianKey = obsidianKey;
            this.pksKey = pksKey;
        }

        private static final Map<String, String> LOOKUP;
        static {
            Map<String, String> m = new HashMap<>();
            for (PropertyMapping pm : values()) {
                m.put(pm.obsidianKey, pm.pksKey);
                m.put(pm.pksKey, pm.pksKey);
            }
            LOOKUP = Collections.unmodifiableMap(m);
        }

        public static Optional<PropertyMapping> fromObsidian(String key) {
            return Arrays.stream(values())
                    .filter(m -> m.obsidianKey.equals(key) || m.pksKey.equals(key))
                    .findFirst();
        }

        public static String resolve(String key) {
            String mapped = LOOKUP.get(key);
            if (mapped != null && !mapped.equals(key)) {
                logger.debug("Obsidian specific property found, mapped from {}", key);
            }
            return mapped != null ? mapped : key;
        }
    }

    // --- Immutable Intermediate Expression State ---
    private static final class ParsedQuery {
        private final String property;
        private final List<String> values;
        private final boolean isNegated;
        private final boolean isContainsAny;
        private final ComparisonOp comparisonOp;
        private final boolean isContainsAll;
        private final boolean isContains;

        // Numeric comparison operators, mapped to their Lua function suffix
        private enum ComparisonOp {
            NONE(""), GT("GreaterThan"), GTE("GreaterThanOrEqual"),
            LT("LessThan"), LTE("LessThanOrEqual");

            private static final Map<String, ComparisonOp> BY_SYMBOL = Map.of(
                    ">", GT, ">=", GTE, "<", LT, "<=", LTE);

            private final String luaFunctionSuffix;

            ComparisonOp(String luaFunctionSuffix) {
                this.luaFunctionSuffix = luaFunctionSuffix;
            }

            String luaFunctionSuffix() {
                return luaFunctionSuffix;
            }

            static ComparisonOp fromSymbol(String symbol) {
                return BY_SYMBOL.getOrDefault(symbol, NONE);
            }
        }

        ParsedQuery(String property, List<String> values, boolean isNegated, boolean isContainsAny) {
            this(property, values, isNegated, isContainsAny, ComparisonOp.NONE, false, false);
        }

        ParsedQuery(String property, List<String> values, boolean isNegated, boolean isContainsAny,
                    ComparisonOp comparisonOp) {
            this(property, values, isNegated, isContainsAny, comparisonOp, false, false);
        }

        ParsedQuery(String property, List<String> values, boolean isNegated, boolean isContainsAny,
                    ComparisonOp comparisonOp, boolean isContainsAll, boolean isContains) {
            this.property = property;
            this.values = values;
            this.isNegated = isNegated;
            this.isContainsAny = isContainsAny;
            this.comparisonOp = comparisonOp;
            this.isContainsAll = isContainsAll;
            this.isContains = isContains;
        }

        String property() { return property; }
        List<String> values() { return values; }
        boolean isNegated() { return isNegated; }
        boolean isContainsAny() { return isContainsAny; }
        ComparisonOp comparisonOp() { return comparisonOp; }
        boolean isContainsAll() { return isContainsAll; }
        boolean isContains() { return isContains; }

        public String formatLuaArray() {
            return values.stream()
                    .map(v -> "\"" + v + "\"")
                    .collect(Collectors.joining(", ", "{", "}"));
        }

        public static Optional<ParsedQuery> parse(String jsText) {
            if (jsText == null || jsText.isEmpty()) return Optional.empty();

            boolean negated = jsText.startsWith("!");
            String cleanText = negated ? jsText.substring(1).trim() : jsText;

            // Strip one layer of enclosing parens, e.g. !(file.size > 100)
            if (cleanText.startsWith("(") && cleanText.endsWith(")")) {
                cleanText = cleanText.substring(1, cleanText.length() - 1).trim();
            }

            // Check translation mapping matches up-front
            for (PropertyMapping mapping : PropertyMapping.values()) {
                if (cleanText.startsWith(mapping.obsidianKey)) {
                    cleanText = cleanText.replaceFirst(Pattern.quote(mapping.obsidianKey), mapping.pksKey);
                    break;
                }
            }

            // 1. Try matching contains / containsAll / containsAny
            Matcher containsMatcher = CONTAINS_ANY_PATTERN.matcher(cleanText);
            if (containsMatcher.matches()) {
                String property = PropertyMapping.resolve(containsMatcher.group(1));
                List<String> values = new ArrayList<>();
                Matcher valMatcher = VALUE_PATTERN.matcher(containsMatcher.group(2));
                while (valMatcher.find()) {
                    values.add(valMatcher.group(1));
                }
                boolean isContainsAll = containsMatcher.group().contains("containsAll");
                boolean isContainsAny = containsMatcher.group().contains("containsAny") || values.size() > 1;
                boolean isContains = !isContainsAll && !isContainsAny;
                return Optional.of(new ParsedQuery(property, values, negated, isContainsAny, ComparisonOp.NONE, isContainsAll, isContains));
            }

            // 2. Try matching numeric comparison (>, >=, <, <=)
            Matcher comparisonMatcher = COMPARISON_PATTERN.matcher(cleanText);
            if (comparisonMatcher.matches()) {
                String property = PropertyMapping.resolve(comparisonMatcher.group(1));
                ComparisonOp op = ComparisonOp.fromSymbol(comparisonMatcher.group(2));
                return Optional.of(new ParsedQuery(property, List.of(comparisonMatcher.group(3)),
                        negated, false, op));
            }

            // 3. Try matching array equality: prop == ["a", "b"]
            Matcher arrayEqualsMatcher = ARRAY_EQUALS_PATTERN.matcher(cleanText);
            if (arrayEqualsMatcher.matches()) {
                String property = PropertyMapping.resolve(arrayEqualsMatcher.group(1));
                List<String> values = new ArrayList<>();
                for (String v : arrayEqualsMatcher.group(2).split(",")) {
                    String stripped = v.trim().replaceAll("^['\"]|['\"]$", "");
                    if (!stripped.isEmpty()) {
                        values.add(stripped);
                    }
                }
                if (!values.isEmpty()) {
                    return Optional.of(new ParsedQuery(property, values, negated, true));
                }
            }

            // 4. Try matching simple equality
            Matcher equalsMatcher = EQUALS_PATTERN.matcher(cleanText);
            if (equalsMatcher.matches()) {
                String property = PropertyMapping.resolve(equalsMatcher.group(1));
                List<String> values = List.of(equalsMatcher.group(2));
                return Optional.of(new ParsedQuery(property, values, negated, false));
            }

            return Optional.empty();
        }
    }

    // --- Base Application Flow ---

    public String convert(String base) {
        Map<String, Object> yamlMap = YAML_TL.get().load(YamlParser.expandLeadingTabs(base));
        List<?> views = (List<?>) yamlMap.get("views");
        StringBuilder luaBaseYaml = new StringBuilder();

        addViews(luaBaseYaml, views);
        return luaBaseYaml.toString();
    }

    /**
     * Fast path: parses {@code base} and converts it to a LuaBase spec map in-place,
     * skipping YAML re-serialization. The returned map is ready to pass directly to
     * {@link io.pskenny.pkspkms.luabase.LuaBaseProcessor#process(Map, io.pskenny.pkspkms.repo.PksFileRepository)}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> convertToMap(String base) {
        Map<String, Object> yamlMap = YAML_TL.get().load(YamlParser.expandLeadingTabs(base));
        List<Map<String, Object>> views = (List<Map<String, Object>>) yamlMap.get("views");
        if (views == null || views.isEmpty()) return yamlMap;

        Map<String, Object> view = views.get(0);
        if (view == null) return yamlMap;

        // Drop keys that LuaBaseProcessor doesn't use (name, columnSize, limit, …)
        // by building a clean replacement map with only the keys we translate.
        Map<String, Object> converted = new java.util.LinkedHashMap<>();
        converted.put("type", view.get("type"));

        // filters — view-level, else inherit top-level (Obsidian shared filters)
        Map<String, Object> filters = (Map<String, Object>) view.get("filters");
        if (filters == null || filters.isEmpty()) {
            filters = (Map<String, Object>) yamlMap.get("filters");
        }
        if (filters != null && !filters.isEmpty()) {
            Map<String, Object> convertedFilters = convertFilters(filters);
            if (!convertedFilters.isEmpty()) {
                converted.put("filters", convertedFilters);
            }
        }

        // order
        List<?> order = (List<?>) view.get("order");
        if (order != null && !order.isEmpty()) {
            List<String> convertedOrder = new ArrayList<>(order.size());
            for (Object o : order) {
                convertedOrder.add(stripYamlQuotes(tryAndConvertValue(o.toString())));
            }
            converted.put("order", convertedOrder);
        }

        // sort — resolve property names but leave the structure intact
        List<Map<String, Object>> sort = (List<Map<String, Object>>) view.get("sort");
        if (sort != null && !sort.isEmpty()) {
            List<Map<String, Object>> convertedSort = new ArrayList<>(sort.size());
            for (Map<String, Object> sortItem : sort) {
                Map<String, Object> convertedItem = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : sortItem.entrySet()) {
                    String v = entry.getValue() != null ? entry.getValue().toString() : null;
                    convertedItem.put(entry.getKey(),
                            v != null ? PropertyMapping.resolve(v) : null);
                }
                convertedSort.add(convertedItem);
            }
            converted.put("sort", convertedSort);
        }

        // formulas (pass through unchanged if present)
        Object formulas = view.get("formulas");
        if (formulas != null) converted.put("formulas", formulas);

        // Replace the single view with the cleaned, converted one
        views.set(0, converted);
        return yamlMap;
    }

    // Converts a filter map (and/or lists of Obsidian expressions) into Lua calls,
    // preserving the logical keys.
    private Map<String, Object> convertFilters(Map<String, Object> filters) {
        Map<String, Object> convertedFilters = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            if (entry.getValue() instanceof List<?> values && isLogicalKey(entry.getKey())) {
                List<String> converted = new ArrayList<>(values.size());
                for (Object v : values) {
                    converted.add(stripYamlQuotes(tryAndConvertExpression(v.toString())));
                }
                convertedFilters.put(entry.getKey(), converted);
            }
        }
        return convertedFilters;
    }

    private boolean isLogicalKey(String key) {
        return key.equalsIgnoreCase("and") || key.equalsIgnoreCase("or")
                || key.equalsIgnoreCase("all") || key.equalsIgnoreCase("any");
    }

    private void addViews(StringBuilder sb, List<?> views) {
        if (views == null || views.isEmpty()) return;

        sb.append("views:\n");
        Map<?, ?> firstViewElement = (Map<?, ?>) views.get(0);
        if (firstViewElement == null) return;

        addIfPresent(sb, "type", (String) firstViewElement.get("type"));
        addFilters(sb, (Map<?, ?>) firstViewElement.get("filters"));
        addOrder(sb, (List<?>) firstViewElement.get("order"));
        addSort(sb, (List<?>) firstViewElement.get("sort"));
    }

    private void addIfPresent(StringBuilder sb, String property, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append("  - ").append(property).append(": ").append(value).append("\n");
        }
    }

    private void addFilters(StringBuilder sb, Map<?, ?> filters) {
        if (filters == null || filters.isEmpty()) return;

        List<?> andValues = (List<?>) filters.get("and");
        if (andValues != null) {
            sb.append("    filters:\n      and:\n");
            for (Object value : andValues) {
                sb.append("        - ")
                        .append(tryAndConvertExpression(value.toString()))
                        .append("\n");
            }
        }
    }

    private void addOrder(StringBuilder sb, List<?> order) {
        if (order == null || order.isEmpty()) return;

        sb.append("    order:\n");
        for (Object o : order) {
            sb.append("      - ")
                    .append(tryAndConvertValue(o.toString()))
                    .append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void addSort(StringBuilder sb, List<?> sort) {
        if (sort == null || sort.isEmpty()) return;

        sb.append("    sort:\n");
        for (Object item : sort) {
            Map<String, String> sortMap = (Map<String, String>) item;
            sb.append("      - ");
            for (Map.Entry<String, String> entry : sortMap.entrySet()) {
                String resolvedValue = PropertyMapping.resolve(entry.getValue());
                sb.append(entry.getKey()).append(": ").append(resolvedValue).append("\n        ");
            }
            sb.setLength(sb.length() - 8); // Clean up final loop trailing indentation white-space
        }
    }

    // Forms that don't fit ParsedQuery: file.inFolder, startsWith, isEmpty.
    // Checked before PropertyMapping rewriting so file.name/basename/path keep their meaning.
    private String trySpecialForms(String jsText) {
        if (jsText == null || jsText.isEmpty()) {
            return null;
        }

        boolean negated = jsText.startsWith("!");
        String clean = negated ? jsText.substring(1).trim() : jsText;
        if (clean.startsWith("(") && clean.endsWith(")")) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        String maybeNegate = negated ? " not " : "";

        Matcher inFolder = IN_FOLDER_PATTERN.matcher(clean);
        if (inFolder.matches()) {
            return "'" + maybeNegate + "fileInFolder(file, \"" + inFolder.group(2) + "\")'";
        }

        Matcher startsWith = STARTS_WITH_PATTERN.matcher(clean);
        if (startsWith.matches()) {
            String prefix = startsWith.group(2);
            switch (startsWith.group(1)) {
                case "file.name": return "'" + maybeNegate + "fileFieldStartsWith(file, \"name\", \"" + prefix + "\")'";
                case "file.basename": return "'" + maybeNegate + "fileFieldStartsWith(file, \"basename\", \"" + prefix + "\")'";
                case "file.ext": return "'" + maybeNegate + "fileFieldStartsWith(file, \"ext\", \"" + prefix + "\")'";
                case "file.path": case "filePath": case "path":
                    return "'" + maybeNegate + "fileFieldStartsWith(file, \"path\", \"" + prefix + "\")'";
                default:
                    return "'" + maybeNegate + "hasPropertyValueStartsWith(file, \"" + startsWith.group(1) + "\", \"" + prefix + "\")'";
            }
        }

        Matcher isEmpty = IS_EMPTY_PATTERN.matcher(clean);
        if (isEmpty.matches()) {
            return "'" + maybeNegate + "hasEmptyProperty(file, \"" + isEmpty.group(1) + "\")'";
        }

        return null;
    }

    // --- Refactored Translation Targets ---

    public String tryAndConvertExpression(String jsText) {
        String special = trySpecialForms(jsText);
        if (special != null) {
            return special;
        }
        return ParsedQuery.parse(jsText)
                .map(query -> {
                    if (query.values().isEmpty()) return jsText;

                    String maybeNegate = query.isNegated() ? " not " : "";

                    // Numeric comparison: value stays unquoted
                    if (query.comparisonOp() != ParsedQuery.ComparisonOp.NONE) {
                        String fn = "hasPropertyValue" + query.comparisonOp().luaFunctionSuffix();
                        return "'" + maybeNegate + fn + "(file, \"" + query.property()
                                + "\", " + query.values().get(0) + ")'";
                    }

                    if (query.isContainsAll()) {
                        return "'" + maybeNegate + "hasPropertyContainingAll(file, \"" + query.property()
                                + "\", " + query.formatLuaArray() + ")'";
                    }

                    // Plain .contains: substring match on text (Obsidian semantics)
                    if (query.isContains()) {
                        return "'" + maybeNegate + "hasPropertyContaining(file, \"" + query.property()
                                + "\", \"" + query.values().get(0) + "\")'";
                    }

                    String functionName = query.isContainsAny() && query.values().size() > 1
                            ? "hasPropertyValueIn" : "hasPropertyValue";

                    String arguments = query.isContainsAny() && query.values().size() > 1
                            ? "\"" + query.property() + "\", " + query.formatLuaArray()
                            : "\"" + query.property() + "\", \"" + query.values().get(0) + "\"";

                    // FIX: Removed the "filePath" if-block that was wrapping things in "[[" and "]]"
                    return "'" + maybeNegate + functionName + "(file, " + arguments + ")'";
                })
                .orElseGet(() -> PropertyMapping.resolve(jsText));
    }

    private String tryAndConvertValue(String jsText) {
        Optional<ParsedQuery> parsed = ParsedQuery.parse(jsText);

        if (parsed.isPresent()) {
            ParsedQuery query = parsed.get();
            if (query.values().isEmpty()) return jsText;

            String maybeNegate = query.isNegated() ? " not " : "";

            if (query.values().size() == 1) {
                return "'" + maybeNegate + " hasPropertyValue(file, \"" + query.property() + "\", \"" + query.values().get(0) + "\")'";
            } else {
                return "'" + maybeNegate + " hasPropertyValueIn(file, \"" + query.property() + "\", " + query.formatLuaArray() + ")'";
            }
        }

        String resolvedProperty = PropertyMapping.resolve(jsText);
        String maybeNegate = jsText.startsWith("!") || jsText.contains("!") ? " not " : "";

        if ("filePath".equals(resolvedProperty)) {
            return LUA_FILE_PATH_VALUE_TEMPLATE;
        } else if ("tags".equals(resolvedProperty)) {
            return LUA_TAGS_TEMPLATE;
        }

        return maybeNegate + "'getPropertyValue(file, \"" + resolvedProperty + "\", \"\"), \"" + resolvedProperty + "\"'";
    }

    /**
     * Strips the outer single-quote YAML scalar markers that {@link #tryAndConvertExpression}
     * and {@link #tryAndConvertValue} add (e.g. {@code 'expr'} → {@code expr}).
     * These quotes are only meaningful as YAML syntax; when building a Map directly
     * they must be removed so the Lua interpreter receives the bare expression.
     */
    private static String stripYamlQuotes(String s) {
        if (s != null && s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
