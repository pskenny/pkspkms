package io.github.pskenny.luabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Naively converts Obsidian Base text to LuaBase
public class NaiveBaseToLuaBaseConverter {
    private static final Logger logger = LoggerFactory.getLogger(NaiveBaseToLuaBaseConverter.class);
    public static final String FILE_NAME = "file.name";

    // Does not include file.embeds or file.properties
    Map<String, String> obsidianPropertiesToPksProperties = Map.of(
            "file.tags", "tags",
            FILE_NAME, "filePath",
            "file.backlinks", "backlinks",
            "file.ctime", "creationDate",
            "file.ext", "ext",
            "file.folder", "folder",
//            "file.file", "",
            "file.mtime", "modificationDate",
            "file.path", "path",
            "file.size", "size"
    );

    public String convert(String base) {
        // parse, match and hope to God
        Map yaml = new Yaml().load(base);
        ArrayList views = (ArrayList) yaml.get("views");
        StringBuilder luaBaseYaml = new StringBuilder();

        addViews(luaBaseYaml, views);
//        convertFormulas(viewElement);
//        convertSort(viewElement);

        return luaBaseYaml.toString();
    }

    private void addViews(StringBuilder sb, ArrayList views) {
        if(views == null || views.isEmpty()) {
            return;
        }
        sb.append("views:\n");
        Map firstViewElement = (Map) views.get(0);

        if(firstViewElement == null) {
            return;
        }

        // add table details
        addIfPresent(sb, "type", (String) firstViewElement.get("type"));

        addFilters(sb, (Map<String, ArrayList>) firstViewElement.get("filters"));
        addOrder(sb, (ArrayList<String>) firstViewElement.get("order"));
        addSort(sb, (ArrayList<Map<String, String>>) firstViewElement.get("sort"));
    }

    private void addIfPresent(StringBuilder sb, String property, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        sb.append("  - " + property + ": ")
                .append(value)
                .append("\n");
    }

    private void addFilters(StringBuilder sb, Map<String, ArrayList> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        sb.append("    filters:\n");

        for (Map.Entry<String, ArrayList> entry : filters.entrySet()) {
            switch (entry.getKey()) {
                case "and":
                    sb.append("      and:\n");
                    ArrayList values = entry.getValue();
                    for (Object value : values) {
                        // TODO: check if the value is "and" or "or"
                        sb.append("        - ")
                                .append(tryAndConvertExpression(value.toString()))
                                .append("\n");
                        // TODO swap out the values
                    }
                    break;
                case "or":
                    break;
                default:
                    break;
            }
        }
    }

    private void addOrder(StringBuilder sb, ArrayList<String> order) {
        if (order == null || order.isEmpty()) {
            return;
        }

        sb.append("    order:\n");

        for (String o : order) {
            sb.append("      - ")
                    .append(tryAndConvertValue(o))
                    .append("\n");
        }
    }

    private void addSort(StringBuilder sb, ArrayList<Map<String, String>> sort) {
        if (sort == null || sort.isEmpty()) {
            return;
        }

        sb.append("    sort:\n");
        for (Map<String, String> m : sort) {
            sb.append("      - ");
            for (var entry : m.entrySet()) {
                String value = entry.getValue();
                if (obsidianPropertiesToPksProperties.containsKey(value)) {
                    value = obsidianPropertiesToPksProperties.get((value));
                }
                sb.append(entry.getKey()).append(": ").append(value).append("\n        ");
            }
        }
    }

