package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.feed.FeedFetcher;
import io.pskenny.pkspkms.io.feed.OpmlFileSystem;
import io.pskenny.pkspkms.io.fs.JavaFileSystem;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.repo.sqlite.SQLitePksFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static io.pskenny.pkspkms.test.FileUtil.createFile;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.QueryCompiler;
import io.pskenny.pkspkms.repo.query.QueryParser;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Syndication vaults go through the real loader: OPML outliner notes and feed
 * items are parsed like any markdown note, frontmatter lands in properties,
 * and incremental loads honor per-entry mtimes.
 */
public class SyndicationVaultLoaderTest {

    private static final Path MAIN_DIR = Paths.get("target", "test-notes", "syndication-main");
    private static final Path OPML_FILE = Paths.get("target", "test-notes", "syndication-vault.opml");
    private static final Path FEED_FILE = Paths.get("target", "test-notes", "syndication-feed.xml");
    private static final String DB_URL = "jdbc:sqlite:test_syndication.db";

    private static CompiledQuery Q(String q) {
        return new QueryCompiler().compile(new QueryParser().parse(q));
    }

    private static byte[] throwUnresolvable(String source) throws IOException {
        throw new IOException("Unresolvable feed source: " + source);
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(Path.of("test_syndication.db"));
        Files.createDirectories(MAIN_DIR);
        createFile(MAIN_DIR, "keep.md", Map.of("tags", "main"), "");
        Files.writeString(OPML_FILE, """
                <?xml version="1.0" encoding="utf-8"?>
                <opml version="2.0"><head><title>peer</title></head><body>
                  <outline text="Knowledge">
                    <outline text="Lisp" _note="garbage collection" htmlUrl="https://x/lisp" type="link"/>
                  </outline>
                  <outline text="Podcast" xmlUrl="https://fixture.test/feed.xml"/>
                </body></opml>
                """);
        Files.writeString(FEED_FILE, """
                <?xml version="1.0"?>
                <rss version="2.0"><channel><title>CoRecursive</title>
                  <item><title>Syntax and Semantics</title><link>https://corecursive.com/ep</link>
                  <pubDate>Tue, 01 Jul 2025 10:30:00 GMT</pubDate></item>
                  <item><title>Epic #1</title></item>
                </channel></rss>
                """);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(Path.of("test_syndication.db"));
        Files.deleteIfExists(OPML_FILE);
        Files.deleteIfExists(FEED_FILE);
        if (Files.exists(MAIN_DIR)) {
            try (Stream<Path> walk = Files.walk(MAIN_DIR)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    @Test
    void opmlAndFeedsIndexThroughTheRegularLoader() throws Exception {
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();

            PkmsFileSystem opmlFs = new OpmlFileSystem(OPML_FILE,
                    source -> source.equals("https://fixture.test/feed.xml")
                            ? Files.readAllBytes(FEED_FILE)
                            : throwUnresolvable(source));
            repository.loadVirtualVault(opmlFs, "scy");

            var results = repository.searchRegular(Q("filePath:@scy/*"));
            assertEquals(5, results.size(), "channel + 2 items + 2 outliner notes");

            var lisp = results.stream().filter(f -> f.getFilePath().equals("@scy/Knowledge/Lisp.md")).findFirst().orElseThrow();
            assertEquals("Lisp", lisp.getMutableProperties().get("title"), "outliner title frontmatter");
            assertEquals("https://x/lisp", lisp.getMutableProperties().get("url"), "htmlUrl mapped");

            var episode = results.stream().filter(f -> f.getFilePath().equals("@scy/Podcast/2025-07-01 Syntax and Semantics.md")).findFirst().orElseThrow();
            assertEquals("Syntax and Semantics", episode.getMutableProperties().get("title"));
            assertEquals("https://corecursive.com/ep", episode.getMutableProperties().get("url"));
            assertEquals("2025-07-01T10:30:00Z", episode.getMutableProperties().get("published"));

            // # in a filename: only a quoted filePath phrase matches (the raw
            // [[wikilink]] heading convention splits on #, so path queries it is)
            var hashNote = repository.searchRegular(Q("filePath:\"@scy/Podcast/Epic #1.md\""));
            assertEquals(1, hashNote.size(), "quoted filePath with # matches");
            assertEquals("@scy/Podcast/Epic #1.md", hashNote.get(0).getFilePath());
        }
    }

    @Test
    void unchangedOpmlDoesNotReparseOnSecondLoad() throws Exception {
        try (SQLitePksFileRepository repository = new SQLitePksFileRepository(DB_URL, null, new JavaFileSystem(MAIN_DIR.toFile()))) {
            repository.loadDirectoryIntoRepository();
            repository.loadVirtualVault(new OpmlFileSystem(OPML_FILE, FeedFetcher.loader()), "scy");

            Long firstMtime = mtimeOf("@scy/Knowledge/Lisp.md");
            repository.loadDirectoryIntoRepository();

            assertEquals(firstMtime, mtimeOf("@scy/Knowledge/Lisp.md"), "unchanged OPML -> outliner mtime stable -> no reparse");
        }
    }

    private Long mtimeOf(String filePath) throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement("SELECT file_last_modified FROM FILES WHERE file_path = ?")) {
            pstmt.setString(1, filePath);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }
}
