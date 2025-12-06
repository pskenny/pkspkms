package io.github.pskenny.io;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PksFile implements java.io.Serializable {

    private transient final String filePath;
    private Map<String, Object> properties;
    private final transient File file;

    public PksFile(String filePath, Map<String, Object> properties) {
        this.filePath = filePath;
        this.properties = properties;
        this.file = new File(filePath);

        this.properties.put("filePath", filePath);
    }

    public PksFile(File file, String directory, Map<String, Object> properties) {
        this.properties = properties;
        this.file = file;
        try {
            int substringStart = file.getCanonicalPath().indexOf(directory) + directory.length();
            filePath = file.getCanonicalPath().substring(substringStart);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.properties.put("filePath", filePath);
    }

    public PksFile(PksFile source) {
        this.filePath = source.filePath;
        this.properties = new HashMap<>(source.properties);
        this.file = new File(source.filePath);
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