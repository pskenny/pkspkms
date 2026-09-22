package io.pskenny.pkspkms.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PksFile {

    private Map<String, Object> properties;
    private String hash;
    private long lastModified;

    public PksFile(String filePath, Map<String, Object> properties) {
        this.properties = new LinkedHashMap<>(properties);
        this.properties.put("filePath", filePath);
    }

    public PksFile(java.io.File file, String directory, Map<String, Object> properties) {
        String filePath = getFilePath(file, directory);
        this.properties = new LinkedHashMap<>(properties);
        this.properties.put("filePath", filePath);
    }

    public PksFile(PksFile source) {
        this.properties = deepCopy(source.properties);
        this.hash = source.hash;
        this.lastModified = source.lastModified;
    }

    public PksFile(PksFile source, String newFilePath) {
        this.properties = deepCopy(source.properties);
        this.properties.put("filePath", newFilePath);
        this.hash = source.hash;
        this.lastModified = source.lastModified;
    }

    // Copies are independent: without deep-copying lists, a copied file's
    // addAll (addToProperty) silently mutated the source's lists too
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() instanceof List<?> list
                    ? new ArrayList<>(list)
                    : entry.getValue());
        }
        return copy;
    }

    private String getFilePath(java.io.File file, String directory) {
        try {
            String dirCanonical = new java.io.File(directory).getCanonicalPath();
            String fileCanonical = file.getCanonicalPath();
            if (!fileCanonical.startsWith(dirCanonical)) {
                throw new IllegalStateException("File " + fileCanonical + " is not under " + dirCanonical);
            }
            return fileCanonical.substring(dirCanonical.length() + 1)
                    .replace(java.io.File.separatorChar, '/');
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getAsList(String key) {
        Object value = properties.get(key);
        if (value instanceof List) {
            return (List<T>) value;
        }
        return new ArrayList<>();
    }

    public void addToProperty(String key, List<String> items) {
        Object existing = properties.get(key);
        if (existing instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<String> typed = (List<String>) list;
            typed.addAll(items);
        } else {
            properties.put(key, new ArrayList<>(items));
        }
    }

    public String getFilePath() {
        return (String) properties.get("filePath");
    }

    public String getHash() {
        return this.hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public long getLastModified() {
        return this.lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    /**
     * Returns the live, mutable property map. Changes made to the returned map
     * affect this PksFile instance permanently.
     */
    public Map<String, Object> getMutableProperties() {
        return this.properties;
    }

    public void filterProperties(List<String> include, List<String> exclude) {
        var filteredProperties = new LinkedHashMap<>(this.properties);

        if (include != null && !include.isEmpty()) {
            filteredProperties.keySet().retainAll(include);
        }
        if (exclude != null && !exclude.isEmpty()) {
            exclude.forEach(filteredProperties::remove);
        }

        this.properties = filteredProperties;
    }
}
