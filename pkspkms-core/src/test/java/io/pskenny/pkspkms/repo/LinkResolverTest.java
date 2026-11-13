package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.createFile;
import static org.junit.jupiter.api.Assertions.*;

// BUGS.md B6/B7/B10: vault-scoped wikilink resolution, deterministic
// duplicate-name lookup, one LINKS row per (source, target, type) with correct
// kind attribution.
public class LinkResolverTest {

    private static final Path MAIN_DIR = Paths.get("target", "test-notes", LinkResolverTest.class.getSimpleName());
    private static final Path VIRTUAL_DIR = Paths.get("target", "test-notes", "link-resolver-virtual");
    private static final String TEST_DB_PATH = "test_link_resolver.db";
    private static final String DB_URL = "jdbc:sqlite:" + TEST_DB_PATH;

    private static io.pskenny.pkspkms.repo.query.CompiledQuery Q(String q) {
        return new io.pskenny.pkspkms.repo.query.QueryCompiler().compile(
                new io.pskenny.pkspkms.repo.query.QueryParser().parse(q));
    }

    @BeforeEach
    void setUp() throws IOException {
        deleteDb();
        Files.createDirectories(MAIN_DIR);
        Files.createDirectories(VIRTUAL_DIR);
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteDb();
        for (Path dir : List.of(MAIN_DIR, VIRTUAL_DIR)) {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void deleteDb() throws IOException {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(TEST_DB_PATH + suffix));
        }
    }

