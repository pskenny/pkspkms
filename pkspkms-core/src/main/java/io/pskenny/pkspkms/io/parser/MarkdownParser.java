package io.pskenny.pkspkms.io.parser;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.parser.actions.WikilinkToMarkdownLinkAction;
import io.pskenny.pkspkms.io.parser.markdown.MarkdownLinkReader;
import io.pskenny.pkspkms.io.parser.markdown.MarkdownWikilinkReader;
import io.pskenny.pkspkms.io.parser.markdown.YamlFrontmatterReader;
import io.pskenny.pkspkms.repo.WikilinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownParser {
    private static final Logger logger = LoggerFactory.getLogger(MarkdownParser.class);

    private final MarkdownLinkReader markdownLinkReader;
    private final MarkdownWikilinkReader markdownWikilinkReader;
    private final YamlFrontmatterReader yamlFrontmatterReader;
    private final String dbUrl;

    public MarkdownParser(String dbUrl) {
        this.dbUrl = dbUrl;
        markdownLinkReader = new MarkdownLinkReader();
        markdownWikilinkReader = new MarkdownWikilinkReader();
        yamlFrontmatterReader = new YamlFrontmatterReader();
    }

    public PksFile initialParse(Path path, String directory) {
        String content;
        try {
            content = Files.readString(path, Charset.defaultCharset());
        } catch (IOException e) {
            logger.error("Couldn't read file: {}", path);
            return null;
        }
        // standard Markdown only: frontmatter, md links. No transformations.
        Map <String, Object> frontmatter = new HashMap<>();
        try {
            frontmatter = yamlFrontmatterReader.getFrontMatterProperties(content);
        } catch (Exception ex) {
            logger.error("Error reading YAML frontmatter for file {}", path);
        }

        PksFile pksFile = new PksFile(path.toFile(), directory, frontmatter);

        var wikilinks = markdownWikilinkReader.getWikilinks(content);
        if (!wikilinks.isEmpty()) {
            LinkedHashMap<String, Object> wikilinksMap = new LinkedHashMap<>();
            wikilinksMap.put("wikilinks", wikilinks);
            pksFile.getProperties().putAll(wikilinksMap);
        }
        var links = markdownLinkReader.getMarkdownLinksProperties(content);
        if (!links.isEmpty()) {
            var linksMap = Map.of("links", links);
            pksFile.getProperties().putAll(linksMap);
        }
        var bases = getBases(content);
        if (!bases.isEmpty()) {
            var basesMap = Map.of("bases", bases);
            pksFile.getProperties().putAll(basesMap);
        }
        var luabases = getLuaBases(content);
        if (!luabases.isEmpty()) {
            var luabasesMap = Map.of("luabases", luabases);
            pksFile.getProperties().putAll(luabasesMap);
        }

        return pksFile;
    }

    public Map parseToMap(String content) {
        Map <String, Object> map = new HashMap<>();
        // parse wikilinks, change the content
        WikilinkService wikilinkService = new WikilinkService(dbUrl);
        WikilinkToMarkdownLinkAction wikilinkToMarkdownLinkAction = new WikilinkToMarkdownLinkAction();
        var wikilinks = wikilinkToMarkdownLinkAction.getWikilinks(content, wikilinkService);
        if (!wikilinks.isEmpty()) {
            map.put("wikilinks", wikilinks);
        }
        content = wikilinkToMarkdownLinkAction.transformWikilinksToLinks(content, wikilinkService);
        // parse the links
        var links = markdownLinkReader.getMarkdownLinksProperties(content);
        if (!links.isEmpty()) {
            map.put("links", links);
        }
        return map;
    }

    public List<String> getBases(String content) {
        Pattern luabasePattern = Pattern.compile("(?s)```base(.*?)```");
        Matcher matcher = luabasePattern.matcher(content);

        List<String> base = new ArrayList<>();
        while (matcher.find()) {
            String obsidianBaseYaml = matcher.group(1).trim();  // Extract YAML content between ```base and ```
            base.add(obsidianBaseYaml);
        }

        return base;
    }

    public List<String> getLuaBases(String content) {
        Pattern luabasePattern = Pattern.compile("(?s)```luabase(.*?)```");
        Matcher matcher = luabasePattern.matcher(content);

        List<String> luabase = new ArrayList<>();
        while (matcher.find()) {
            String luabaseYaml = matcher.group(1).trim();
            luabase.add(luabaseYaml);
        }

        return luabase;
    }
}
