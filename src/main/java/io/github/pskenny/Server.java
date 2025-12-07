package io.github.pskenny;

import io.github.pskenny.io.JsonUtil;
import io.github.pskenny.io.PksFile;
import io.github.pskenny.repo.InMemoryFileRepository;
import io.javalin.Javalin;
import io.javalin.plugin.bundled.CorsPluginConfig;

import java.util.*;

public class Server {
    private final InMemoryFileRepository inMemoryFileRepository;
    private Javalin app;

    public Server(String directory) {
        inMemoryFileRepository = new InMemoryFileRepository(directory);
        app = Javalin.create(config -> {
//            config.bundledPlugins.enableDevLogging();
            config.showJavalinBanner = false;
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(CorsPluginConfig.CorsRule::anyHost);
            });
        });

        app.get("/ping", ctx -> ctx.status(200));

        app.get("/files/list", ctx -> {
            Map<String, PksFile> matchedFiles = inMemoryFileRepository.search(ctx.queryParamMap());
            matchedFiles.values().forEach(pksFile -> pksFile.filterProperties(List.of(), List.of("content")));
            ctx.json(JsonUtil.pksFilesToJson(matchedFiles.values()));
            ctx.status(200);
        });

        app.get("/files/list/graph", ctx -> {
            Map<String, PksFile> matchedFiles = inMemoryFileRepository.search(ctx.queryParamMap());
            matchedFiles.values().forEach(pksFile ->
                    pksFile.filterProperties(List.of("links", "backlinks", "tags", "filePath"), List.of()));

            ctx.json(JsonUtil.pksFilesToJson(matchedFiles.values()));
            ctx.status(200);
        });

        app.get("/files/list/graph/depth/2", ctx -> {
            Map<String, PksFile> matchedFiles = inMemoryFileRepository.search(ctx.queryParamMap());

            Set<String> linkedFilePaths = new HashSet<>();
            Set<String> allLinkedTags = new HashSet<>();

            matchedFiles.values().forEach(pksFile -> {
                if (pksFile.getProperties().containsKey("links")) {
                    linkedFilePaths.addAll((Collection<? extends String>) pksFile.getProperties().get("links"));
                }
                if (pksFile.getProperties().containsKey("backlinks")) {
                    linkedFilePaths.addAll((Collection<? extends String>) pksFile.getProperties().get("backlinks"));
                }
                if (pksFile.getProperties().containsKey("tags") && pksFile.getProperties().get("tags") != null) {
                    if (pksFile.getProperties().get("tags") instanceof String) {
                        allLinkedTags.add(pksFile.getProperties().get("tags").toString());
                    } else {
                        allLinkedTags.addAll((Collection<? extends String>) pksFile.getProperties().get("tags"));
                    }
                }
            });

            // Remove any files already in the matched files
            linkedFilePaths.removeIf((obj) ->
                matchedFiles.containsKey(obj) || matchedFiles.containsKey("/" + obj)
            );
            Map<String, PksFile> depth1PksFiles = getPksFileMapByPath(linkedFilePaths);

            depth1PksFiles.forEach((path, pksFile) -> {
                if (pksFile.getProperties().containsKey("tags") && pksFile.getProperties().get("tags") != null) {
                    allLinkedTags.addAll((Collection<? extends String>) pksFile.getProperties().get("tags"));
                }
            });

            Map<String, PksFile> taggedFiles = new HashMap<>();
            if (!allLinkedTags.isEmpty()) {
                // get every file with every tag
                for (String tag : allLinkedTags) {
                    Map<String, PksFile> matchedTagFiles = inMemoryFileRepository.search("tags=" + tag);
                    taggedFiles.putAll(matchedTagFiles);
                }
            }

            Map<String, PksFile> allPksFiles = new HashMap<>();
            allPksFiles.putAll(matchedFiles);
            allPksFiles.putAll(depth1PksFiles);
            allPksFiles.putAll(taggedFiles);

            Set<PksFile> filesToReturn = new HashSet<>();
            allPksFiles.forEach((path, pksFile) -> {
                // only have filePath, links, backlinks and tags properties
                pksFile.filterProperties(List.of("links", "backlinks", "tags", "filePath"), List.of());
                filesToReturn.add(pksFile);
            });

            ctx.json(JsonUtil.pksFilesToJson(filesToReturn));
            ctx.status(200);
        });
    }

    private Map<String, PksFile> getPksFileMapByPath(Set<String> paths) {
        Map<String, PksFile> files = new HashMap<>();

        var allPksFiles = inMemoryFileRepository.getAllPksFiles();

        paths.forEach(path -> {
            if (allPksFiles.containsKey(path)) {
                files.put(path, allPksFiles.get(path));
            } else if (allPksFiles.containsKey("/" + path)) {
                files.put("/" + path, allPksFiles.get("/" + path));
            }
        });

        return files;
    }

    // for testing
    public Javalin getJavalinApp() {
        return app;
    }
}
