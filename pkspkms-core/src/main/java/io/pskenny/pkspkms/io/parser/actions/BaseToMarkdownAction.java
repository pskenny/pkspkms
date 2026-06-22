package io.pskenny.pkspkms.io.parser.actions;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.luabase.LuaBaseProcessor;
import io.pskenny.pkspkms.luabase.NaiveBaseToLuaBaseConverter;
import io.pskenny.pkspkms.luabase.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
Idea: Get which properties are used in the base. Keep a list of files and their dependant properties for dynamic content and use
that as a trigger for when other files properties are updated to update that file with base
 */
public class BaseToMarkdownAction {
    private static final Logger logger = LoggerFactory.getLogger(BaseToMarkdownAction.class);
    private final LuaBaseProcessor processor = new LuaBaseProcessor();
    public static Pattern BASE_PATTERN = Pattern.compile("(?s)```base(.*?)```");
    public static Pattern LUABASE_PATTERN = Pattern.compile("(?s)```luabase(.*?)```");

    public Set<String> act(PksFile pksFile, Map<String, PksFile> allPksFiles) {
        if (!pksFile.getFilePath().endsWith(".md")) {
            return Set.of();
        }

        Set<String> changedFiles = new HashSet<>();
        String content;
        if (pksFile.getProperties().containsKey("content")) {
            content = pksFile.getProperties().get("content").toString();
        } else {
            try {
                content = Files.readString(pksFile.getFile().toPath(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                logger.error("Error reading file {}: {}", pksFile.getFile().getAbsolutePath(), ex.getMessage());
                return Set.of();
            }
        }

        Matcher matcher = BASE_PATTERN.matcher(content);

        StringBuilder stringBuilder = new StringBuilder();
        NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter = new NaiveBaseToLuaBaseConverter();

        while (matcher.find()) {
            String obsidianBaseYaml = matcher.group(1).trim();  // Extract YAML content between ```base and ```
            String replacement = "";
            // Convert to Lua and process
            try {
            // Copying the entire file map is expensive for large repositories
            var copiedFiles = Map.copyOf(allPksFiles);
                Map<String, Object> spec = new YamlParser().parse(naiveBaseToLuaBaseConverter.convert(obsidianBaseYaml));

                // Replace this match with the Lua table
                replacement = processor.process(spec, copiedFiles);

                changedFiles.add(pksFile.getFilePath());
                logger.debug("Converted Obsidian Base on " + pksFile.getFilePath());
            } catch (Exception e) {
                logger.warn("Couldn't do Base conversion on " + pksFile.getFilePath() + "\n" + e.getMessage());
                logger.warn(obsidianBaseYaml);
            }
            matcher.appendReplacement(stringBuilder, Matcher.quoteReplacement(replacement));
        }

        if (!changedFiles.isEmpty()) {
            matcher.appendTail(stringBuilder);
            String newContent = stringBuilder.toString();
            pksFile.getProperties().put("content", newContent);
        }

        return changedFiles;
    }
}