    // Visible for testing
    private String tryAndConvertExpression(String jsText) {
        if (jsText.isEmpty()) {
            return "";
        }

        String maybeNegate = jsText.startsWith("!") ? " not " : "";
        if (!maybeNegate.isEmpty()) {
            jsText = jsText.substring(1);
        }

        for (String obsidianSwap : obsidianPropertiesToPksProperties.keySet()) {
            if (jsText.startsWith(obsidianSwap)) {
                jsText = jsText.replaceFirst(obsidianSwap, obsidianPropertiesToPksProperties.get(obsidianSwap));
                break;
            }
        }

        // Is a simple containsAny, with one value, i.e. not containsAny
        String containsAny = "([\\w\\.]+)\\.containsAny\\(\\\"([^\\\"]+)\\\"\\)";
        Pattern containsAnyPattern = Pattern.compile(containsAny);
        Matcher matcher = containsAnyPattern.matcher(jsText);
        var matched = matcher.matches();
        if (matched) {
            var property = matcher.group(1);
            var value = matcher.group(2);
            // check and swap out Obsidian names
            if (obsidianPropertiesToPksProperties.containsKey(property)) {
                logger.debug("obsidian specific thing found, from " + property);
                property = obsidianPropertiesToPksProperties.get(property).toString();

                if (property.equals("filePath")) {
                    return "'\"[[\" .. ( " + maybeNegate + " hasPropertyValue(file, \"" + property + "\", \"" + value + "\")) .. \"]]\"'";
                }
            }

            return "'" + maybeNegate + "hasPropertyValue(file, \""+ property + "\", \"" + value + "\")'";
        }

        // Is a simple ==
        String equals = "([\\w\\.]+)\\s*==\\s*\"([^\"]+)\"";
        Pattern equalsPattern = Pattern.compile(equals);
        Matcher equalsMatcher = equalsPattern.matcher(jsText);
        var equalsMatched = equalsMatcher.matches();
        if (equalsMatched) {
            var property = equalsMatcher.group(1);
            var value = equalsMatcher.group(2);
            // check and swap out Obsidian names
            if (obsidianPropertiesToPksProperties.containsKey(property)) {
                logger.debug("obsidian specific thing found, from " + property);
                property = obsidianPropertiesToPksProperties.get(property).toString();

                if (property.equals("filePath")) {
                    return "'\"[[\" .. (" + maybeNegate + " hasPropertyValue(file, \"" + property + "\", \"" + value + "\")) .. \"]]\"'";
                }
            }

            return "'" + maybeNegate + "hasPropertyValue(file, \""+ property + "\", \"" + value + "\")'";
        }

        // check and swap out Obsidian names
        if (obsidianPropertiesToPksProperties.containsKey(jsText)) {
            logger.debug("Obsidian specific thing found, from " + jsText);
            jsText = obsidianPropertiesToPksProperties.get(jsText).toString();
        }

        return jsText;
    }

    // Visible for testing
    private String tryAndConvertValue(String jsText) {
        if (jsText.isEmpty()) {
            return "";
        }
        String maybeNegate = jsText.startsWith("!") ? " not " : "";

        // Is a simple containsAny, with one value
        String containsAny = "(\\w+)\\.containsAny\\(\"([^\"]+)\"\\)";
        Pattern containsAnyPattern = Pattern.compile(containsAny);
        Matcher matcher = containsAnyPattern.matcher(jsText);
        var matched = matcher.matches();
        if (matched) {
            var property = matcher.group(1);
            var value = matcher.group(2);

            return "'" + maybeNegate + " hasPropertyValue(file, \""+ property + "\", \"" + value + "\")'";
        }

        // check and swap out Obsidian names
        if (obsidianPropertiesToPksProperties.containsKey(jsText)) {
            logger.debug("obsidian specific thing found, from " + jsText);
            jsText = obsidianPropertiesToPksProperties.get(jsText).toString();
        }

        if (jsText.equals("filePath")) {
            return "'\"[[\" .. getPropertyValue(file, \"" + jsText + "\", \"\") .. \"]]\", \"" + jsText + "\"'";
        }
        else if (jsText.equals("tags")) {
            return "'table.concat( (function() local t = {}; local tags_array = (" + maybeNegate + " getPropertyValue(file, \"tags\", \"\") or {}):toArray(); for i=1, #tags_array do local v = tags_array[i]; table.insert(t, \"#\" .. v) end; return t end)(), \" \"), \"tags\"'";
        }

        return maybeNegate +  "'getPropertyValue(file, \"" + jsText + "\", \"\"), \"" + jsText + "\"'";
    }
}
