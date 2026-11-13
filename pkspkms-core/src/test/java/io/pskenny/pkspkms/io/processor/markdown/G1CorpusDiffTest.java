package io.pskenny.pkspkms.io.processor.markdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * G1 real-vault shadow diff: oracle (regex stack) vs the spec-grammar
 * extractor over every {@code .md} in a vault, categories written to
 * {@code /tmp/pkspkms-ast-diff.json}. GATED on
 * {@code -Dpkspkms.g1.dir=<vault>} — the suite never depends on a live vault;
 * read-only over the files, output to /tmp.
 */
public class G1CorpusDiffTest {

    private static final String LEGACY = "legacy";
    private static final String SPEC = "spec";

    private LegacyMarkdownExtractor oracle = new LegacyMarkdownExtractor();
    private FlexmarkMarkdownExtractor spec = new FlexmarkMarkdownExtractor();

    @Test
    @EnabledIfSystemProperty(named = "pkspkms.g1.dir", matches = ".+")
    void shadowDiffOverRealVault() throws IOException {
        Path vault = Paths.get(System.getProperty("pkspkms.g1.dir"));
        List<String> diffs = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(vault)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
                    .toList()) {
                scanned++;
                String relative = vault.relativize(file).toString();
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                String contentSansBom = content.startsWith("\uFEFF") ? content.substring(1) : content;
                MarkdownExtractor.Extraction legacy = oracle.extract(contentSansBom);
                MarkdownExtractor.Extraction actual = spec.extract(contentSansBom);

                Map<String, Object> note = diffNote(relative, contentSansBom, legacy, actual);
                if (!note.isEmpty()) {
                    diffs.add("{" + note + "}");
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scanned", scanned);
        report.put("notesWithDiffs", diffs.size());
        report.put("diffs", diffs);
        Files.writeString(Path.of("/tmp/pkspkms-ast-diff.json"),
                diffsToJson(report), StandardCharsets.UTF_8);
        System.out.printf("G1 shadow diff: scanned %d notes, %d with diffs%n", scanned, diffs.size());
    }

    private Map<String, Object> diffNote(String relative, String content,
                                         MarkdownExtractor.Extraction legacy, MarkdownExtractor.Extraction actual) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!legacy.links().equals(actual.links())) {
            categorize(out, relative, LEGACY, SPEC, "links", legacy.links(), actual.links(), content);
        }
        if (!legacy.wikilinks().equals(actual.wikilinks())) {
            categorize(out, relative, LEGACY, SPEC, "wikilinks", legacy.wikilinks(), actual.wikilinks(), content);
        }
        if (!legacy.tasksOpen().equals(actual.tasksOpen())) {
            categorize(out, relative, LEGACY, SPEC, "tasks", legacy.tasksOpen(), actual.tasksOpen(), content);
        }
        if (!legacy.tasksDone().equals(actual.tasksDone())) {
            categorize(out, relative, LEGACY, SPEC, "tasksDone", legacy.tasksDone(), actual.tasksDone(), content);
        }
        if (!legacy.bases().equals(actual.bases())) {
            categorize(out, relative, LEGACY, SPEC, "bases", legacy.bases(), actual.bases(), content);
        }
        if (!legacy.luabases().equals(actual.luabases())) {
            categorize(out, relative, LEGACY, SPEC, "luabases", legacy.luabases(), actual.luabases(), content);
        }
        return out;
    }

    // simple category inference: mention the offending strings so a human can
    // assign each diff to a whitelisted class or a fix
    private void categorize(Map<String, Object> out, String relative, String from, String to,
                            String key, List<String> legacy, List<String> actual, String content) {
        List<String> reasons = new ArrayList<>();
        boolean codeSpanGhost = content.contains("`[[") || content.contains("`- [ ]");
        boolean referenceLinks = content.contains("\n\n[" ) && content.contains("]: ");
        boolean indentedCode = content.matches("(?s).*\\n        \\[[^\\]]*\\]\\([^)]*\\).*");
        boolean titleLeak = legacy.stream().anyMatch(l -> l.contains(" \""));
        for (String target : diffStrings(legacy, actual)) {
            if (legacy.contains(target) && !actual.contains(target)) {
                if (codeSpanGhost && (content.contains("`" + target + "`") || key.startsWith("tasks"))) {
                    reasons.add("removed:inline-code-ghost");
                } else if (indentedCode) {
                    reasons.add("removed:indented-code-ghost");
                } else if (titleLeak && target.contains(" \"")) {
                    reasons.add("fixed:title-de-leak");
                } else {
                    reasons.add("removed:unexplained");
                }
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("added-or-reordered");
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("key", key);
        diff.put("legacy", legacy);
        diff.put("spec", actual);
        diff.put("categorization", reasons);
        out.put(relative, diff);
    }

    private List<String> diffStrings(List<String> legacy, List<String> actual) {
        List<String> all = new ArrayList<>();
        legacy.forEach(l -> { if (!all.contains(l)) all.add(l); });
        actual.forEach(a -> { if (!all.contains(a)) all.add(a); });
        return all;
    }

    private String diffsToJson(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"scanned\": ").append(report.get("scanned")).append(", ");
        sb.append("\"notesWithDiffs\": ").append(report.get("notesWithDiffs")).append(", ");
        sb.append("\"diffs\": [");
        @SuppressWarnings("unchecked")
        List<String> diffs = (List<String>) report.get("diffs");
        for (int i = 0; i < diffs.size(); i++) {
            sb.append("\n").append(diffs.get(i));
            if (i < diffs.size() - 1) sb.append(",");
        }
        return sb.append("\n]}").toString();
    }
}
