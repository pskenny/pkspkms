package io.pskenny.pkspkms;

import static io.pskenny.pkspkms.io.processor.MarkdownProcessor.BASE_PATTERN;
import static io.pskenny.pkspkms.io.processor.MarkdownProcessor.LUABASE_PATTERN;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.processor.markdown.MarkdownWikilinkEmbedReader;
import io.pskenny.pkspkms.io.processor.markdown.MarkdownWikilinkReader;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.QueryCompiler;
import io.pskenny.pkspkms.repo.query.QueryParser;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

public final class Export {
    private static final Logger logger = LoggerFactory.getLogger(Export.class);

    // Text embeds above this size become links instead of inlining
    private static final int MAX_INLINE_CHARS = 1_000_000;

    private static boolean isMarkdownFile(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".md");
    }

    // Decode %XX sequences only; '+' is a literal plus in file paths. Malformed
    // or stray '%' stays literal.
    private static String percentDecode(String path) {
        if (!path.contains("%")) {
            return path;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                try {
                    out.write(Integer.parseInt(path.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException e) {
                    // fall through: literal '%'
                }
            }
            out.write(c);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // End index just past the frontmatter block, or -1 when absent (mirrors stripFrontmatter)
    private static int frontmatterEnd(String content) {
        if (!content.startsWith("---")) {
            return -1;
        }
        int end = content.indexOf("---", 3);
        return end == -1 ? -1 : end + 3;
    }

    private final PksFileRepository repository;
    private final ExportConfig config;
    private final PkmsFileSystem inputFs;
    private final PkmsFileSystem outputFs;
    private final MarkdownWikilinkReader markdownWikilinkReader = new MarkdownWikilinkReader();
    private final MarkdownWikilinkEmbedReader embedReader = new MarkdownWikilinkEmbedReader();

    public Export(ExportConfig config, PksFileRepository repository, PkmsFileSystem inputFs, PkmsFileSystem outputFs) {
        this.config = config;
        this.repository = repository;
        this.inputFs = inputFs;
        this.outputFs = outputFs;
    }

    public void export() {
        CompiledQuery query = new QueryCompiler().compile(new QueryParser().parse(config.query()), repository.getPropertyTypes());
        List<PksFile> matchedFiles = repository.searchRegular(query);
        if (matchedFiles.isEmpty()) {
            logger.info("No files to export");
            return;
        }
        logger.info("Matched {} files", matchedFiles.size());

        switch (config.type()) {
            case "markdown":
                markdownExport(matchedFiles, config.dryRun());
                break;
            case "copy":
                copyExport(matchedFiles, config.dryRun());
                break;
            default:
                logger.error("No export type specified");
                break;
        }
    }

    private void markdownExport(List<PksFile> files, boolean dryRun) {
        for (PksFile file : files) {
            try {
                // Non-markdown subjects (media etc.) must only ever be copied
                // byte-exactly — running them through the content pipeline
                // corrupts binary via UTF-8 decode/re-encode.
                if (!isMarkdownFile(file.getFilePath())) {
                    copyFileViaFs(file.getFilePath(), dryRun);
                    continue;
                }
                copyLinkedFiles(file, dryRun);
                processFileForExport(file, dryRun);
                writePksFile(file, dryRun);
                logger.debug("Exported {}", file.getFilePath());
            } catch (RuntimeException e) {
                logger.error("Export failed for {}, skipping", file.getFilePath(), e);
            }
        }
        logger.info("Processed {} files{}", files.size(), dryRun ? " (dry run)" : "");
    }

    private void processFileForExport(PksFile file, boolean dryRun) {
        String content = readContent(file);
        if (content == null) {
            return;
        }
        content = replaceLuaBase(content);
        content = replaceBase(content);
        content = expandEmbeds(content, dryRun);
        content = resolveWikilinks(content);
        file.getMutableProperties().put("content", content);
    }

    private String readContent(PksFile file) {
        String content = (String) file.getMutableProperties().get("content");
        if (content != null) {
            return content;
        }
        try (InputStream in = inputFs.openInput(file.getFilePath())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Couldn't read file: {}", file.getFilePath());
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
                replacement = repository.getMarkdownFromLuaBase(luaBaseYaml);
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
                replacement = repository.getMarkdownFromBase(baseYaml);
            } catch (Exception e) {
                logger.warn("Couldn't do Base replacement", e);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String expandEmbeds(String content, boolean dryRun) {
        List<String> embeds = embedReader.getEmbeds(content);
        int frontEnd = frontmatterEnd(content);
        for (String embed : embeds) {
            String token = "![[" + embed + "]]";
            // First occurrence decides the replacement form (documented simplification)
            int first = content.indexOf(token);
            boolean inFrontmatter = frontEnd != -1 && first != -1 && first < frontEnd;
            content = content.replace(token, resolveEmbed(embed, dryRun, inFrontmatter));
        }
        return content;
    }

    private String resolveEmbed(String embed, boolean dryRun, boolean inFrontmatter) {
        String originalEmbed = embed;
        String embedHeading = null;
        String searchFile = embed;

        if (searchFile.contains("#")) {
            int hashIndex = searchFile.indexOf("#");
            embedHeading = searchFile.substring(hashIndex + 1);
            searchFile = searchFile.substring(0, hashIndex);
        }

        // Content-derived DSL queries are fragile (apostrophes/parens in filenames);
        // reuse the exact-match resolver instead
        String resolvedPath = repository.resolveWikilink(searchFile);
        if (resolvedPath == null) {
            logger.warn("Embed '{}' matched no file, exporting empty", originalEmbed);
            return "";
        }

        // Frontmatter occurrences become a bare path: "!" would start a YAML tag.
        // Non-md targets are copied so the reference resolves after export.
        if (inFrontmatter) {
            if (!isMarkdownFile(resolvedPath)) {
                copyFileViaFs(resolvedPath, dryRun);
            }
            return resolvedPath;
        }

        // Text embeds only for markdown files; everything else -> copy + ![](path)
        if (!isMarkdownFile(resolvedPath)) {
            copyFileViaFs(resolvedPath, dryRun);
            return "![](" + resolvedPath + ")";
        }

        PksFile foundFile = new PksFile(resolvedPath, new HashMap<>());
        String foundContent = readContent(foundFile);
        if (foundContent == null) {
            logger.warn("Embed '{}' source unreadable, exporting empty", originalEmbed);
            return "";
        }
        if (foundContent.length() > MAX_INLINE_CHARS) {
            logger.warn("Embed '{}' target exceeds {} chars, exporting link instead",
                    originalEmbed, MAX_INLINE_CHARS);
            copyFileViaFs(resolvedPath, dryRun);
            return "![](" + resolvedPath + ")";
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
            // [[target#heading|display]] — resolve target, emit alias as display text
            String[] parts = wikilink.split("\\|", 2);
            String linkPath = parts[0];
            String linkText = parts.length > 1 ? parts[1] : linkPath;

            String resolved = repository.resolveWikilink(linkPath);
            if (resolved != null) {
                content = content.replace("[[" + wikilink + "]]", "[" + linkText + "](" + resolved + ")");
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

    private void copyExport(List<PksFile> files, boolean dryRun) {
        for (PksFile file : files) {
            try {
                copyLinkedFiles(file, dryRun);
                copyFileViaFs(file.getFilePath(), dryRun);
                logger.debug("Copied {}", file.getFilePath());
            } catch (RuntimeException e) {
                logger.error("Export failed for {}, skipping", file.getFilePath(), e);
            }
        }
        logger.info("Processed {} files{}", files.size(), dryRun ? " (dry run)" : "");
    }

    private void writePksFile(PksFile file, boolean dryRun) {
        // Defense in depth: only markdown subjects go through the String
        // content pipeline; everything else is copied byte-exactly.
        if (file.getMutableProperties().containsKey("content")
                && isMarkdownFile(file.getFilePath())) {
            if (dryRun) return;
            try {
                outputFs.writeString(file.getFilePath(), (String) file.getMutableProperties().get("content"));
            } catch (IOException e) {
                logger.error("Couldn't write file: {}", file.getFilePath(), e);
            }
        } else {
            copyFileViaFs(file.getFilePath(), dryRun);
        }
    }

    private void copyLinkedFiles(PksFile file, boolean dryRun) {
        List<String> links = file.getAsList("links");
        if (links.isEmpty()) return;
        for (String link : links) {
            if (link.indexOf("|") != -1) {
                link = link.substring(0, link.indexOf("|"));
            }
            String target = percentDecode(link);
            if (isMarkdownFile(target)) {
                continue;   // notes export themselves
            }
            try {
                copyFileViaFs(target, dryRun);
            } catch (RuntimeException e) {
                logger.warn("Couldn't copy linked file {}, skipping", target, e);
            }
        }
    }

    private void copyFileViaFs(String filePath, boolean dryRun) {
        if (outputFs.exists(filePath)) return;
        if (dryRun) return;
        try (InputStream in = inputFs.openInput(filePath)) {
            outputFs.copyFrom(in, filePath);
        } catch (IOException e) {
            logger.error("Couldn't copy file: {}", filePath, e);
        }
    }

    public static final class ExportConfig {
        private final String directory;
        private final String dbPath;
        private final String query;
        private final String output;
        private final String type;
        private final boolean dryRun;

        public ExportConfig(String directory, String dbPath, String query, String output, String type, boolean dryRun) {
            this.directory = directory;
            this.dbPath = dbPath;
            this.query = query;
            this.output = output;
            this.type = type;
            this.dryRun = dryRun;
        }

        public String directory() { return directory; }
        public String dbPath() { return dbPath; }
        public String query() { return query; }
        public String output() { return output; }
        public String type() { return type; }
        public boolean dryRun() { return dryRun; }
    }
}
