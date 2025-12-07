package io.github.pskenny;

import io.github.pskenny.io.JsonUtil;
import io.github.pskenny.io.PksFile;
import io.github.pskenny.io.parser.GenericParser;
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
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(CorsPluginConfig.CorsRule::anyHost);
            });
        });

        app.get("/ping", ctx -> ctx.status(200));

        app.get("/files/list", ctx -> {
            Map<String, PksFile> matchedFiles = inMemoryFileRepository.search(ctx.queryParamMap());
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
            Collection<PksFile> files = new ArrayList<>();

            Set<String> linkedFiles = new HashSet<>();
            Set<String> linkedTags = new HashSet<>();

            matchedFiles.values().forEach(pksFile -> {
                if (pksFile.getProperties().containsKey("links")) {
                    linkedFiles.addAll((Collection<? extends String>) pksFile.getProperties().get("links"));
                }
                if (pksFile.getProperties().containsKey("backlinks")) {
                    linkedFiles.addAll((Collection<? extends String>) pksFile.getProperties().get("backlinks"));
                }
                if (pksFile.getProperties().containsKey("tags") && pksFile.getProperties().get("tags") != null) {
                    if (pksFile.getProperties().get("tags") instanceof String) {
                        linkedFiles.add(pksFile.getProperties().get("tags").toString());
                    } else {
                        linkedTags.addAll((Collection<? extends String>) pksFile.getProperties().get("tags"));
                    }
                }
            });

            linkedFiles.removeIf(matchedFiles::containsKey);
            List<PksFile> depth2Files = getPksFilesByPath(linkedFiles);

            depth2Files.forEach(pksFile -> {
                if (pksFile.getProperties().containsKey("links")) {
                    linkedFiles.addAll((Collection<? extends String>) pksFile.getProperties().get("links"));
                }
                if (pksFile.getProperties().containsKey("backlinks")) {
                    linkedFiles.addAll((Collection<? extends String>) pksFile.getProperties().get("backlinks"));
                }
                if (pksFile.getProperties().containsKey("tags") && pksFile.getProperties().get("tags") != null) {
                    linkedTags.addAll((Collection<? extends String>) pksFile.getProperties().get("tags"));
                }
            });

            if (!linkedTags.isEmpty()) {
                // get every file with every tag
                Map<String, PksFile> taggedFiles = new HashMap<>();
                for (String tag : linkedTags) {
                    Map<String, PksFile> matchedTagFiles = inMemoryFileRepository.search("tags=" + tag);
                    taggedFiles.putAll(matchedTagFiles);
                }

                taggedFiles.forEach((s, pksFile) -> {
                    if (!matchedFiles.containsKey(s)) {
                        // has redundant files
                        depth2Files.add(pksFile);
                    }
                });
            }

            if (!depth2Files.isEmpty()) {
                files.addAll(depth2Files);
            }

            List<PksFile> filesToReturn = new ArrayList<>(matchedFiles.size());
            files.forEach(pksFile -> {
                // only have filePath, links, backlinks and tags properties
                pksFile.filterProperties(List.of("links", "backlinks", "tags", "filePath"), List.of());
                filesToReturn.add(pksFile);
            });

            ctx.json(JsonUtil.pksFilesToJson(filesToReturn));
            ctx.status(200);
        });
    }


    private List<PksFile> getPksFilesByPath(Set<String> paths) {
        List<PksFile> files = new ArrayList<>();
        GenericParser gp = new GenericParser();
        var allPksFilesMap = inMemoryFileRepository.getAllPksFiles();
        paths.forEach(path -> {
            if (allPksFilesMap.containsKey(path)) {
                files.add(allPksFilesMap.get(path));
            }
        });

        return files;
    }

    public Javalin getJavalinApp() {
        return app;
    }

    // for testing
    public void stopServer() {
        app.stop();
    }
}
