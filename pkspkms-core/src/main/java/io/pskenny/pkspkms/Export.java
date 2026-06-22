package io.pskenny.pkspkms;

import static io.pskenny.pkspkms.io.FileUtil.*;
import static io.pskenny.pkspkms.io.parser.actions.BaseToMarkdownAction.BASE_PATTERN;
import static io.pskenny.pkspkms.io.parser.actions.BaseToMarkdownAction.LUABASE_PATTERN;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.parser.markdown.MarkdownWikilinkEmbedReader;
import io.pskenny.pkspkms.io.parser.markdown.MarkdownWikilinkReader;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.repo.SQLitePksFileRepository;
import io.pskenny.pkspkms.repo.sqlite.SqlQueryParser;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;

public final class Export {
    private static final Logger logger = LoggerFactory.getLogger(Export.class);

    private final PksFileRepository repository;
    private final ExportConfig config;
    private final MarkdownWikilinkReader markdownWikilinkReader = new MarkdownWikilinkReader();
    private final MarkdownWikilinkEmbedReader embedReader = new MarkdownWikilinkEmbedReader();

    public Export(ExportConfig config, PksFileRepository repository) {
        this.config = config;
        this.repository = repository;
    }

    public void export() {
        SqlQueryParser sqlQueryParser = new SqlQueryParser();
        List<PksFile> matchedFiles = repository.searchRegular(
                sqlQueryParser.parseToFullJoinQuery(config.query())
        );
        if (matchedFiles.isEmpty()) {
            logger.info("No files to export");
            return;
        }
        logger.info("Matched {} files", matchedFiles.size());

        switch (config.type()) {
            case "markdown":
                markdownExport(
                        matchedFiles,
                        config.directory(),
                        config.output(),
                        config.dryRun()
                );
                break;
            case "copy":
                copyExport(
                        matchedFiles,
                        config.directory(),
                        config.output(),
                        config.dryRun()
                );
                break;
            default:
                logger.error("No export type specified");
                break;
        }
    }

    private void markdownExport(
            List<PksFile> files,
            String pkms,
            String outputDirectory,
            boolean dryRun
    ) {
        files.forEach(file -> {
                    copyLinkedFiles(file, pkms, outputDirectory, dryRun);
                    processFileForExport(file, pkms);
                    writePksFile(file, pkms, outputDirectory, dryRun);
                });
    }

    private void processFileForExport(PksFile file, String pkms) {
        String content = readContent(file, pkms);
        if (content == null) {
            return;
        }
        logger.info("Processing {}", file.getFilePath());
        if (file.getFilePath().endsWith("Public Home.md")) {
            System.out.println("yo");
        }
        content = replaceLuaBase(content);
        content = replaceBase(content);
        content = expandEmbeds(content, pkms);
        content = resolveWikilinks(content);
        file.getProperties().put("content", content);
    }

    private String readContent(PksFile file, String pkms) {
        String content = (String) file.getProperties().get("content");
        if (content != null) {
            return content;
        }
        try {
            Path path = Paths.get(pkms, file.getFilePath());
            return Files.readString(path);
        } catch (IOException e) {
            logger.error("Couldn't read file: {}{}", pkms, file.getFilePath());
            return null;
        }
    }

