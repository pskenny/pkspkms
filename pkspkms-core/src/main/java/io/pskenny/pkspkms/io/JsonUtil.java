package io.pskenny.pkspkms.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

public class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String pksFilesToJson(Collection<PksFile> files) {
        Set<Map<String, Object>> filesProperties = files.stream()
                        .map(PksFile::getProperties)
                        .collect(Collectors.toSet());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultSize", filesProperties.size());
        result.put("files", filesProperties);

        try {
            return MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize files to JSON", e);
        }
    }

    public static String fileToJson(PksFile file) {
        try {
            return MAPPER.writeValueAsString(file);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize file to JSON", e);
        }
    }
}
