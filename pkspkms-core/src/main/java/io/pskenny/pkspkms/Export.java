/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of PKSPKMS.
 * Copyright (C) 2026 Paul Kenny
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.pskenny.pkspkms;

import static io.pskenny.pkspkms.io.processor.MarkdownProcessor.BASE_PATTERN;
import static io.pskenny.pkspkms.io.processor.MarkdownProcessor.LUABASE_PATTERN;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.processor.markdown.BlockMasker;
import io.pskenny.pkspkms.io.processor.markdown.BomStripper;
import io.pskenny.pkspkms.io.processor.markdown.MarkdownWikilinkEmbedReader;
import io.pskenny.pkspkms.io.processor.markdown.MarkdownWikilinkReader;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.QueryCompiler;
import io.pskenny.pkspkms.repo.query.QueryParser;
import com.vladsch.flexmark.ext.wikilink.WikiLinkExtension;
import com.vladsch.flexmark.formatter.Formatter;
import com.vladsch.flexmark.util.data.MutableDataSet;

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

    // End index just past the frontmatter block, or -1 when absent — the
    // shared Frontmatter oracle (BUGS.md H4): dash values never close the
    // region, `----` never opens one.
    private static int frontmatterEnd(String content) {
        return io.pskenny.pkspkms.io.Frontmatter.endOf(content);
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

        switch (config.mode()) {
            case MARKDOWN:
                markdownExport(matchedFiles, config.dryRun());
                break;
            case COPY:
                copyExport(matchedFiles, config.dryRun());
                break;
        }
    }

    private void markdownExport(List<PksFile> files, boolean dryRun) {
        for (PksFile file : files) {
            try {
                // By design: virtual vault files are not export targets —
                // materialize them with the /cache endpoint first, then export
                // from the main vault. A clean skip instead of content-pipeline
                // noise (BUGS.md 12, closed as by-design).
                if (file.getFilePath().startsWith("@")) {
                    logger.info("Skipping virtual-vault subject {}: virtual vaults are not exportable — cache it into the main vault first", file.getFilePath());
                    continue;
                }
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
        // Fence-masked copy drives every *detection* pass (B14/B15): demo
        // wikilinks/embeds/base blocks inside code fences are never indexed or
        // expanded. Replacement still operates on the raw content — positions
        // coincide textually, and fence runs stay visible in the mask.
        String masked = BlockMasker.maskFencedBlocks(content);
        // Base/luabase replacement rewrites whole blocks; re-mask afterwards so
        // wikilink detection sees the rendered tables instead of the original
        // ```base fence (which keeps its YAML visible by exemption)
        content = replaceLuaBase(content, masked);
        content = replaceBase(content, masked);
        content = expandEmbeds(content, masked, dryRun);
        String detectedForLinks = BlockMasker.maskFencedBlocks(content);
        content = resolveWikilinks(content, detectedForLinks);
        file.getMutableProperties().put("content", content);
    }

    private String readContent(PksFile file) {
        String content = (String) file.getMutableProperties().get("content");
        if (content != null) {
            return content;
        }
        try (InputStream in = inputFs.openInput(file.getFilePath())) {
            return BomStripper.strip(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("Couldn't read file: {}", file.getFilePath());
            return null;
        }
    }

    private String replaceLuaBase(String content, String masked) {
        Matcher matcher = LUABASE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = "";
            boolean fenced = isBlankedOut(masked, matcher.start(), matcher.end());
            if (!fenced) {
                try {
                    String luaBaseYaml = matcher.group(1).trim();
                    replacement = repository.getMarkdownFromLuaBase(luaBaseYaml);
                } catch (Exception e) {
                    logger.warn("Couldn't do LuaBase replacement", e);
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String replaceBase(String content, String masked) {
        Matcher matcher = BASE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = "";
            boolean fenced = isBlankedOut(masked, matcher.start(), matcher.end());
            if (!fenced) {
                try {
                    String baseYaml = matcher.group(1).trim();
                    replacement = repository.getMarkdownFromBase(baseYaml);
                } catch (Exception e) {
                    logger.warn("Couldn't do Base replacement", e);
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // A matched span whose masked twin is all spaces sat inside a fenced code
    // block (masking keeps fence runs visible, so the ``` stayed matchable on
    // raw); such spans must be left untouched (B15).
    private static boolean isBlankedOut(String masked, int start, int end) {
        if (end > masked.length()) {
            return false;
        }
        for (int i = start; i < end; i++) {
            if (masked.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    private String expandEmbeds(String content, String masked, boolean dryRun) {
        List<String> embeds = embedReader.getEmbeds(masked);
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
        // B8: the section renderer re-escapes markdown punctuation; unescape
        // those back to the characters they stand for. Previously a blanket
        // strip destroyed every backslash in embedded content (C:\Users …).
        return io.pskenny.pkspkms.io.processor.markdown.Unescape.markdownPunctuation(replacement);
    }

    private String stripFrontmatter(String content) {
        int end = frontmatterEnd(content);
        if (end == -1) {
            return content;
        }
        return content.substring(end).trim();
    }

    private String resolveWikilinks(String content, String masked) {
        // frontmatter region stays literal: [[links]] inside YAML values are
        // metadata, not body links — rewriting them corrups the YAML (the v6
        // era silently flattened them into markdown links)
        int frontEnd = frontmatterEnd(content);
        List<String> wikilinks = markdownWikilinkReader.getWikilinks(masked);
        for (String wikilink : wikilinks) {
            // [[target#heading|display]] — resolve target, emit alias as display text
            String[] parts = wikilink.split("\\|", 2);
            String linkPath = parts[0];
            String linkText = parts.length > 1 ? parts[1] : linkPath;

            String resolved = repository.resolveWikilink(linkPath);
            if (resolved != null) {
                content = replaceOutsideFrontmatter(content,
                        "[[" + wikilink + "]]", "[" + linkText + "](" + resolved + ")", frontEnd);
            }
        }
        return content;
    }

    /**
     * Replaces every occurrence of {@code token} OUTSIDE the frontmatter
     * region [0, frontEnd); inside it the token survives verbatim. Absent
     * frontmatter (frontEnd -1) replaces everywhere.
     */
    private static String replaceOutsideFrontmatter(String content, String token,
                                                    String replacement, int frontEnd) {
        if (token.isEmpty()) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length());
        int from = 0;
        int at;
        while ((at = content.indexOf(token, from)) >= 0) {
            boolean inFrontmatter = frontEnd != -1 && at < frontEnd;
            out.append(content, from, at);
            out.append(inFrontmatter ? token : replacement);
            from = at + token.length();
        }
        out.append(content, from, content.length());
        return out.toString();
    }

    // Export section rendering is flexmark-only (owner decision): the old
    // commonmark branch was retired because its renderer glued adjacent
    // blocks (`workshop.File`, `SolrSolr is...`) and bracket-escaped task and
    // wiki markers, leaving B8's Unescape pass to repair it afterward — the
    // flex Formatter preserves the source structure natively
    private static final MutableDataSet FLEX_EXPORT_OPTIONS = new MutableDataSet()
            .set(com.vladsch.flexmark.parser.Parser.EXTENSIONS, List.of(
                    WikiLinkExtension.create(),
                    com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension.create()))
            .set(WikiLinkExtension.ALLOW_ANCHORS, true)
            .set(WikiLinkExtension.ALLOW_INLINES, true)
            .set(WikiLinkExtension.LINK_FIRST_SYNTAX, true);
    private static final com.vladsch.flexmark.parser.Parser FLEX_EXPORT_PARSER = com.vladsch.flexmark.parser.Parser.builder(FLEX_EXPORT_OPTIONS).build();
    private static final Formatter FLEX_EXPORT_FORMATTER = Formatter.builder(FLEX_EXPORT_OPTIONS).build();

    // flex source walk semantics — the public entry point; the extractSection
    // name is kept so B8's Unescape pass and its embed callers stay pinned.
    // read_file's `section` scope consults this ONE walk via sectionSlice.
    private String extractSection(String content, String headingText) {
        StringBuilder resultBuilder = new StringBuilder();
        walkSection(content, headingText, new SectionBounds(),
                node -> resultBuilder.append(FLEX_EXPORT_FORMATTER.render(node)));
        return resultBuilder.toString();
    }

    /** Boundary facts of a section walk (openStart -1 = no heading matched). */
    static final class SectionBounds {
        int openStart = -1;   // start offset of the matched heading
        int headingEnd = -1;  // end offset of the matched heading line
        int lastEnd = 0;      // end offset of the last captured body node
        int closeStart = -1;  // start offset of the closing heading (-1 = doc end)
        boolean matched() { return openStart >= 0; }
    }

    /**
     * Shared section walk: first-matched heading wins (case-insensitive
     * full-text via the fence/Code-aware {@link #headingTextOf} oracle) and
     * the section closes at the next same-or-higher-level heading. Body
     * nodes stream through capturedNode; boundary offsets ride the holder.
     */
    private static void walkSection(String content, String headingText, SectionBounds bounds,
                             java.util.function.Consumer<com.vladsch.flexmark.util.ast.Node> capturedNode) {
        com.vladsch.flexmark.util.ast.Node document = FLEX_EXPORT_PARSER.parse(content);
        com.vladsch.flexmark.util.ast.Node node = document.getFirstChild();
        boolean capturing = false;
        int captureLevel = 0;

        while (node != null) {
            if (node instanceof com.vladsch.flexmark.ast.Heading heading) {
                if (capturing) {
                    bounds.closeStart = heading.getStartOffset();
                    if (heading.getLevel() <= captureLevel) {
                        break;
                    }
                } else if (headingTextOf(heading).equalsIgnoreCase(headingText)) {
                    capturing = true;
                    captureLevel = heading.getLevel();
                    bounds.openStart = heading.getStartOffset();
                    int nl = content.indexOf('\n', heading.getStartOffset());
                    bounds.headingEnd = nl < 0 ? heading.getEndOffset() : nl;
                    node = node.getNext();
                    continue;
                }
            }

            if (capturing) {
                bounds.lastEnd = Math.max(bounds.lastEnd, node.getEndOffset());
                capturedNode.accept(node);
            }
            node = node.getNext();
        }
    }

    /**
     * Raw source slice for {@code read_file}'s section scope (flex unlock 1):
     * heading line included, closing heading excluded, trailing blank lines
     * clamped to a single newline. headingStart == -1 = no heading matched —
     * callers surface that as an error with hint candidates.
     */
    public static SectionSlice sectionSlice(String content, String headingText) {
        SectionBounds bounds = new SectionBounds();
        walkSection(content, headingText, bounds, node -> { });
        if (!bounds.matched()) {
            return new SectionSlice(-1, "");
        }
        if (bounds.lastEnd <= bounds.headingEnd) {
            // empty section: the heading line is the entire slice
            return new SectionSlice(bounds.openStart,
                    content.substring(bounds.openStart, bounds.headingEnd) + "\n");
        }
        int end = bounds.closeStart >= 0 ? bounds.closeStart
                : Math.min(bounds.lastEnd, content.length());
        String raw = content.substring(bounds.openStart, Math.max(bounds.openStart, end));
        String clamped = raw.replaceAll("\\n\\s*\\n\\s*$", "\\n");
        return new SectionSlice(bounds.openStart, clamped);
    }

    public record SectionSlice(int headingStart, String content) {}


    /** flex inline children: Text/TextBase carry their SOURCE chars (emphasis
     * markers et al included — fine: the match is against the raw heading
     * text), Code carries its content bearing the fence ticks. Static: read_file
     * consults the same oracle via {@link #sectionSlice}. */
    private static String headingTextOf(com.vladsch.flexmark.ast.Heading heading) {
        StringBuilder textBuilder = new StringBuilder();
        com.vladsch.flexmark.util.ast.Node child = heading.getFirstChild();
        while (child != null) {
            if (child instanceof com.vladsch.flexmark.ast.Text textNode) {
                textBuilder.append(textNode.getChars());
            } else if (child instanceof com.vladsch.flexmark.ast.Code codeNode) {
                String chars = codeNode.getChars().toString();
                textBuilder.append(chars.replaceAll("^`+|`+$", ""));
            }
            child = child.getNext();
        }
        return textBuilder.toString();
    }

    private void copyExport(List<PksFile> files, boolean dryRun) {
        for (PksFile file : files) {
            try {
                // By design: virtual vault files are not export targets
                if (file.getFilePath().startsWith("@")) {
                    logger.info("Skipping virtual-vault subject {}: virtual vaults are not exportable", file.getFilePath());
                    continue;
                }
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
            // D4: pipes only strip the alias for the WIKI and `path|display`
            // shapes — scheme-led URLs (markdown links carrying a literal
            // `|`) survive untouched (B11)
            if (link.indexOf("|") != -1 && shouldStripAlias(link)) {
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

    private static boolean shouldStripAlias(String link) {
        if (link.endsWith("]]")) {
            return true; // raw `[[target|alias]]` inner carrying its brackets
        }
        if (link.toLowerCase(java.util.Locale.ROOT).startsWith("http")) {
            return false; // a URL with a literal pipe is a URL, not an alias split
        }
        String right = link.substring(link.indexOf('|') + 1);
        return !right.isBlank(); // `path|display`
    }

    private void copyFileViaFs(String filePath, boolean dryRun) {
        if (dryRun) return;
        // B20: identical content -> skip; the old exists() check left stale
        // copies behind whenever the source changed after the first export
        if (sameBinary(filePath)) return;
        try (InputStream in = inputFs.openInput(filePath)) {
            outputFs.copyFrom(in, filePath);
        } catch (IOException e) {
            logger.error("Couldn't copy file: {}", filePath, e);
        }
    }

    // Existing output whose bytes equal the source's Blake3 stays untouched:
    // the hash comes from the FILES row, or — when the file has no row yet
    // (fresh cache upserts) — from streaming the source.
    private boolean sameBinary(String filePath) {
        if (!outputFs.exists(filePath)) {
            return false;
        }
        String outputHash;
        try (InputStream out = outputFs.openInput(filePath)) {
            outputHash = io.pskenny.pkspkms.io.Blake3Util.hashStream(out);
        } catch (IOException e) {
            return false;
        }
        String candidate = repository.blake3Of(filePath).orElseGet(() -> {
            try (InputStream in = inputFs.openInput(filePath)) {
                return io.pskenny.pkspkms.io.Blake3Util.hashStream(in);
            } catch (IOException e) {
                return null;
            }
        });
        return candidate != null && candidate.equals(outputHash);
    }

    public enum ExportMode {
        MARKDOWN,
        COPY,
    }

    public static final class ExportConfig {
        private final String directory;
        private final String dbPath;
        private final String query;
        private final String output;
        private final ExportMode mode;
        private final boolean dryRun;

        public ExportConfig(String directory, String dbPath, String query, String output, ExportMode mode, boolean dryRun) {
            this.directory = directory;
            this.dbPath = dbPath;
            this.query = query;
            this.output = output;
            this.mode = mode;
            this.dryRun = dryRun;
        }

        public String directory() { return directory; }
        public String dbPath() { return dbPath; }
        public String query() { return query; }
        public String output() { return output; }
        public ExportMode mode() { return mode; }
        public boolean dryRun() { return dryRun; }
    }
}
