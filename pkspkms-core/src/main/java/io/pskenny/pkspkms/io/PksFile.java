package io.pskenny.pkspkms.io;

import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PksFile implements java.io.Serializable {

    private transient final String filePath;
    private Map<String, Object> properties;
    private final transient File file;

    public PksFile(String filePath, Map<String, Object> properties) {
        this.filePath = filePath;
        this.properties = new HashMap<>(properties);
        this.file = new File(filePath);

        this.properties.put("filePath", filePath);
    }

    public PksFile(File file, String directory, Map<String, Object> properties) {
        this.properties = new HashMap<>(properties);
        this.file = file;
        try {
            var dirPath = java.nio.file.Paths.get(directory).toRealPath();
            var filePathReal = file.toPath().toRealPath();
            filePath = dirPath.relativize(filePathReal).toString().replace(java.io.File.separatorChar, '/');
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        this.properties.put("filePath", filePath);
    }

    public PksFile(PksFile source) {
        this.filePath = source.filePath;
        this.properties = new HashMap<>(source.getProperties());
        this.file = new File(source.filePath);
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
        List<String> list = (List<String>) properties.computeIfAbsent(key, k -> new ArrayList<String>());
        list.addAll(items);
    }

    public File getFile() {
        return this.file;
    }

    public String getFilePath() {
        return this.filePath;
    }

    public Map<String, Object> getProperties() {
        return this.properties;
    }

    public void filterProperties(List<String> include, List<String> exclude) {
        var filteredProperties = new HashMap<>(this.properties);

        if (include != null && !include.isEmpty()) {
            filteredProperties.keySet().retainAll(include);
        }
        if (exclude != null && !exclude.isEmpty()) {
            exclude.forEach(filteredProperties::remove);
        }

        this.properties = filteredProperties;
    }
}