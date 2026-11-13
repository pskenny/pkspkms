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

package io.pskenny.pkspkms.io.processor.markdown;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.wikilink.WikiImage;
import com.vladsch.flexmark.ext.wikilink.WikiLink;
import com.vladsch.flexmark.ext.wikilink.WikiNode;
import com.vladsch.flexmark.ext.wikilink.WikiLinkExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The flexmark extraction engine (owner decision, deviations accepted):
 * one flexmark AST walk with the official {@code flexmark-ext-wikilink}
 * (anchors + inline content allowed, link-first syntax — Obsidian shape)
 * and {@code flexmark-ext-gfm-tasklist} extensions.
 *
 * Known deviations vs the commonmark engine (graceful: no wrong values, an
 * entry simply doesn't extract):
 * - angle-bracket destinations `[x](<dest with spaces>)` extract nothing
 *   (flexmark's lenient inline-HTML pass consumes the destination)
 * - quoted destinations `[x]("dest with spaces")` — same
 * - reference-style links `[text][label]` stay unresolved LinkRef nodes —
 *   ignored here (no destination to read)
 *
 * WikiLink/WikiImage nodes, task items, headings and fences are unaffected.
 * {@code LinkRef}/{@code HtmlInline} nodes are deliberately never read.
 */
public final class FlexmarkMarkdownExtractor implements MarkdownExtractor {

    private static final int MAX_TASKS = 200;

    private static final MutableDataSet OPTIONS = new MutableDataSet()
            .set(Parser.EXTENSIONS, List.of(
                    WikiLinkExtension.create(),
                    TaskListExtension.create()))
            .set(WikiLinkExtension.ALLOW_ANCHORS, true)
            .set(WikiLinkExtension.ALLOW_INLINES, true)
            .set(WikiLinkExtension.LINK_FIRST_SYNTAX, true)
            // Obsidian embeds (`![[x]]`) parse as WikiImage — WITHOUT this the
            //! is a bare Text node, the WikiImage branch below never fires and
            // every embed records as a plain wikilink with a wrong offset
            // (BUGS.md H2)
            .set(WikiLinkExtension.IMAGE_LINKS, true);

    // flexmark Parser is thread-safe once built; the loader's parse pool
    // shares one instance
    private static final Parser PARSER = Parser.builder(OPTIONS).build();

    private static final Set<String> BASE_EXEMPT_INFOS = Set.of("base", "luabase");

    @Override
    public Extraction extract(String content) {
        List<String> links = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        Set<String> wikiTexts = new LinkedHashSet<>();
        Set<String> open = new LinkedHashSet<>();
        Set<String> done = new LinkedHashSet<>();
        List<String> bases = new ArrayList<>();
        List<String> luabases = new ArrayList<>();
        List<Map<String, Object>> outline = new ArrayList<>();
        List<Map<String, Object>> spans = new ArrayList<>();
        List<Map<String, Object>> linkSpans = new ArrayList<>();
        List<Map<String, Object>> taskSpans = new ArrayList<>();
        List<Map<String, Object>> embedSpans = new ArrayList<>();

        // line starts for offset→line derivation (flexmark start offsets are
        // document-relative to the parse input == body string)
        List<Integer> lineStarts = new ArrayList<>();
        int pos = 0;
        while (pos < content.length()) {
            lineStarts.add(pos);
            int nl = content.indexOf('\n', pos);
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }

        Node document = PARSER.parse(content);
        walk(document, node -> {
            if (node instanceof Link link) {
                addLink(link.getUrl().toString(), seenLinks, links);
                addLinkSpan("link", link.getUrl().toString(), node, linkSpans, lineStarts);
            } else if (node instanceof Image image) {
                addLink(image.getUrl().toString(), seenLinks, links);
                addLinkSpan("image", image.getUrl().toString(), node, linkSpans, lineStarts);
            } else if (node instanceof WikiLink wiki) {
                addWiki(wiki, wiki.getPageRef().toString(), wikiTexts, spans, lineStarts);
            } else if (node instanceof WikiImage wikiImage) {
                addWiki(wikiImage, wikiImage.getPageRef().toString(), wikiTexts, spans, lineStarts);
            } else if (node instanceof Heading heading) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("text", headingText(heading));
                item.put("level", heading.getLevel());
                outline.add(item);
            } else if (node instanceof TaskListItem item) {
                addTask(item, open, done, content, taskSpans, lineStarts);
            } else if (node instanceof FencedCodeBlock fenced) {
                String info = fenced.getInfo().toString();
                if (!BASE_EXEMPT_INFOS.contains(info)) {
                    return;
                }
                // v6 embed provenance: which note carries which view, line-precise
                Map<String, Object> embedSpan = new LinkedHashMap<>();
                embedSpan.put("kind", info);
                int eOffset = fenced.getStartOffset();
                embedSpan.put("offset", eOffset);
                int eIdx = 0;
                for (int i = 0; i < lineStarts.size(); i++) {
                    if (lineStarts.get(i) <= eOffset) {
                        eIdx = i;
                    }
                }
                embedSpan.put("line", lineStarts.isEmpty() ? 1 : Math.min(eIdx + 1, lineStarts.size()));
                embedSpans.add(embedSpan);
                String yaml = fenced.getContentChars().toString();
                // D1: an empty "base" fence is not a view
                if (yaml.strip().isEmpty()) {
                    return;
                }
                for (String link : new MarkdownLinkReader().getMarkdownLinksProperties(yaml)) {
                    addLink(link, seenLinks, links);
                }
                for (String inner : new MarkdownWikilinkReader().getWikilinks(yaml)) {
                    wikiTexts.add(inner);
                }
                if ("base".equals(info)) {
                    bases.add(yaml.trim());
                } else {
                    luabases.add(yaml.trim());
                }
            }
        });

        return new Extraction(links, List.copyOf(wikiTexts), List.copyOf(open), List.copyOf(done),
                bases, luabases, outline, spans, linkSpans, taskSpans, embedSpans);
    }

    private interface NodeConsumer {
        void accept(Node node);
    }

    // Iterative walk: the recursive twin overflowed the JVM stack on deeply
    // nested quote chains (BUGS.md D2 — a parse-task SOE silently dropped the
    // file from the index).
    private static void walk(Node root, NodeConsumer consumer) {
        java.util.Deque<Node> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            consumer.accept(node);
            Node last = null;
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                last = child;
            }
            // push children in reserve order so the first child evaluates first
            for (Node child = last; child != null; child = child.getPrevious()) {
                stack.push(child);
            }
        }
    }

    private static void addLink(String url, Set<String> seen, List<String> out) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return;
        }
        if (url == null || url.isEmpty()) {
            return;
        }
        if (seen.add(url)) {
            out.add(url);
        }
    }

    // raw inner text contract: everything between the double brackets —
    // images start at char 3 (![[), links at 2 ([[)
    private static void addLinkSpan(String kind, String destination, Node node,
                                    List<Map<String, Object>> linkSpans, List<Integer> lineStarts) {
        if (destination == null || destination.isBlank()) {
            return;
        }
        int offset = node.getStartOffset();
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("kind", kind);
        span.put("target", destination.strip());
        span.put("offset", offset);
        int lineIndex = 0;
        for (int i = 0; i < lineStarts.size(); i++) {
            if (lineStarts.get(i) <= offset) {
                lineIndex = i;
            }
        }
        span.put("line", lineStarts.isEmpty() ? 1 : lineIndex + 1);
        linkSpans.add(span);
    }

    private static void addWiki(Node node, String pageRef, Set<String> seen,
                                List<Map<String, Object>> spans, List<Integer> lineStarts) {
        String full = node.getChars().toString();
        boolean embed = node instanceof WikiImage;
        int start = embed ? 3 : 2;
        String inner = full.substring(start, full.length() - 2);
        // D1: blank inners ([[ ]]) and pure self-anchors ([[#h]]) are not
        // targets — they never join wikilink facts in either engine (the
        // legacy oracle skips them the same way)
        String outer = inner.strip();
        if (outer.isEmpty() || outer.startsWith("#")) {
            return;
        }
        seen.add(inner);
        if (!pageRef.isBlank()) {
            WikiNode wikiNode = node instanceof WikiNode wn ? wn : null;
            String target = pageRef.strip();
            String anchor = wikiNode != null && wikiNode.getAnchorRef().isNotNull()
                    ? wikiNode.getAnchorRef().toString().strip()
                    : null;
            // flexmark never splits anchors for WikiImage: its ctor hardcodes
            // allowAnchors=false, so `![[note#Section]]` keeps `#Section` in
            // the pageRef. Split locally — first unescaped-style `#` (the
            // WikiLink path splits on the first `#` the same way), so embed
            // anchors behave like link anchors (BUGS.md H2 companion).
            if (embed) {
                int hash = target.indexOf('#');
                if (hash >= 0) {
                    String candidate = target.substring(hash + 1).strip();
                    if (!candidate.isEmpty()) {
                        anchor = candidate;
                        target = target.substring(0, hash);
                    }
                }
            }
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("kind", "wikilink");
            span.put("target", target);
            // the embed/link distinction (BUGS.md H2): true only for ![[ embeds
            if (embed) {
                span.put("embed", Boolean.TRUE);
            }
            // v5 span facts: the parsed `#anchor` (flex splits WikiNode into
            // pageRef/anchorRef/text — the pre-v5 span dropped all three)
            if (anchor != null && !anchor.isEmpty()) {
                span.put("anchor", anchor);
            }
            // display (links) / alt (images): the text after `|`, when present.
            // D1: `[[x|]]` — the separator without text adds no empty key.
            if (wikiNode != null && wikiNode.getTextSeparatorMarker().isNotNull()) {
                String display = wikiNode.getText().toString().strip();
                if (!display.isEmpty()) {
                    span.put("display", display);
                }
            }
            // node start: the `!` for embeds (chars "![[…]]"), the `[` for links
            int offset = node.getStartOffset();
            span.put("offset", offset);
            int line = Math.min(offset, Integer.MAX_VALUE);
            int lineIndex = 0;
            for (int i = 0; i < lineStarts.size(); i++) {
                if (lineStarts.get(i) <= offset) {
                    lineIndex = i;
                }
            }
            line = lineStarts.isEmpty() ? 1 : lineIndex + 1;
            span.put("line", line);
            spans.add(span);
        }
    }

    private static void addTask(TaskListItem item, Set<String> open, Set<String> done, String content,
                                List<Map<String, Object>> taskSpans, List<Integer> lineStarts) {
        // text = the item's paragraph's first source line after the checkbox —
        // the legacy single-line contract
        String task = "";
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Paragraph paragraph) {
                int start = paragraph.getStartOffset();
                int nl = content.indexOf('\n', start);
                if (nl < 0) {
                    nl = content.length();
                }
                String raw = content.substring(Math.min(start, content.length()), nl).stripLeading();
                task = raw.replaceFirst("^\\[[ xX]\\]\\s*", "").strip();
                break;
            }
        }
        if (task.isEmpty()) {
            return;
        }
        Set<String> target = item.isItemDoneMarker() ? done : open;
        if (target.size() < MAX_TASKS) {
            target.add(task);
            if (taskSpans.size() < MAX_TASKS
                    && item.getFirstChild() instanceof Paragraph paragraph) {
                Map<String, Object> span = new LinkedHashMap<>();
                span.put("text", task);
                span.put("open", !item.isItemDoneMarker());
                // paragraph start offset == the checkbox line start
                int start = paragraph.getStartOffset();
                span.put("offset", start);
                int idx = 0;
                for (int i = 0; i < lineStarts.size(); i++) {
                    if (lineStarts.get(i) <= start) {
                        idx = i;
                    }
                }
                span.put("line", lineStarts.isEmpty() ? 1 : Math.min(idx + 1, lineStarts.size()));
                taskSpans.add(span);
            }
        }
    }

    private static String headingText(Heading heading) {
        StringBuilder sb = new StringBuilder();
        collectInlineText(heading, sb);
        return sb.toString().trim().replaceFirst("^#+\\s*", "");
    }

    private static void collectInlineText(Node node, StringBuilder sb) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof com.vladsch.flexmark.ast.Text text) {
                sb.append(text.getChars().toString());
            } else if (child instanceof WikiLink wiki) {
                sb.append(wiki.getLink().toString());
            } else {
                collectInlineText(child, sb);
            }
        }
    }
}
