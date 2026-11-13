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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.vladsch.flexmark.util.ast.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Export-section round-trip diff: the flex Formatter's rendered section vs
 * the SOURCE block text (per-heading raw slice) over every {@code .md} in a
 * vault — verifies the round-trip keeps the line content (we treat any
 * glued-word loss as a bug). GATED on {@code -Dpkspkms.g1.dir=<vault>} —
 * the suite never depends on a live vault; read-only over files, report
 * written to {@code /tmp/pkspkms-export-diff.json}.
 */
public class ExportSectionDiffTest {

    private Export exportInstance;
    private Method flexMethod;
    private Method headingMethodFlex;

    @Test
    @EnabledIfSystemProperty(named = "pkspkms.g1.dir", matches = ".+")
    void exportSectionShadowDiff() throws Exception {
        exportInstance = new Export(new io.pskenny.pkspkms.Export.ExportConfig(
                "unused", "", "", "", io.pskenny.pkspkms.Export.ExportMode.MARKDOWN, false),
                new io.pskenny.pkspkms.test.UnsupportedPksRepository() {}, null, null);
        // reflection over BOTH section implementations; no vault writes occur
        // probe — adapt if the constructor differs; reflection over the private
        // section methods keeps the oracle/or*-both paths untouched
        flexMethod = Export.class.getDeclaredMethod("extractSection", String.class, String.class);
        flexMethod.setAccessible(true);
        headingMethodFlex = Export.class.getDeclaredMethod("headingTextOf",
                Class.forName("com.vladsch.flexmark.ast.Heading"));
        headingMethodFlex.setAccessible(true);

        Path vault = Paths.get(System.getProperty("pkspkms.g1.dir"));
        List<String> diffs = new ArrayList<>();
        int scanned = 0;
        int sectioned = 0;
        try (Stream<Path> walk = Files.walk(vault)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
                    .toList()) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                String sansBom = content.startsWith("\uFEFF") ? content.substring(1) : content;
                scanned++;
                for (var heading : headings(sansBom)) {
                    sectioned++;
                    String rendered = (String) flexMethod.invoke(exportInstance, sansBom, heading);
                    // oracle: the SOURCE lines of the section (everything after
                    // the heading line until the next heading line) — a
                    // whitespace-normalized compare treats line-shape as the
                    // only acceptable drift (separator normalization), glued
                    // words count as bugs
                    String expectedSection = sourceSection(sansBom, heading);
                    String a = rendered.replaceAll("\\s+", " ").strip();
                    String b = expectedSection.replaceAll("\\s+", " ").strip();
                    if (!a.equals(b)) {
                        diffs.add("{\"file\": " + quote(vault.relativize(file).toString())
                                + ", \"heading\": " + quote(heading)
                                + ", \"class\": \"glued-or-lost-content\""
                                + ", \"expected\": " + quote(expectedSection)
                                + ", \"rendered\": " + quote(rendered) + "}");
                    }
                }
            }
        }
        String report = "{"
                + "\"scanned\": " + scanned
                + ", \"sectionsChecked\": " + sectioned
                + ", \"sectionsWithDifferentBytes\": " + diffs.size()
                + ", \"diffs\": [" + String.join(",\n", diffs) + "]}";
        Files.writeString(Path.of("/tmp/pkspkms-export-diff.json"), report, StandardCharsets.UTF_8);
        System.out.printf("Export section diff: scanned %d notes, %d sections, %d differing bytes-render%n",
                scanned, sectioned, diffs.size());
    }

    private List<String> headings(String content) {
        // fence-aware heading scan: `#` lines inside ```/~~~ fences are NOT
        // headings; matches the flex extractor's flexHeadingText semantics
        // (Code children contribute their marked literal)
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        String fence = "";
        for (String line : content.split("\n", -1)) {
            var t = line.strip();
            if (!inFence && (t.startsWith("```") || t.startsWith("~~~"))) {
                inFence = true;
                fence = t.substring(0, 3);
                continue;
            }
            if (inFence && t.startsWith(fence)) {
                inFence = false;
                continue;
            }
            if (inFence) {
                continue;
            }
            var m = java.util.regex.Pattern.compile("^#{1,6}\\s+([^-#].*)?").matcher(line);
            if (m.matches() || (line.matches("^#{1,6}\\s+.*"))) {
                // heading text exactly as flexHeadingText derives it
                StringBuilder headText = new StringBuilder();
                com.vladsch.flexmark.parser.Parser p = com.vladsch.flexmark.parser.Parser.builder(
                        new com.vladsch.flexmark.util.data.MutableDataSet()
                                .set(com.vladsch.flexmark.parser.Parser.EXTENSIONS, java.util.List.of(
                                        com.vladsch.flexmark.ext.wikilink.WikiLinkExtension.create(),
                                        com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension.create())))
                        .build();
                Node doc = p.parse(line);
                collectHeadingText(doc, headText);
                out.add(headText.toString().trim());
            }
        }
        return out;
    }

    private void collectHeadingText(Node node, StringBuilder sb) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof com.vladsch.flexmark.ast.Text text) {
                sb.append(text.getChars());
            } else if (child instanceof com.vladsch.flexmark.ast.Code code) {
                var chars = code.getChars().toString();
                sb.append(chars.replaceAll("^`+|`+$", ""));
            } else {
                collectHeadingText(child, sb);
            }
        }
    }

    // oracle slice with the SAME capture semantics as extractSection: capture
    // the matched heading's own level, keep going until a heading of level
    // <= capture (h1 capture keeps h2/h3 subsections); fence-aware — `#`
    // lines inside ```/~~~ fences are never headings
    private String sourceSection(String content, String headingText) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean capturing = false;
        int captureLevel = 0;
        boolean inFence = false;
        String fenceMark = "";
        for (String line : lines) {
            var tStrip = line.strip();
            if (!inFence && (tStrip.startsWith("```") || tStrip.startsWith("~~~"))) {
                inFence = true;
                fenceMark = tStrip.substring(0, 3);
                if (capturing) {
                    sb.append(line).append('\n'); // fence markers are source too
                }
                continue;
            }
            if (inFence && tStrip.startsWith(fenceMark)) {
                inFence = false;
                if (capturing) {
                    sb.append(line).append('\n');
                }
                continue;
            }
            var h = java.util.regex.Pattern.compile("^(#{1,6})(\\s.*|\\s*)$").matcher(line);
            if (h.matches() && !inFence) {
                if (capturing && h.group(1).length() <= captureLevel) {
                    break;
                }
                if (!capturing && h.group(2).strip().equalsIgnoreCase(headingText)) {
                    capturing = true;
                    captureLevel = h.group(1).length();
                } else if (capturing) {
                    // subsection headings inside the capture stay in the slice
                    sb.append(line).append('\n');
                }
                continue;
            }
            if (capturing) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\r') sb.append("\\r");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append("\"").toString();
    }

    private String json(String s) {
        return quote(s);
    }
}
