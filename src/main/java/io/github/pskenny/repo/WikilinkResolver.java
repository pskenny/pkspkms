package io.github.pskenny.repo;

import io.github.pskenny.io.PksFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

public class WikilinkResolver {
    private static final Logger logger = LoggerFactory.getLogger(WikilinkResolver.class);
    private Map<String, Set<String>> resolvedLinks = new HashMap<>();

    public void initialise(Map<String, PksFile> initialPksFiles) {
        HashMap<String, Set<String>> fileMap = new HashMap<>();

        initialPksFiles.values().forEach(pksFile -> {
                    File file = pksFile.getFile();
                    String fileName = file.getName();

                    addToFileMap(fileMap, pksFile, fileName);
                    addAliases(pksFile, fileMap);
                }
        );
        resolvedLinks = fileMap;
    }

    private void addToFileMap(Map<String, Set<String>> fileMap, PksFile pksFile, String name) {
        if (fileMap.containsKey(name)) {
            Set<String> filePaths = fileMap.get(name);
            HashSet<String> newPaths = new HashSet<>(filePaths);
            newPaths.add(pksFile.getFilePath());

            fileMap.put(name, newPaths);
        } else {
            fileMap.put(name, Set.of(pksFile.getFilePath()));
        }
    }

    private void addAliases(PksFile pksFile, Map<String, Set<String>> fileMap) {
        if (pksFile.getProperties().containsKey("aliases")) {
            ArrayList<String> aliases = (ArrayList<String>) pksFile.getProperties().get("aliases");
            if (!Objects.isNull(aliases)) {
                for (String alias : aliases) {
                    addToFileMap(fileMap, pksFile, alias);
                }
            }
        }
    }

    /**
     * Returns null if there's no links already resolved found.
     * @param text
     * @return
     */
    public String resolveWikilink(String text) {
        text = text.startsWith("/") ? text.substring(1) : text;
        Set<String> potentialLinks = resolvedLinks.get(text);
        // Check as is
        if (potentialLinks == null) {
            // Check with Markdown file extension
            potentialLinks = resolvedLinks.get(text + ".md");
            if (potentialLinks == null) {
                return null;
            }
        }
        if (potentialLinks.size() > 1) {
            logger.error("Ambiguous wikilink lookup: {}", text);
        }
        return potentialLinks.toArray()[0].toString();
    }
}
