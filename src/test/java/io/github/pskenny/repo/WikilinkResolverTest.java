package io.github.pskenny.repo;

import io.github.pskenny.io.PksFile;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WikilinkResolverTest {
    @Test
    public void givenFileWithWikilinkProperty_whenResolving_returnCorrectFilePath() {
        Map<String, Object> properties1 = new HashMap<>();
        ArrayList<String> wikilinks = new ArrayList<>();
        wikilinks.add("aliasName");
        properties1.put("wikilinks", wikilinks);
        PksFile file1 = new PksFile("file1.md", properties1);

        Map<String, Object> properties2 = new HashMap<>();
        ArrayList<String> aliasName = new ArrayList<>();
        aliasName.add("aliasName");
        properties2.put("aliases", aliasName);
        PksFile file2 = new PksFile("file2.md", properties2);

        List<PksFile> files = List.of(file1, file2);
        Map<String, Set<String>> actual  = WikilinkResolver.getWikilinkToFilePaths(files);

        assertEquals(actual.get("aliasName").size(), 1);
        assertEquals(actual.get("aliasName").iterator().next(), "file2.md");
    }

    @Test
    public void givenFilesWithWikilinkProperty_andConflictingAliases_whenResolving_returnSomething() {
        Map<String, Object> properties = new HashMap<>();
        ArrayList<String> wikilinks = new ArrayList<>();
        wikilinks.add("aliasName");
        properties.put("wikilinks", wikilinks);
        ArrayList<String> aliasName1 = new ArrayList<>();
        aliasName1.add("aliasName");
        properties.put("aliases", aliasName1);
        PksFile file1 = new PksFile("file1.md", properties);
        Map<String, Object> properties2 = new HashMap<>();
        ArrayList<String> aliasName = new ArrayList<>();
        aliasName.add("aliasName");
        properties2.put("aliases", aliasName);
        PksFile file2 = new PksFile("file2.md", properties2);
        List<PksFile> files = List.of(file1, file2);
        Map<String, Set<String>> actual  = WikilinkResolver.getWikilinkToFilePaths(files);

        // when the value has more than one value that means it's ambiguous
        assertEquals(actual.get("aliasName").size(), 2);
    }
}