    private String replaceLuaBase(String content) {
        Matcher matcher = LUABASE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = "";
            try {
                String luaBaseYaml = matcher.group(1).trim();
                replacement = ((SQLitePksFileRepository) repository).getMarkdownFromLuaBase(luaBaseYaml);
            } catch (Exception e) {
                logger.warn("Couldn't do LuaBase replacement", e);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String replaceBase(String content) {
        Matcher matcher = BASE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = "";
            try {
                String baseYaml = matcher.group(1).trim();
                replacement = ((SQLitePksFileRepository) repository).getMarkdownFromBase(baseYaml);
            } catch (Exception e) {
                logger.warn("Couldn't do Base replacement", e);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String expandEmbeds(String content, String pkms) {
        List<String> embeds = embedReader.getEmbeds(content);
        for (String embed : embeds) {
            String replacement = resolveEmbed(embed, pkms);
            content = content.replace("![[" + embed + "]]", replacement);
        }
        return content;
    }

    private String resolveEmbed(String embed, String pkms) {
        String originalEmbed = embed;
        String embedHeading = null;
        String searchFile = embed;

        if (searchFile.contains("#")) {
            int hashIndex = searchFile.indexOf("#");
            embedHeading = searchFile.substring(hashIndex + 1);
            searchFile = searchFile.substring(0, hashIndex);
        }

        String fileQuery = searchFile;
        if (!fileQuery.contains(".")) {
            fileQuery = fileQuery + ".";
        }

        SqlQueryParser sqlQueryParser = new SqlQueryParser();
        var maybeFile = repository.searchRegular(sqlQueryParser.parseToFullJoinQuery("filePath = " + fileQuery + "*"));
        if (maybeFile.isEmpty()) {
            return "";
        }

        PksFile foundFile = maybeFile.iterator().next();
        String foundContent = readContent(foundFile, pkms);
        if (foundContent == null) {
            return "";
        }

        foundContent = stripFrontmatter(foundContent);

        String replacement;
        if (embedHeading == null || embedHeading.isEmpty()) {
            replacement = foundContent;
        } else {
            replacement = extractSection(foundContent, embedHeading);
        }
        return replacement.replaceAll("\\\\", "");
    }

    private String stripFrontmatter(String content) {
        if (!content.startsWith("---")) {
            return content;
        }
        int end = content.indexOf("---", 3);
        if (end == -1) {
            return content;
        }
        return content.substring(end + 3).trim();
    }

    private String resolveWikilinks(String content) {
        List<String> wikilinks = markdownWikilinkReader.getWikilinks(content);
        for (String wikilink : wikilinks) {
            String resolved = repository.resolveWikilink(wikilink);
            if (resolved != null) {
                content = content.replace("[["+ wikilink + "]]", "[" + wikilink + "](" + resolved + ")");
            }
        }
        return content;
    }

    private String extractSection(String content, String headingText) {
        Parser parser = Parser.builder().build();
        Node document = parser.parse(content);
        MarkdownRenderer renderer = MarkdownRenderer.builder().build();
        StringBuilder resultBuilder = new StringBuilder();

        Node node = document.getFirstChild();
        boolean capturing = false;
        int captureLevel = 0;

        while (node != null) {
            if (node instanceof Heading heading) {
                if (capturing) {
                    if (heading.getLevel() <= captureLevel) {
                        break;
                    }
                } else {
                    if (getHeadingText(heading).equalsIgnoreCase(headingText)) {
                        capturing = true;
                        captureLevel = heading.getLevel();
                        node = node.getNext();
                        continue;
                    }
                }
            }

            if (capturing) {
                resultBuilder.append(renderer.render(node));
            }
            node = node.getNext();
        }
        return resultBuilder.toString();
    }

    private String getHeadingText(Heading heading) {
        StringBuilder textBuilder = new StringBuilder();
        Node child = heading.getFirstChild();
        while (child != null) {
            if (child instanceof Text textNode) {
                textBuilder.append(textNode.getLiteral());
            } else if (child instanceof Code codeNode) {
                textBuilder.append(codeNode.getLiteral());
            }
            child = child.getNext();
        }
        return textBuilder.toString();
    }

    private void copyExport(
            List<PksFile> files,
            String pkms,
            String outputDirectory,
            boolean dryRun
    ) {
        files.forEach(file -> {
                    copyLinkedFiles(file, pkms, outputDirectory, dryRun);
                    copyFile(file.getFilePath(), pkms, outputDirectory, dryRun);
                });
    }

    public record ExportConfig(
            String directory,
            String dbPath,
            String query,
            String output,
            String type,
            String options,
            boolean dryRun,
            boolean load
    ) {}
}
