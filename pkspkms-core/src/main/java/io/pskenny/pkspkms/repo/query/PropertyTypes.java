package io.pskenny.pkspkms.repo.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Property types declared in a vault's .obsidian/types.json (Obsidian Bases).
 * Queries compile comparisons according to the declared type instead of
 * guessing from the bound's shape.
 */
public final class PropertyTypes {

    public enum Kind { NUMBER, DATE, DATETIME, TEXT, MULTITEXT, CHECKBOX, UNDECLARED }

    private static final Logger logger = LoggerFactory.getLogger(PropertyTypes.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final PropertyTypes EMPTY = new PropertyTypes(Map.of());

    private final Map<String, Kind> kinds;

    private PropertyTypes(Map<String, Kind> kinds) {
        this.kinds = kinds;
    }

    public static PropertyTypes empty() {
        return EMPTY;
    }

    public static PropertyTypes of(Map<String, String> obsidianTypes) {
        Map<String, Kind> kinds = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : obsidianTypes.entrySet()) {
            kinds.put(e.getKey(), kindFor(e.getValue()));
        }
        return new PropertyTypes(kinds);
    }

    public static PropertyTypes parse(InputStream in) {
        try {
            var root = MAPPER.readValue(in, new TypeReference<Map<String, Object>>() {});
            Object typesNode = root.get("types");
            if (!(typesNode instanceof Map<?, ?> typesMap)) {
                logger.warn("types.json has no 'types' object, falling back to heuristics");
                return EMPTY;
            }
            Map<String, Kind> kinds = new java.util.HashMap<>();
            for (Map.Entry<?, ?> e : typesMap.entrySet()) {
                if (e.getKey() instanceof String name && e.getValue() != null) {
                    kinds.put(name, kindFor(e.getValue().toString()));
                }
            }
            return new PropertyTypes(kinds);
        } catch (Exception e) {
            logger.warn("Could not parse .obsidian/types.json, falling back to heuristics", e);
            return EMPTY;
        }
    }

    private static Kind kindFor(String obsidianType) {
        return switch (obsidianType.toLowerCase(Locale.ROOT)) {
            case "number" -> Kind.NUMBER;
            case "date" -> Kind.DATE;
            case "datetime" -> Kind.DATETIME;
            case "checkbox" -> Kind.CHECKBOX;
            case "multitext", "aliases", "tags" -> Kind.MULTITEXT;
            default -> Kind.TEXT;
        };
    }

    public Kind typeOf(String field) {
        Kind kind = kinds.get(field);
        return kind == null ? Kind.UNDECLARED : kind;
    }
}