    private SQLitePksFileRepository repo() {
        return new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()));
    }

    // --- B7: duplicate names ---

    @Test
    @DisplayName("Duplicate name resolves to the main-vault file deterministically")
    void duplicateNameResolvesDeterministically() throws Exception {
        createFile(MAIN_DIR, "Shared.md", Map.of(), "main content");
        createFile(VIRTUAL_DIR, "Shared.md", Map.of(), "alias content");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            for (int i = 0; i < 3; i++) {
                assertEquals("Shared.md", repository.resolveWikilink("Shared"),
                        "same answer every probe: aliasless first");
            }
        }
    }

    // --- B6: loader-time resolution stays in-vault ---

    @Test
    @DisplayName("Main-vault [[Shared]] resolves only to the main vault")
    void loaderTimeResolutionStaysInVault() throws Exception {
        createFile(MAIN_DIR, "Shared.md", Map.of(), "main content");
        createFile(VIRTUAL_DIR, "Shared.md", Map.of(), "alias content");
        createFile(MAIN_DIR, "mn.md", Map.of(), "[[Shared]]");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            var results = repository.searchRegular(Q("filePath:mn.md"));
            assertEquals(1, results.size());
            var links = results.get(0).getAsList("links");
            assertTrue(links.contains("Shared.md"), "main note resolves in-vault: " + links);
            assertFalse(links.contains("@gwern/Shared.md"), "no cross-vault leak: " + links);
        }
    }

    @Test
    @DisplayName("Virtual-vault [[Shared]] resolves only inside @gwern")
    void virtualVaultResolvesItsOwnSharedName() throws Exception {
        createFile(MAIN_DIR, "Shared.md", Map.of(), "main content");
        createFile(VIRTUAL_DIR, "Shared.md", Map.of(), "alias content");
        createFile(VIRTUAL_DIR, "vn.md", Map.of(), "[[Shared]]");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            var results = repository.searchRegular(Q("filePath:@gwern/vn.md"));
            assertEquals(1, results.size());
            var links = results.get(0).getAsList("links");
            assertTrue(links.contains("@gwern/Shared.md"), "alias note resolves in-vault: " + links);
            assertFalse(links.contains("Shared.md"), "no main-vault leak: " + links);
        }
    }

    @Test
    @DisplayName("Virtual-vault subfoldered markdown link gets the @alias prefix")
    void virtualVaultPrefixesSubfolderedMarkdownLinks() throws Exception {
        Files.createDirectories(VIRTUAL_DIR.resolve("Notes"));
        createFile(VIRTUAL_DIR, "Notes/marked.md", Map.of(), "[x](Notes/other.md)");
        createFile(VIRTUAL_DIR, "Notes/other.md", Map.of(), "target");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            var results = repository.searchRegular(Q("filePath:@gwern/Notes/marked.md"));
            assertEquals(1, results.size());
            var links = results.get(0).getAsList("links");
            assertTrue(links.contains("@gwern/Notes/other.md"),
                    "subfolder links must resolve inside the alias: " + links);
            assertFalse(links.contains("Notes/other.md"), links.toString());
        }
    }

    // frontmatter aliases: data only — wikilinks no longer resolve through
    // them (the alias resolution tier is retired)

    @Test
    @DisplayName("[[Alias]] no longer resolves; aliases stay in properties")
    void wikilinkNoLongerResolvesThroughFrontmatterAlias() throws Exception {
        createFile(MAIN_DIR, "fin.md", Map.of("aliases", List.of("Finances", "Budget")), "fin note");
        createFile(MAIN_DIR, "meta.md", Map.of(), "[[Finances]] and [[Budget]]");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();

            var results = repository.searchRegular(Q("filePath:meta.md"));
            assertEquals(1, results.size());
            var links = results.get(0).getAsList("links");
            assertFalse(links.contains("fin.md"), "alias targets are data only: " + links);
            assertTrue(results.get(0).getAsList("danglingLinks").contains("Finances"),
                    "alias-targeting wikilinks dangle now: " + results.get(0).getAsList("danglingLinks"));
            assertNull(repository.resolveWikilink("Finances"),
                    "on-demand resolver lost the alias rung with the tier");
            // lookup by the note itself still works through real names/paths
            assertEquals("fin.md", repository.resolveWikilink("fin"));
        }
    }

    @Test
    @DisplayName("Filename resolution is unaffected by note aliases")
    void aliasPrecedenceAndScope() throws Exception {
        createFile(MAIN_DIR, "Verne.md", Map.of("aliases", List.of("Shared", "Islet")), "alias holder");
        createFile(MAIN_DIR, "Shared.md", Map.of(), "real file wins");
        createFile(VIRTUAL_DIR, "Shared.md", Map.of(), "virtual twin");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new JavaFileSystem(VIRTUAL_DIR.toFile()), "gwern");

            // deterministic probe (B7): filenames always win
            assertEquals("Shared.md", repository.resolveWikilink("Shared"));
            // unclaimed target names resolve to NOTHING now (Verne's declared
            // aliases are data, not resolution candidates)
            assertNull(repository.resolveWikilink("Islet"),
                    "alias names lost the resolution rung");

            var virt = repository.searchRegular(Q("filePath:@gwern/Shared.md"));
            assertEquals(1, virt.size(), "virtual twin untouched by alias retirement");
        }
    }

    @Test
    @DisplayName("dangling wikilinks surface as the danglingLinks property")
    void danglingLinksProperty() throws Exception {
        createFile(MAIN_DIR, "T.md", Map.of(), "target");
        createFile(MAIN_DIR, "dang.md", Map.of(), "[[Finances]]\n[[T|alias text]]");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();

            var results = repository.searchRegular(Q("filePath:dang.md"));
            assertEquals(1, results.size());
            var dangling = results.get(0).getAsList("danglingLinks");
            // "T|alias text" resolves by target name after the display-alias strip
            assertTrue(dangling.contains("Finances"), "unresolved targets listed: " + dangling);
            assertFalse(dangling.contains("alias text"), "display text must not pollute: " + dangling);
            assertFalse(dangling.contains("T"), "resolved targets are not dangling: " + dangling);
        }
    }

    // --- B10: one row per (source, target, type) + kind attribution ---

    @Test
    @DisplayName("A file reached by both kinds gets exactly two rows, one per kind")
    void oneLinkRowPerPairPerType() throws Exception {
        createFile(MAIN_DIR, "T.md", Map.of(), "target");
        createFile(MAIN_DIR, "src.md", Map.of(), "[T](T.md)\n[[T]]");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("""
                         SELECT link_type, COUNT(*) FROM LINKS l
                         JOIN FILES t ON t.id = l.target_file_id
                         WHERE t.file_path = 'T.md'
                         GROUP BY link_type""");
                 ResultSet rs = pstmt.executeQuery()) {
                var counts = new java.util.LinkedHashMap<String, Integer>();
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getInt(2));
                }
                assertEquals(1, counts.getOrDefault("outgoing", 0), "outgoing rows");
                assertEquals(1, counts.getOrDefault("wikilink", 0), "wikilink rows");
                assertEquals(java.util.Set.of("outgoing", "wikilink"), counts.keySet(),
                        "no third kind");
            }
        }
    }

    @Test
    @DisplayName("Kind attribution follows source: markdown->outgoing, wikilink->wikilink")
    void typeAttributionMatchesSourceKind() throws Exception {
        createFile(MAIN_DIR, "Tx.md", Map.of(), "markdown target");
        createFile(MAIN_DIR, "Tw.md", Map.of(), "wikilink target");
        createFile(MAIN_DIR, "noteA.md", Map.of(), "[Tx](Tx.md)");
        createFile(MAIN_DIR, "noteB.md", Map.of(), "[[Tw]]");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement("""
                         SELECT t.file_path, l.link_type FROM LINKS l
                         JOIN FILES s ON s.id = l.source_file_id
                         JOIN FILES t ON t.id = l.target_file_id
                         WHERE s.file_path IN ('noteA.md', 'noteB.md') ORDER BY s.file_path""");
                 ResultSet rs = pstmt.executeQuery()) {
                var rows = new java.util.LinkedHashMap<String, String>();
                while (rs.next()) {
                    rows.put(rs.getString(1), rs.getString(2));
                }
                assertEquals("outgoing", rows.get("Tx.md"), "A's markdown target");
                assertEquals("wikilink", rows.get("Tw.md"), "B's wikilink target");
                assertEquals(2, rows.size(), "no phantom pairs");
            }
        }
    }

    @Test
    @DisplayName("Backlinks remain unioned across both kinds")
    void backlinksStayUnionedAfterFix() throws Exception {
        createFile(MAIN_DIR, "File1.md", Map.of(), "[File2](File2.md)\n[[File2]]");
        createFile(MAIN_DIR, "File2.md", Map.of(), "hub");
        createFile(MAIN_DIR, "File3.md", Map.of(), "[[File2]]");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();

            var results = repository.searchRegular(Q("filePath:File2.md"));
            assertEquals(1, results.size());
            var backlinks = results.get(0).getAsList("backlinks");
            assertTrue(backlinks.contains("File1.md"), "deduped: " + backlinks);
            assertTrue(backlinks.contains("File3.md"), backlinks.toString());
        }
    }

    // --- cache lifecycle ---

    @Test
    @DisplayName("Deleted files drop out of the resolver cache after reload")
    void resolverCacheInvalidatesAfterDelete() throws Exception {
        createFile(MAIN_DIR, "Gone.md", Map.of(), "content");

        try (SQLitePksFileRepository repository = repo()) {
            repository.loadDirectoryIntoRepository();
            assertEquals("Gone.md", repository.resolveWikilink("Gone"));

            Files.deleteIfExists(MAIN_DIR.resolve("Gone.md"));
            repository.loadDirectoryIntoRepository();

            assertNull(repository.resolveWikilink("Gone"),
                    "stale cache must not resurrect a deleted file");
        }
    }
}
