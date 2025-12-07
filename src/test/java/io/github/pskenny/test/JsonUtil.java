package io.github.pskenny.test;

import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JsonUtil {
    public static String readJsonFile(String relativePath) {
        try {
            Path path = Paths.get(System.getProperty("user.dir"), relativePath);
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read test data file: " + relativePath, e);
        }
    }

    public static void assertJsonEquals(String expectedJson, String actualJson, boolean strict) {
        try {
            JSONCompareMode mode = strict ? JSONCompareMode.STRICT : JSONCompareMode.LENIENT;
            JSONAssert.assertEquals(expectedJson, actualJson, mode);
        } catch (JSONException e) {
            throw new RuntimeException("JSON comparison failed", e);
        }
    }
}
