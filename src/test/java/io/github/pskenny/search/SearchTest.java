package io.github.pskenny.search;

import io.github.pskenny.io.PksFile;
import io.github.pskenny.io.Search;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// inclusions follow AND (INNER JOIN) and exclusions follow OR?
public class SearchTest extends TestCase {
    public final String FILE_PROPERTY_TAGS_INCLUSION = "tags";
    public final String FILE_PROPERTY_TAGS_EXCLUSION = "!tags";

    public SearchTest(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(SearchTest.class);
    }

    public void testApp() {
        PksFile file = getFile();
        Map<String, List<String>> params = getParameters();
        Set<PksFile> results = Stream.of(file)
                .filter(pksFile -> Search.matchesProperties(pksFile, params))
                .collect(Collectors.toSet());

        assert (results.size() == 1);
    }

    public void testInclusionFilteringMany() {
        PksFile file1 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag1", "tag2"));
        PksFile file2 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag3", "tag4"));
        PksFile file3 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag1", "tag3"));

        Map<String, List<String>> params = getParameters(FILE_PROPERTY_TAGS_INCLUSION, "tag1");

        Set<PksFile> results = Stream.of(file1, file2, file3)
                .filter(pksFile -> Search.matchesProperties(pksFile, params))
                .collect(Collectors.toSet());

        assert (results.size() == 2);
    }

    public void testExclusionManyFilteringMany() {
        PksFile file1 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag1", "tag2"));
        PksFile file2 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag3", "tag4"));
        PksFile file3 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag1", "tag3"));
        PksFile file4 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag5", "tag6"));

        Map<String, List<String>> params = getParameters("!tags", "tag1");
        params.put("!tags", List.of("tag5", "tag1"));

        Set<PksFile> results = Stream.of(file1, file2, file3, file4)
                .filter(pksFile -> Search.matchesProperties(pksFile, params))
                .collect(Collectors.toSet());

        // should be file2 gets though
        assert (results.size() == 1);
    }

    public void testExclusionFilteringMany() {
        PksFile file1 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag1", "tag2"));
        PksFile file2 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag3", "tag4"));
        PksFile file3 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag1", "tag3"));

        Map<String, List<String>> params = getParameters(FILE_PROPERTY_TAGS_EXCLUSION, "tag1");

        Set<PksFile> results = Stream.of(file1, file2, file3)
                .filter(pksFile -> Search.matchesProperties(pksFile, params))
                .collect(Collectors.toSet());

        assert (results.size() == 1);
    }


    public void testInclusionAndExclusionFilteringMany() {
        Map<String, Object> file1Properties = new HashMap<>();
        file1Properties.put("filePath", List.of("11 Notes/file1.md"));
        file1Properties.put("tags", List.of("tag1", "tag2"));
        PksFile file1 = getFile(file1Properties);

        Map<String, Object> file2Properties = new HashMap<>();
        file2Properties.put("filePath", List.of("01 Notes/file2.md"));
        file2Properties.put("tags", List.of("tag3", "tag4"));
        PksFile file2 = getFile(file2Properties);

        Map<String, Object> file3Properties = new HashMap<>();
        file3Properties.put("filePath", List.of("01 Notes/file3.md"));
        file3Properties.put("tags", List.of("tag1", "tag3"));
        PksFile file3 = getFile(file3Properties);

        Map<String, Object> file4Properties = new HashMap<>();
        file4Properties.put("filePath", List.of("01 Notes/file4.md"));
        file4Properties.put("tags", List.of("tag2", "tag5"));
        PksFile file4 = getFile(file4Properties);

        Map<String, Object> file5Properties = new HashMap<>();
        file5Properties.put("filePath", List.of("01 Notes/file5.md"));
        file5Properties.put("tags", List.of("tag5", "tag1"));
        PksFile file5 = getFile(file5Properties);

        Map<String, List<String>> searchParameters = getParameters("filePath", "0*");
        searchParameters.put("!tags", List.of("tag1", "tag5"));

        Set<PksFile> results = Stream.of(file1, file2, file3, file4, file5)
                .filter(pksFile -> Search.matchesProperties(pksFile, searchParameters))
                .collect(Collectors.toSet());

        assert (results.size() == 1);
    }


    public void testHasOneProperty() {
        PksFile file1 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag1", "tag2"));
        PksFile file2 = getFile(getProperties("other", "a", "b"));

        Map<String, List<String>> params = getParameters(FILE_PROPERTY_TAGS_INCLUSION, "");

        Set<PksFile> results = Stream.of(file1, file2)
                .filter(pksFile -> Search.matchesProperties(pksFile, params))
                .collect(Collectors.toSet());

        assert (results.size() == 1);
    }

    public void testHasManyProperties() {
        PksFile file1 = getFile(getProperties(FILE_PROPERTY_TAGS_INCLUSION, "tag1", "tag2"));
        Map<String, Object> properties = new HashMap<>();
        properties.put("pkms", "123");
        properties.put(FILE_PROPERTY_TAGS_INCLUSION, List.of("abc"));
        PksFile file2 = getFile(properties);
        PksFile file3 = getFile(getProperties("other", "a", "b"));

        Map<String, List<String>> params = getParameters(FILE_PROPERTY_TAGS_INCLUSION, "");
        params.put("pkms", List.of(""));

        Set<PksFile> results = Stream.of(file1, file2, file3)
                .filter(pksFile -> Search.matchesProperties(pksFile, params))
                .collect(Collectors.toSet());

        assert (results.size() == 1);
    }

    private Map<String, List<String>> getParameters() {
        return getParameters(FILE_PROPERTY_TAGS_EXCLUSION, "tag4", "tag5");
    }

    private Map<String, List<String>> getParameters(String property, String... value) {
        Map<String, List<String>> properties = new HashMap<>();
        List<String> tagsToFilter = List.of(value);
        properties.put(property, tagsToFilter);

        return properties;
    }

    private Map<String, Object> getProperties(String property, String... values) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(property, values);

        return properties;
    }

    private PksFile getFile() {
        Map<String, Object> properties = new HashMap<>();
        List<String> tags = List.of("tag1", "tag2");
        properties.put(FILE_PROPERTY_TAGS_INCLUSION, tags);

        return getFile(properties);
    }

    private PksFile getFile(Map<String, Object> properties) {

        return new PksFile("filePath", properties);
    }
}
