package io.github.pskenny.io;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.stream.Collectors;

public class JsonUtil {
    public static String pksFilesToJson(Collection<PksFile> files) {
        Set<Map<String, Object>> filesProperties = files.stream()
                        .map(PksFile::getProperties)
                        .collect(Collectors.toSet());

        Gson gson = new Gson();
        JsonObject result = new JsonObject();
        result.add("resultSize", gson.toJsonTree(filesProperties.size()));
        result.add("files", gson.toJsonTree(filesProperties));

        return gson.toJson(result);
    }

    public static String fileToJson(PksFile file) {
        Gson gson = new Gson();
        return gson.toJson(file);
    }
}
