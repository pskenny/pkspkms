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

                    if (fileMap.containsKey(fileName)) {
                        HashSet<String> newPaths = new HashSet<>(fileMap.get(fileName));
                        newPaths.add(pksFile.getFilePath());

                        fileMap.put(fileName, newPaths);
                    } else {
                        fileMap.put(fileName, Set.of(pksFile.getFilePath()));
                    }

                    if (pksFile.getProperties().containsKey("aliases")) {
                        ArrayList<String> aliases = (ArrayList<String>) pksFile.getProperties().get("aliases");
                        if (!Objects.isNull(aliases)) {
                            for (String alias : aliases) {
                                if (fileMap.containsKey(alias)) {
                                    Set<String> filePaths = fileMap.get(alias);
                                    HashSet<String> newPaths = new HashSet<>(filePaths);
                                    newPaths.add(pksFile.getFilePath());

                                    fileMap.put(alias, newPaths);
                                } else {
                                    fileMap.put(alias, Set.of(pksFile.getFilePath()));
                                }
                            }
                        }
                    }
                }
        );
        resolvedLinks = fileMap;
    }

    public String resolveWikilink(String text) {
        text = text.startsWith("/") ? text.substring(1) : text;
        Set<String> potentialLinks = resolvedLinks.get(text);
        // check plain
        if (potentialLinks == null) {
            // check if it's the file name without the file extension
            potentialLinks = resolvedLinks.get(text + ".md");
            if (potentialLinks == null) {
                return null;
            }
        }
        if (potentialLinks.size() > 1) {
            logger.error("Ambiguous wikilink lookup: {}", text);
        }
        return "." +
                potentialLinks.toArray()[0].toString();
    }

    // returns a map with the key being a valid wikilink (every file name and alias for a file) and the value being the file path
    // if the value has more than one value the wikilink is ambiguous
    public static Map<String, Set<String>> getWikilinkToFilePaths(Collection<PksFile> files) {
        HashMap<String, Set<String>> fileMap = new HashMap<>();
        files.forEach(pksFile -> {
                    File file = new File(pksFile.getFilePath());
                    String fileName = file.getName();

                    if (fileMap.containsKey(fileName)) {
                        HashSet<String> newPaths = new HashSet<>(fileMap.get(fileName));
                        newPaths.add(pksFile.getFilePath());

                        fileMap.put(fileName, newPaths);
                    } else {
                        fileMap.put(fileName, Set.of(pksFile.getFilePath()));
                    }

                    if (pksFile.getProperties().containsKey("aliases")) {
                        ArrayList<String> aliases = (ArrayList<String>) pksFile.getProperties().get("aliases");
                        if (!Objects.isNull(aliases)) {
                            for (String alias : aliases) {
                                if (fileMap.containsKey(alias)) {
                                    Set<String> filePaths = fileMap.get(alias);
                                    HashSet<String> newPaths = new HashSet<>(filePaths);
                                    newPaths.add(pksFile.getFilePath());

                                    fileMap.put(alias, newPaths);
                                } else {
                                    fileMap.put(alias, Set.of(pksFile.getFilePath()));
                                }
                            }
                        }
                    }
                }
        );

        return fileMap;
    }
}
