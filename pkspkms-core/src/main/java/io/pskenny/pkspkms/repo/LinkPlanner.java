package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.sqlite.SqliteFiles.NameIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Load-time wikilink resolution as a pure function: parsed files in, one
 * mutation applied to each file's properties out (created/resolved/dangling
 * lists). No JDBC, no filesystem — the trickiest loader step gains
 * DB-free tests and stays byte-identical in behavior.
 *
 * Precedence is the documented one: path > filename-ext > base name
 * (BUGS.md B7 ordering for collisions inside each layer). Resolution is
 * vault-scoped: callers feed a {@link NameIndex} already scoped to one vault
 * (BUGS.md B6 — no cross-vault leaks).
 */
public final class LinkPlanner {

    private LinkPlanner() {}

    /** 50 distinct dangling targets per note — the hygiene cap. */
    private static final int MAX_DANGLERS = 50;

    /** Attribution lists removed by the loader before persistence. */
    private static final List<String> INTERIM_KEYS = List.of("wikilinkTargets", "markdownTargets");

    /**
     * Applies resolution to every parsed file: {@code markdownTargets} and
     * {@code links}/{@code wikilinkTargets} attribution plus
     * {@code danglingLinks}. The loader persists files afterwards.
     */
    public static void plan(List<PksFile> pksFiles, NameIndex nameIndex) {
        Map<String, String> filePathMap = new HashMap<>(nameIndex.byPath());
        Map<String, String> fileNameExtMap = new HashMap<>(nameIndex.byNameExt());
        Map<String, String> fileNameMap = new HashMap<>(nameIndex.byName());

        // A. Merge the freshly parsed files' own identities (path/derived names)
        for (PksFile file : pksFiles) {
            String path = file.getFilePath();
            if (path == null) {
                continue;
            }
            String nameExt = path;
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash != -1) {
                nameExt = path.substring(lastSlash + 1);
            }
            String name = nameExt;
            int lastDot = nameExt.lastIndexOf('.');
            if (lastDot != -1) {
                name = nameExt.substring(0, lastDot);
            }
            filePathMap.put(path, path);
            fileNameExtMap.put(nameExt, path);
            fileNameMap.put(name, path);
        }

        // B. Resolve each file's wikilinks in-memory
        for (PksFile pksFile : pksFiles) {
            // B10 attribution snapshot: the pure markdown targets as parsed,
            // before resolved wikilink targets merge into the union
            List<String> pureMarkdown = pksFile.<String>getAsList("links").stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!pureMarkdown.isEmpty()) {
                pksFile.addToProperty("markdownTargets", pureMarkdown);
            }

            List<String> resolved = new ArrayList<>();
            List<String> danglers = new ArrayList<>();
            for (Object obj : pksFile.getAsList("wikilinks")) {
                if (obj == null) {
                    continue;
                }
                String wikilink = obj.toString();
                String cleanLink = wikilink.split("\\|")[0]   // drop display alias
                        .split("#")[0]                        // drop heading/block ref
                        .replace("[[", "")
                        .replace("]]", "");

                String target = filePathMap.get(cleanLink);
                if (target == null) target = fileNameExtMap.get(cleanLink);
                if (target == null) target = fileNameMap.get(cleanLink);

                if (target != null) {
                    resolved.add(target);
                } else {
                    danglers.add(cleanLink);
                }
            }

            // Dangling wikilinks (hygiene report): unresolved cleaned targets,
            // distinct, capped per note. Persisted like the other derived
            // lists — queryable as danglingLinks:* / danglingLinks:Name
            List<String> dangling = danglers.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .limit(MAX_DANGLERS)
                    .toList();
            if (!dangling.isEmpty()) {
                pksFile.addToProperty("danglingLinks", dangling);
            }

            List<String> distinctResolved = resolved.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (distinctResolved.isEmpty()) {
                continue;
            }
            pksFile.addToProperty("links", distinctResolved);
            // B10 attribution: remember the wikilink-sourced targets separately
            // (consumed in the loader's link-writing phase; the stitched JSON
            // keeps the merged union shape)
            pksFile.addToProperty("wikilinkTargets", distinctResolved);
        }
    }

    /** Salience note: which interim attribution keys the loader strips. */
    public static List<String> interimKeys() {
        return INTERIM_KEYS;
    }
}
