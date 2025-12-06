package io.github.pskenny.repo;

import io.github.pskenny.io.PksFile;
import io.github.pskenny.io.Search;
import io.github.pskenny.io.parser.Parsers;
import io.github.pskenny.io.parser.actions.BaseToMarkdownAction;
import io.github.pskenny.io.parser.markdown.MarkdownLinkReader;
import io.github.pskenny.io.parser.actions.WikilinkToMarkdownLinkAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static io.github.pskenny.io.Search.parse;

public class InMemoryFileRepository {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryFileRepository.class);

    private final HashMap<String, PksFile> allPksFiles = new HashMap<>();
    private final String directory;
    private final Parsers parsers = new Parsers();
    private final WikilinkResolver wikilinkResolver;

    private final MarkdownLinkReader markdownLinkReader = new MarkdownLinkReader();

    public InMemoryFileRepository(String directory) {
        this.directory = directory;
        wikilinkResolver = new WikilinkResolver();

        long startTime = System.currentTimeMillis();

        initialRead();
        update();
        logger.debug("Complete init. All pksfiles: {}", allPksFiles.size());
        long endTime = System.currentTimeMillis();
        long durationMs = endTime - startTime;
        long seconds = durationMs / 1000;
        long milliseconds = durationMs % 1000;

        logger.debug("Initialisation time: {}s {}ms", seconds, milliseconds);
    }

    public HashMap<String, PksFile> getAllPksFiles() {
        return allPksFiles;
    }

    private void initialRead() {
        File dir = new File(directory);
        if (!dir.exists()) {
            logger.error("Directory doesn't exist: {}", dir);
            System.exit(1);
        }

        if (dir.isFile()) {
            logger.error("Directory must not be a file: {}", dir);
            System.exit(1);
        }

        try {
            Files.find(Paths.get(directory), Integer.MAX_VALUE, (filePath, fileAttr) -> fileAttr.isRegularFile())
                    .map(file -> parsers.initialReadOnlyParse(file, directory))
                    .filter(Objects::nonNull)
                    .forEach(pksFile -> allPksFiles.put(pksFile.getFilePath(), pksFile));
        } catch (IOException e) {
            logger.error("Couldn't read files in directory {}", directory);
            System.exit(1);
        }
        wikilinkResolver.initialise(allPksFiles);
    }

    public void update() {
        this.update(1);
    }

    public void update(int maxRound) {
        this.update(maxRound, allPksFiles.keySet());
    }

    public void update(int maxRound, Set<String> filePathsToCheck) {
        int currentRound = 0;
        do {
            Set<String> newlyChanged = new HashSet<>();
            Set<String> dynamicChanged = maybeReplaceDynamicContent(filePathsToCheck);
            newlyChanged.addAll(dynamicChanged);
            Set<String> readChanges = readChangesToFiles(filePathsToCheck);
            newlyChanged.addAll(readChanges);
            filePathsToCheck = newlyChanged;

            logger.debug("{} changed files on update round {}", filePathsToCheck.size(), currentRound + 1);
        } while (++currentRound < maxRound && !filePathsToCheck.isEmpty());
    }

    private Set<String> maybeReplaceDynamicContent(Set<String> files) {
        Set<String> changedFiles = new HashSet<>();
        for (String file : files) {
            PksFile pksFile = allPksFiles.get(file);
            BaseToMarkdownAction baseToMarkdownAction = new BaseToMarkdownAction();
            Set<String> baseChangedFiles = baseToMarkdownAction.act(pksFile, allPksFiles);
            WikilinkToMarkdownLinkAction wikilinkToMarkdownLinkAction = new WikilinkToMarkdownLinkAction();
            Set<String> wikilinkChangedFiles = wikilinkToMarkdownLinkAction.act(pksFile, wikilinkResolver);

            Set<String> newlyChangedFiles = new HashSet<>();
            if (!baseChangedFiles.isEmpty()) {
                logger.debug("{} base dynamic files changed", baseChangedFiles.size());
            }
            if (!wikilinkChangedFiles.isEmpty()) {
                logger.debug("{} wikilink dynamic files changed", wikilinkChangedFiles.size());
            }
            newlyChangedFiles.addAll(wikilinkChangedFiles);
            newlyChangedFiles.addAll(baseChangedFiles);

            if (!newlyChangedFiles.isEmpty()) {
                newlyChangedFiles.add(pksFile.getFilePath());
                changedFiles.addAll(newlyChangedFiles);
            }
        }

        return changedFiles;
    }

    private Set<String> readChangesToFiles(Set<String> files) {
        Set<String> changedFiles = new HashSet<>();
        for (String file : files) {
            if (!file.endsWith(".md")) {
                continue;
            }
            // 1. Find Markdown links in file content
            PksFile pksFile = allPksFiles.get(file);
            String content;
            if (pksFile.getProperties().containsKey("content")) {
                content = (String) pksFile.getProperties().get("content");
            } else {
                try {
                    // Read content from file path (most reliable source)
                    content = Files.readString(pksFile.getFile().toPath(), StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    logger.error("Error reading file {}: {}", pksFile.getFile().getAbsolutePath(), ex.getMessage());
                    return Set.of();
                }
            }
            // replace links in properties
            List<String> oldLinks = (List<String>) pksFile.getProperties().get("links");
            List<String> newLinks = markdownLinkReader.getMarkdownLinksProperties(content);

            if (oldLinks == null) {
                if (!newLinks.isEmpty()) {
                    changedFiles.addAll(newLinks);
                }
            } else if (!(new HashSet<>(newLinks).containsAll(oldLinks) && new HashSet<>(oldLinks).containsAll(newLinks))) {
                // mark added files as changed
                Set<String> changed = new HashSet<>(newLinks);
                oldLinks.forEach(changed::remove);
                changedFiles.addAll(changed);
            }

            if (!newLinks.isEmpty()) {
                // TODO: relativise them here?
                pksFile.getProperties().putAll(Map.of("links", newLinks));
            }
        }
        logger.debug(changedFiles.size() + " read changed files");
        // 2. Wikilink resolver update?
        return changedFiles;
    }

    public Map<String, PksFile> search(String query) {
        return search(parse(query));
    }

    public Map<String, PksFile> search(Map<String, List<String>> params) {
        Map<String, PksFile> matchedFiles = new HashMap<>();
        getAllPksFiles().values().forEach(pksFile -> {
            if (Search.matchesProperties(pksFile, params)) {
                matchedFiles.put(pksFile.getFilePath(), new PksFile(pksFile));
            }
        });
        addBacklinks(matchedFiles);

        return matchedFiles;
    }

    // backlinks done as a post result task instead of being tracked throughout
    private void addBacklinks(Map<String, PksFile> pksFiles) {
        logger.warn("Backlinks not implemented!");
        /*
        pksFiles.forEach((filePath, file) -> {
            if (file.getProperties().get("links") instanceof ArrayList) {
                ArrayList<String> links = (ArrayList<String>) file.getProperties().get("links");
                if (links != null) {
                    for (String link : links) {
                        // check absolute paths
                        PksFile linkedFile = allPksFiles.get(link);
                        if (linkedFile != null) {
                            linkedFile.getProperties().computeIfAbsent("backlinks", k -> new ArrayList<String>());
                            ((ArrayList<String>) linkedFile.getProperties().get("backlinks")).add(file.getFilePath());
                        }
                        // TODO: check relative path?
                    }
                }
            } else {
                HashSet<String> links = (HashSet<String>) file.getProperties().get("links");
                if (links != null) {
                    for (String link : links) {
                        PksFile linkedFile = allPksFiles.get(link);
                        if (linkedFile != null) {
                            linkedFile.getProperties().computeIfAbsent("backlinks", k -> new HashSet<String>());
                            ((Collection<String>) linkedFile.getProperties().get("backlinks")).add(file.getFilePath());
                        }
                    }
                }
            }
        });
        */
    }
}
