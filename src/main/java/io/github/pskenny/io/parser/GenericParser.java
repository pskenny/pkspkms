package io.github.pskenny.io.parser;

import io.github.pskenny.io.PksFile;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class GenericParser implements Parser {
    @Override
    public PksFile parse(File file, String directory) throws IOException {
        return new PksFile(file, directory, new HashMap<>());
    }

    public PksFile parse(File file) throws IOException {
        Map<String, Object> fileProperties = new HashMap<>();
        fileProperties.put("filePath", file.getCanonicalPath());

        return new PksFile(file.getAbsolutePath(), fileProperties);
    }
}
