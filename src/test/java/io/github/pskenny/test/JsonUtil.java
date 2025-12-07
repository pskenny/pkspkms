package io.github.pskenny.test;

import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

public class JsonUtil {

    public static void assertJsonEquals(String expectedJson, String actualJson, boolean strict) {
        try {
            JSONCompareMode mode = strict ? JSONCompareMode.STRICT : JSONCompareMode.LENIENT;
            JSONAssert.assertEquals(expectedJson, actualJson, mode);
        } catch (JSONException e) {
            throw new RuntimeException("JSON comparison failed", e);
        }
    }
}
