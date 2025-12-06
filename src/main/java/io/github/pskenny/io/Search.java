package io.github.pskenny.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Search {
    private static final Logger logger = LoggerFactory.getLogger(Search.class);

    public static boolean matchesProperties(PksFile file, String query) {
        return matchesProperties(file, parse(query));
    }

    public static Map<String, List<String>> parse(String queryString) {
        Map<String, List<String>> params = new HashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return params;
        }

        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf("=");
            String key;
            String value;

            if (idx > 0) { // Key and value exist
                key = decode(pair.substring(0, idx));
                value = decode(pair.substring(idx + 1));
            } else { // Key only, no value (e.g., "key&")
                key = decode(pair);
                value = ""; // Assign an empty string as value
            }
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

        return params;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            logger.error("Error decoding string: {} - {}", s, e.getMessage());
            return s;
        }
    }

    public static boolean matchesProperties(PksFile file, Map<String, List<String>> params) {
        if (params.isEmpty()) {
            return true;
        }
        Map<String, List<String>> inclusions = getInclusions(params);
        Map<String, List<String>> exclusions = getExclusions(params);
        boolean hasInclusions = hasInclusions(file, inclusions);
        if (!hasInclusions) {
            return false;
        }
        return hasExclusions(file, exclusions);
    }

    private static boolean hasInclusions(PksFile file, Map<String, List<String>> params) {
        Map<String, Object> fileProperties = file.getProperties();

        for (var parameter : params.entrySet()) {
            String parameterKey = parameter.getKey();
            List<String> paramValues = parameter.getValue();

            for (String parameterValue : paramValues) {
                if (parameterValue.isEmpty() && fileProperties.containsKey(parameterKey)) {
                    // this means we should just check the file has that property,
                    // not compare the value
                    continue;
                }
                boolean paramMatch = doesParamMatchFileProperties(parameterKey, parameterValue, fileProperties);
                // all inclusions must be present so if there's not a match, return false
                if (!paramMatch) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean hasExclusions(PksFile file, Map<String, List<String>> params) {
        Map<String, Object> fileProperties = file.getProperties();

        for (var parameter : params.entrySet()) {
            String parameterKey = parameter.getKey();
            List<String> paramValues = parameter.getValue();

            for (String parameterValue : paramValues) {
                if (parameterValue.isEmpty()
                        && !fileProperties.containsKey(parameterKey)) {
                    // this means we should just check the file doesn't have that property,
                    // not compare the value
                    continue;
                }
                boolean paramMatch = doesParamMatchFileProperties(parameterKey, parameterValue, fileProperties);

                if (paramMatch) {
                    // now you've matching something you shouldn't have
                    return false;
                }
            }
        }

        return true;
    }

    private static Map<String, List<String>> getInclusions(Map<String, List<String>> params) {
        Map<String, List<String>> inclusions = new HashMap<>();
        for (var parameter : params.entrySet()) {
            String paramProperty = parameter.getKey();
            if (!paramProperty.startsWith("!")) {
                inclusions.put(paramProperty, parameter.getValue());
            }
        }
        return inclusions;
    }

    private static Map<String, List<String>> getExclusions(Map<String, List<String>> params) {
        Map<String, List<String>> exclusions = new HashMap<>();
        for (var parameter : params.entrySet()) {
            String paramProperty = parameter.getKey();
            if (paramProperty.startsWith("!")) {
                exclusions.put(paramProperty.substring(1), parameter.getValue());
            }
        }
        return exclusions;
    }

    private static boolean doesParamMatchFileProperties(
            String paramPropertyName,
            String paramPropertyValue,
            Map<String, Object> fileProperties
    ) {
        if (fileProperties.containsKey(paramPropertyName)) {
            Object fileProperty = fileProperties.get(paramPropertyName);
            if (fileProperty instanceof String[]) {
                String[] f = (String[]) fileProperty;
                return matchList(new ArrayList<>(List.of(f)), paramPropertyValue);
            } else if (fileProperty instanceof String) {
                return matchString((String) fileProperty, paramPropertyValue);
            } else if (fileProperty instanceof List<?> list) {
                ArrayList paramList = new ArrayList<>((List) fileProperty);
                return matchList(paramList, paramPropertyValue);
            } else if (fileProperty instanceof ArrayList<?>) {
                ArrayList paramList = (ArrayList) fileProperty;
                return matchList(paramList, paramPropertyValue);
            } else if (fileProperty instanceof Boolean) {
                return (boolean) fileProperty == Boolean.parseBoolean(paramPropertyValue);
            } else if (fileProperty instanceof Integer) {
                return matchNumber((Integer) fileProperty, paramPropertyValue);
            }
        }
        return false;
    }

    private static boolean matchString(String propertyValue, String parameterValue) {
        if (parameterValue.endsWith(("*"))) {
            parameterValue = parameterValue.substring(0, parameterValue.length() - 1);
            return propertyValue.startsWith(parameterValue);
        } else if (parameterValue.startsWith("*")) {
            parameterValue = parameterValue.substring(1, parameterValue.length());
            return propertyValue.endsWith(parameterValue);
        } else if (parameterValue.startsWith(">")) {
            parameterValue = parameterValue.substring(1, parameterValue.length());
            try {
                return Integer.parseInt(propertyValue) > Integer.parseInt(parameterValue);
            } catch (NumberFormatException e) {
                System.err.println("Not a number: " + parameterValue);
                return false;
            }
        } else if (parameterValue.startsWith("<")) {
            try {
                return Integer.parseInt(propertyValue) > Integer.parseInt(parameterValue);
            } catch (NumberFormatException e) {
                System.err.println("Not a number: " + parameterValue);
                return false;
            }
        } else {
            return propertyValue.equals(parameterValue);
        }
    }

    // there ain't no way to escape out of the asterisk and search for one that is at the start or end
    private static boolean matchList(ArrayList<String> propertyValues, String paramValue) {
        if (paramValue.endsWith(("*"))) {
            paramValue = paramValue.substring(0, paramValue.length() - 1);
            for (var propertyValue : propertyValues) {
                // might this only check the fitst value? if there are many
                if(propertyValue == null) {
                    continue;
                }
                if (propertyValue.startsWith(paramValue)) {
                    return true;
                }
            }
        } else if (paramValue.startsWith("*")) {
            paramValue = paramValue.substring(1, paramValue.length());
            for (var propertyValue : propertyValues) {
                if (propertyValue.endsWith(paramValue)) {
                    return true;
                }
            }
        } else if (paramValue.startsWith(">")) {
            paramValue = paramValue.substring(1, paramValue.length());
            for (var propertyValue : propertyValues) {
                if (propertyValue.endsWith(paramValue)) {
                    return true;
                }
            }
        } else if (paramValue.startsWith("<")) {
            paramValue = paramValue.substring(1, paramValue.length());
            for (var propertyValue : propertyValues) {
                if (propertyValue.endsWith(paramValue)) {
                    return true;
                }
            }
        } else {
            return propertyValues.contains(paramValue);
        }
        return false;
    }

    private static boolean matchNumber(Integer propertyValue, String paramValue) {
        if (paramValue.startsWith(">")) {
            paramValue = paramValue.substring(1);
            return propertyValue > Integer.parseInt(paramValue);
        } else if (paramValue.startsWith("<")) {
            paramValue = paramValue.substring(1);
            return propertyValue < Integer.parseInt(paramValue);
        } else {
            return propertyValue == Integer.parseInt(paramValue);
        }
    }
}
