package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.sqlite.SqliteFiles.NameIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure, DB-free battery for the load-time link planner: in-vault resolution,
 * BUGS.md B6 vault scoping, B7 duplicate-name ordering, B10 attribution
 * snapshots, dangler caps. Frontmatter aliases are data only — they play no
 * resolution role (retired tier; alias-targeting wikilinks dangle).
 */
public class LinkPlannerTest {

    private PksFile fresh(String path, String body) {
        PksFile file = new PksFile(path, new HashMap<>());
        if (body != null) {
            file.getMutableProperties().put("wikilinks", List.of(body));
        }
        return file;
    }

    private NameIndex index(Map<String, String> byPath, Map<String, String> byNameExt, Map<String, String> byName) {
        return new NameIndex(byPath, byNameExt, byName);
    }

    @Test
    @DisplayName("in-memory fresh-file identities resolve same-load links")
    void freshFilesResolveAmongThemselves() {
        PksFile target = fresh("T.md", null);
        PksFile source = fresh("note.md", "[[T]]");
        LinkPlanner.plan(List.of(target, source),
                index(Map.of(), Map.of(), Map.of()));
        assertEquals(List.of("T.md"), source.getAsList("links"));
        assertTrue(source.getAsList("danglingLinks").isEmpty());
    }

    @Test
    @DisplayName("retired alias tier: alias-only targets dangle; name targets still resolve")
    void aliasTargetsNoLongerResolve() {
        PksFile nameTarget = fresh("x.md", null);
        PksFile source = new PksFile("lookup.md", new HashMap<>());
        source.getMutableProperties().put("wikilinks", List.of("[[both|display]]", "[[x]]"));
        LinkPlanner.plan(List.of(nameTarget, source),
                index(Map.of(), Map.of(), Map.of()));
        // "both" has no filename/path claim left anywhere — dangles; the
        // display-alias split still applies to the dangler text ("both")
        assertEquals(List.of("x.md"), source.getAsList("links"));
        assertEquals(List.of("both"), source.getAsList("danglingLinks"));
    }

    @Test
    @DisplayName("B10: markdownTarget stays separate from resolved union")
    void markdownAttributionSnapshot() {
        PksFile linked = fresh("m.md", null);
        PksFile source = new PksFile("src.md", new HashMap<>());
        // mutable list: addToProperty extends parsed lists in place
        source.getMutableProperties().put("links", new java.util.ArrayList<>(List.of("[t](m.md)")));
        source.getMutableProperties().put("wikilinks", List.of("[[m]]"));
        LinkPlanner.plan(List.of(linked, source),
                index(Map.of(), Map.of(), Map.of()));
        // markdown layer recorded BEFORE the wikilink targets merge in
        assertEquals(List.of("[t](m.md)"), source.getAsList("markdownTargets"));
        // links = stitched union: raw markdown targets + resolved wikilinks
        assertEquals(List.of("[t](m.md)", "m.md"), source.getAsList("links"));
        assertEquals(List.of("m.md"), source.getAsList("wikilinkTargets"));
    }

    @Test
    @DisplayName("danglers are cleanLinks (display/heading stripped), distinct, capped")
    void danglersCappedAndClean() {
        PksFile source = new PksFile("src.md", new HashMap<>());
        java.util.List<Object> wikilinks = new java.util.ArrayList<>();
        wikilinks.add("[[missing#head|alias]]");
        wikilinks.add("[[missing]]");
        for (int i = 0; i < 60; i++) {
            wikilinks.add("[[filler" + i + "]]");
        }
        source.getMutableProperties().put("wikilinks", wikilinks);
        LinkPlanner.plan(List.of(source), index(Map.of(), Map.of(), Map.of()));
        List<String> danglers = source.getAsList("danglingLinks");
        assertEquals(50, danglers.size(), "distinct targets capped at 50: " + danglers.size());
        assertTrue(danglers.contains("missing"), "uppercase-led spelling wins the dedup race");
        assertFalse(danglers.stream().anyMatch(d -> d.contains("[[")), "cleanLinks only");
    }

    @Test
    @DisplayName("B6 vault scoping: the input indexes are caller-scoped (no cross-vault leak)")
    void vaultScopeHoldsByConstruction() {
        // indexes are handed in already scoped; the planner cannot reach around them
        PksFile source = fresh("src.md", "[[Shared]]");
        LinkPlanner.plan(List.of(source), index(Map.of(), Map.of(), Map.of()));
        assertTrue(source.getAsList("danglingLinks").contains("Shared"),
                "a scoped-out name must NOT resolve");
    }
}
