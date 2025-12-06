package io.github.pskenny.io.parser.actions;

import io.github.pskenny.io.PksFile;
import io.github.pskenny.luabase.LuaBaseProcessor;
import io.github.pskenny.luabase.NaiveBaseToLuaBaseConverter;
import io.github.pskenny.luabase.YamlBaseParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// read the content and change the content
/*
Maybe Idea?:
Get which properties are used in the base. Keep a list of files and their dependant properties for dynamic content and use
that as a trigger for when other files properties are updated to update that file with base
 */
public class BaseToMarkdownAction {
    private static final Logger logger = LoggerFactory.getLogger(BaseToMarkdownAction.class);

    public Set<String> act(PksFile pksFile, HashMap<String, PksFile> allPksFiles) {
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
        Pattern luabasePattern = Pattern.compile("(?s)```base(.*?)```");
        Matcher matcher = luabasePattern.matcher(content);

        StringBuffer resultBuffer = new StringBuffer();
        NaiveBaseToLuaBaseConverter naiveBaseToLuaBaseConverter = new NaiveBaseToLuaBaseConverter();

        while (matcher.find()) {
            String obsidianBaseYaml = matcher.group(1).trim();  // Extract YAML content between ```base and ```
            String replacement = "";
            // Convert to Lua and process
            try {
                // this copy fucking sucks. So much data to copy. Makes bases really expensive
                var copiedFiles = Map.copyOf(allPksFiles);
                Map<String, Object> spec = new YamlBaseParser().parse(naiveBaseToLuaBaseConverter.convert(obsidianBaseYaml));
                LuaBaseProcessor processor = new LuaBaseProcessor(spec);

                // Replace this match with the Lua table
                replacement = processor.process(copiedFiles);

                changedFiles.add(pksFile.getFilePath());
                logger.debug("Converted Obsidian Base on " + pksFile.getFilePath());
            } catch (Exception e) {
                logger.warn("Couldn't do Base conversion on " + pksFile.getFilePath() + "\n" + e.getMessage());
                logger.warn(obsidianBaseYaml);
            }
            matcher.appendReplacement(resultBuffer, Matcher.quoteReplacement(replacement));
        }

        if (!changedFiles.isEmpty()) {
            matcher.appendTail(resultBuffer);
            String newContent = resultBuffer.toString();
            pksFile.getProperties().put("content", newContent);
        }

        return changedFiles;
    }
}
