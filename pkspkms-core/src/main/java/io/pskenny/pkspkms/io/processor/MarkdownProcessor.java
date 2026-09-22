package io.pskenny.pkspkms.io.processor;

import io.pskenny.pkspkms.io.fs.PkmsEntry;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.processor.actions.WikilinkToMarkdownLinkAction;
import io.pskenny.pkspkms.io.processor.markdown.MarkdownLinkReader;
import io.pskenny.pkspkms.io.processor.markdown.MarkdownWikilinkReader;
import io.pskenny.pkspkms.io.processor.markdown.YamlFrontmatterReader;
import io.pskenny.pkspkms.services.WikilinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownProcessor {
    private static final Logger logger = LoggerFactory.getLogger(MarkdownProcessor.class);

    public static final Pattern BASE_PATTERN = Pattern.compile("(?s)```base(.*?)```");
    public static final Pattern LUABASE_PATTERN = Pattern.compile("(?s)```luabase(.*?)```");

    private final MarkdownLinkReader markdownLinkReader;
    private final MarkdownWikilinkReader markdownWikilinkReader;
    private final YamlFrontmatterReader yamlFrontmatterReader;

    public MarkdownProcessor() {
        markdownLinkReader = new MarkdownLinkReader();
        markdownWikilinkReader = new MarkdownWikilinkReader();
        yamlFrontmatterReader = new YamlFrontmatterReader();
    }

    public PksFile initialParse(PkmsEntry entry, byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);

        Map<String, Object> frontmatter = new HashMap<>();
        try {
            frontmatter = yamlFrontmatterReader.getFrontMatterProperties(content);
        } catch (Exception ex) {
            logger.error("Error reading YAML frontmatter for file {}", entry.relativePath(), ex);
        }

        PksFile pksFile = new PksFile(entry.relativePath(), frontmatter);
        try {
            String hash = io.pskenny.pkspkms.io.Blake3Util.hashBytes(bytes);
            pksFile.setHash(hash);
        } catch (Exception e) {
            logger.error("Error computing Blake3 hash for {}", entry.relativePath(), e);
        }

        var wikilinks = markdownWikilinkReader.getWikilinks(content);
        if (!wikilinks.isEmpty()) {
            LinkedHashMap<String, Object> wikilinksMap = new LinkedHashMap<>();
            wikilinksMap.put("wikilinks", wikilinks);
            pksFile.getMutableProperties().putAll(wikilinksMap);
        }
        var links = markdownLinkReader.getMarkdownLinksProperties(content);
        if (!links.isEmpty()) {
            var linksMap = Map.of("links", links);
            pksFile.getMutableProperties().putAll(linksMap);
        }
        var bases = getBases(content);
        if (!bases.isEmpty()) {
            var basesMap = Map.of("bases", bases);
            pksFile.getMutableProperties().putAll(basesMap);
        }
        var luabases = getLuaBases(content);
        if (!luabases.isEmpty()) {
            var luabasesMap = Map.of("luabases", luabases);
            pksFile.getMutableProperties().putAll(luabasesMap);
        }

        return pksFile;
    }

    // Preferred overload for batch use (e.g. EmbedProcessor): caller provides a
    // shared WikilinkService so the full-table-scan cache is built only once.
    public Map parseToMap(String content, WikilinkService wikilinkService) {
        Map <String, Object> map = new HashMap<>();
        // parse wikilinks, change the content
        // TODO this should be a different pattern
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
        Matcher matcher = BASE_PATTERN.matcher(content);

        List<String> base = new ArrayList<>();
        while (matcher.find()) {
            String obsidianBaseYaml = matcher.group(1).trim();  // Extract YAML content between ```base and ```
            base.add(obsidianBaseYaml);
        }

        return base;
    }

    public List<String> getLuaBases(String content) {
        Matcher matcher = LUABASE_PATTERN.matcher(content);

        List<String> luabase = new ArrayList<>();
        while (matcher.find()) {
            String luabaseYaml = matcher.group(1).trim();
            luabase.add(luabaseYaml);
        }

        return luabase;
    }
}
