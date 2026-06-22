package io.pskenny.pkspkms.luabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Arrays;
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

    // --- Regex Patterns ---
    private static final Pattern CONTAINS_ANY_PATTERN = Pattern.compile("([\\w\\.]+)\\.containsAny\\((.*)\\)");
    private static final Pattern VALUE_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern EQUALS_PATTERN = Pattern.compile("([\\w\\.]+)\\s*==\\s*\"([^\"]+)\"");

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

        public static Optional<PropertyMapping> fromObsidian(String key) {
            return Arrays.stream(values())
                    .filter(m -> m.obsidianKey.equals(key) || m.pksKey.equals(key))
                    .findFirst();
        }

        public static String resolve(String key) {
            return fromObsidian(key)
                    .map(m -> {
                        logger.debug("Obsidian specific property found, mapped from {}", key);
                        return m.pksKey;
                    })
                    .orElse(key);
        }
    }

    // --- Immutable Intermediate Expression State ---
    private record ParsedQuery(String property, List<String> values, boolean isNegated, boolean isContainsAny) {
        public static Optional<ParsedQuery> parse(String jsText) {
            if (jsText == null || jsText.isEmpty()) return Optional.empty();

            boolean negated = jsText.startsWith("!");
            String cleanText = negated ? jsText.substring(1) : jsText;

            // Check translation mapping matches up-front
            for (PropertyMapping mapping : PropertyMapping.values()) {
                if (cleanText.startsWith(mapping.obsidianKey)) {
                    cleanText = cleanText.replaceFirst(Pattern.quote(mapping.obsidianKey), mapping.pksKey);
                    break;
                }
            }

            // 1. Try matching containsAny
            Matcher containsMatcher = CONTAINS_ANY_PATTERN.matcher(cleanText);
            if (containsMatcher.matches()) {
                String property = PropertyMapping.resolve(containsMatcher.group(1));
                List<String> values = new ArrayList<>();
                Matcher valMatcher = VALUE_PATTERN.matcher(containsMatcher.group(2));
                while (valMatcher.find()) {
                    values.add(valMatcher.group(1));
                }
                return Optional.of(new ParsedQuery(property, values, negated, true));
            }

            // 2. Try matching simple equality
            Matcher equalsMatcher = EQUALS_PATTERN.matcher(cleanText);
            if (equalsMatcher.matches()) {
                String property = PropertyMapping.resolve(equalsMatcher.group(1));
                List<String> values = List.of(equalsMatcher.group(2));
                return Optional.of(new ParsedQuery(property, values, negated, false));
            }

            return Optional.empty();
        }

        public String formatLuaArray() {
            return values.stream()
                    .map(v -> "\"" + v + "\"")
                    .collect(Collectors.joining(", ", "{", "}"));
        }
    }

    // --- Base Application Flow ---

    public String convert(String base) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> yamlMap = yaml.load(base);
        List<?> views = (List<?>) yamlMap.get("views");
        StringBuilder luaBaseYaml = new StringBuilder();

        addViews(luaBaseYaml, views);
        return luaBaseYaml.toString();
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

    // --- Refactored Translation Targets ---

    public String tryAndConvertExpression(String jsText) {
        return ParsedQuery.parse(jsText)
                .map(query -> {
                    if (query.values().isEmpty()) return jsText;

                    String maybeNegate = query.isNegated() ? " not " : "";
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
}

//package io.pskenny.pkspkms.luabase;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.yaml.snakeyaml.Yaml;
//
//import java.util.ArrayList;
//import java.util.Map;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//// Naively converts Obsidian Base text to LuaBase
//public class NaiveBaseToLuaBaseConverter {
//    private static final Logger logger = LoggerFactory.getLogger(NaiveBaseToLuaBaseConverter.class);
//    public static final String FILE_NAME = "file.name";
//
//    // Does not include file.embeds or file.properties
//    Map<String, String> obsidianPropertiesToPksProperties = Map.of(
//            "file.tags", "tags",
//            FILE_NAME, "filePath",
//            "file.backlinks", "backlinks",
//            "file.ctime", "creationDate",
//            "file.ext", "ext",
//            "file.folder", "folder",
////            "file.file", "",
//            "file.mtime", "modificationDate",
//            "file.path", "path",
//            "file.size", "size"
//    );
//
//    public String convert(String base) {
//        // parse, match and hope to God
//        Map yaml = new Yaml().load(base);
//        ArrayList views = (ArrayList) yaml.get("views");
//        StringBuilder luaBaseYaml = new StringBuilder();
//
//        addViews(luaBaseYaml, views);
////        convertFormulas(viewElement);
////        convertSort(viewElement);
//
//        return luaBaseYaml.toString();
//    }
//
//    private void addViews(StringBuilder sb, ArrayList views) {
//        if(views == null || views.isEmpty()) {
//            return;
//        }
//        sb.append("views:\n");
//        Map firstViewElement = (Map) views.get(0);
//
//        if(firstViewElement == null) {
//            return;
//        }
//
//        // add table details
//        addIfPresent(sb, "type", (String) firstViewElement.get("type"));
//
//        addFilters(sb, (Map<String, ArrayList>) firstViewElement.get("filters"));
//        addOrder(sb, (ArrayList<String>) firstViewElement.get("order"));
//        addSort(sb, (ArrayList<Map<String, String>>) firstViewElement.get("sort"));
//    }
//
//    private void addIfPresent(StringBuilder sb, String property, String value) {
//        if (value == null || value.isEmpty()) {
//            return;
//        }
//        sb.append("  - " + property + ": ")
//                .append(value)
//                .append("\n");
//    }
//
//    private void addFilters(StringBuilder sb, Map<String, ArrayList> filters) {
//        if (filters == null || filters.isEmpty()) {
//            return;
//        }
//        sb.append("    filters:\n");
//
//        for (Map.Entry<String, ArrayList> entry : filters.entrySet()) {
//            switch (entry.getKey()) {
//                case "and":
//                    sb.append("      and:\n");
//                    ArrayList values = entry.getValue();
//                    for (Object value : values) {
//                        sb.append("        - ")
//                                .append(tryAndConvertExpression(value.toString()))
//                                .append("\n");
//                    }
//                    break;
//                case "or":
//                    break;
//                default:
//                    break;
//            }
//        }
//    }
//
//    private void addOrder(StringBuilder sb, ArrayList<String> order) {
//        if (order == null || order.isEmpty()) {
//            return;
//        }
//
//        sb.append("    order:\n");
//
//        for (String o : order) {
//            sb.append("      - ")
//                    .append(tryAndConvertValue(o))
//                    .append("\n");
//        }
//    }
//
//    private void addSort(StringBuilder sb, ArrayList<Map<String, String>> sort) {
//        if (sort == null || sort.isEmpty()) {
//            return;
//        }
//
//        sb.append("    sort:\n");
//        for (Map<String, String> m : sort) {
//            sb.append("      - ");
//            for (var entry : m.entrySet()) {
//                String value = entry.getValue();
//                if (obsidianPropertiesToPksProperties.containsKey(value)) {
//                    value = obsidianPropertiesToPksProperties.get((value));
//                }
//                sb.append(entry.getKey()).append(": ").append(value).append("\n        ");
//            }
//        }
//    }
//
//    // Visible for testing
//    public String tryAndConvertExpression(String jsText) {
//        if (jsText.isEmpty()) {
//            return "";
//        }
//
//        String maybeNegate = jsText.startsWith("!") ? " not " : "";
//        if (!maybeNegate.isEmpty()) {
//            jsText = jsText.substring(1);
//        }
//
//        for (Map.Entry<String, String> entry : obsidianPropertiesToPksProperties.entrySet()) {
//            if (jsText.startsWith(entry.getKey())) {
//                jsText = jsText.replaceFirst(entry.getKey(), entry.getValue());
//                break;
//            }
//        }
//
//        // Is a containsAny, with one or more values
//        String containsAny = "([\\w\\.]+)\\.containsAny\\((.*)\\)";
//        Pattern containsAnyPattern = Pattern.compile(containsAny);
//        Matcher matcher = containsAnyPattern.matcher(jsText);
//        if (matcher.matches()) {
//            var property = matcher.group(1);
//            var argsString = matcher.group(2);
//
//            // Extract all quoted values from the argument list
//            java.util.List<String> values = new java.util.ArrayList<>();
//            Pattern valuePattern = Pattern.compile("\"([^\"]+)\"");
//            Matcher valueMatcher = valuePattern.matcher(argsString);
//            while (valueMatcher.find()) {
//                values.add(valueMatcher.group(1));
//            }
//
//            if (values.isEmpty()) {
//                return jsText; // couldn't parse values, return as-is
//            }
//
//            // check and swap out Obsidian names
//            if (obsidianPropertiesToPksProperties.containsKey(property)) {
//                logger.debug("obsidian specific thing found, from " + property);
//                property = obsidianPropertiesToPksProperties.get(property);
//            }
//
//            if (values.size() == 1) {
//                var value = values.get(0);
//                if (property.equals("filePath")) {
//                    return "'\"[[\" .. ( " + maybeNegate + " hasPropertyValue(file, \"" + property + "\", \"" + value + "\")) .. \"]]\"'";
//                }
//                return "'" + maybeNegate + "hasPropertyValue(file, \""+ property + "\", \"" + value + "\")'";
//            } else {
//                StringBuilder valuesArray = new StringBuilder("{");
//                for (int i = 0; i < values.size(); i++) {
//                    if (i > 0) valuesArray.append(", ");
//                    valuesArray.append("\"").append(values.get(i)).append("\"");
//                }
//                valuesArray.append("}");
//                if (property.equals("filePath")) {
//                    return "'\"[[\" .. ( " + maybeNegate + " hasPropertyValueIn(file, \"" + property + "\", " + valuesArray + ")) .. \"]]\"'";
//                }
//                return "'" + maybeNegate + "hasPropertyValueIn(file, \""+ property + "\", " + valuesArray + ")'";
//            }
//        }
//
//        // Is a simple ==
//        String equals = "([\\w\\.]+)\\s*==\\s*\"([^\"]+)\"";
//        Pattern equalsPattern = Pattern.compile(equals);
//        Matcher equalsMatcher = equalsPattern.matcher(jsText);
//        var equalsMatched = equalsMatcher.matches();
//        if (equalsMatched) {
//            var property = equalsMatcher.group(1);
//            var value = equalsMatcher.group(2);
//            // check and swap out Obsidian names
//            if (obsidianPropertiesToPksProperties.containsKey(property)) {
//                logger.debug("obsidian specific thing found, from " + property);
//                property = obsidianPropertiesToPksProperties.get(property);
//
//                if (property.equals("filePath")) {
//                    return "'\"[[\" .. (" + maybeNegate + " hasPropertyValue(file, \"" + property + "\", \"" + value + "\")) .. \"]]\"'";
//                }
//            }
//
//            return "'" + maybeNegate + "hasPropertyValue(file, \""+ property + "\", \"" + value + "\")'";
//        }
//
//        // check and swap out Obsidian names
//        if (obsidianPropertiesToPksProperties.containsKey(jsText)) {
//            logger.debug("Obsidian specific thing found, from " + jsText);
//            jsText = obsidianPropertiesToPksProperties.get(jsText);
//        }
//
//        return jsText;
//    }
//
//    // Visible for testing
//    private String tryAndConvertValue(String jsText) {
//        if (jsText.isEmpty()) {
//            return "";
//        }
//        String maybeNegate = jsText.startsWith("!") ? " not " : "";
//
//        // Is a containsAny, with one or more values
//        String containsAny = "([\\w\\.]+)\\.containsAny\\((.*)\\)";
//        Pattern containsAnyPattern = Pattern.compile(containsAny);
//        Matcher matcher = containsAnyPattern.matcher(jsText);
//        if (matcher.matches()) {
//            var property = matcher.group(1);
//            var argsString = matcher.group(2);
//
//            java.util.List<String> values = new java.util.ArrayList<>();
//            Pattern valuePattern = Pattern.compile("\"([^\"]+)\"");
//            Matcher valueMatcher = valuePattern.matcher(argsString);
//            while (valueMatcher.find()) {
//                values.add(valueMatcher.group(1));
//            }
//
//            if (values.isEmpty()) {
//                return jsText;
//            }
//
//            if (obsidianPropertiesToPksProperties.containsKey(property)) {
//                property = obsidianPropertiesToPksProperties.get(property);
//            }
//
//            if (values.size() == 1) {
//                return "'" + maybeNegate + " hasPropertyValue(file, \""+ property + "\", \"" + values.get(0) + "\")'";
//            } else {
//                StringBuilder valuesArray = new StringBuilder("{");
//                for (int i = 0; i < values.size(); i++) {
//                    if (i > 0) valuesArray.append(", ");
//                    valuesArray.append("\"").append(values.get(i)).append("\"");
//                }
//                valuesArray.append("}");
//                return "'" + maybeNegate + " hasPropertyValueIn(file, \""+ property + "\", " + valuesArray + ")'";
//            }
//        }
//
//        // check and swap out Obsidian names
//        if (obsidianPropertiesToPksProperties.containsKey(jsText)) {
//            logger.debug("obsidian specific thing found, from " + jsText);
//            jsText = obsidianPropertiesToPksProperties.get(jsText);
//        }
//
//        if (jsText.equals("filePath")) {
//            return "'\"[[\" .. getPropertyValue(file, \"" + jsText + "\", \"\") .. \"]]\", \"" + jsText + "\"'";
//        }
//        else if (jsText.equals("tags")) {
//            return "'table.concat( (function() local t = {}; local tags_array = (" + maybeNegate + " getPropertyValue(file, \"tags\", \"\") or {}):toArray(); for i=1, #tags_array do local v = tags_array[i]; table.insert(t, \"#\" .. v) end; return t end)(), \" \"), \"tags\"'";
//        }
//
//        return maybeNegate +  "'getPropertyValue(file, \"" + jsText + "\", \"\"), \"" + jsText + "\"'";
//    }
//}
