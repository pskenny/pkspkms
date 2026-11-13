package io.pskenny.pkspkms.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.fs.ObsidianIgnores;
import io.pskenny.pkspkms.io.fs.PkmsEntry;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.io.processor.Processors;
import io.pskenny.pkspkms.repo.SqliteRepository;
import io.pskenny.pkspkms.repo.sqlite.SqliteFiles;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

public final class RepositoryFileLoader {
    private final SqliteRepository repository;
    private final SqliteFiles files;
    private final PkmsFileSystem fs;
    private final Processors processors;
    private final String alias;

    public RepositoryFileLoader(SqliteRepository repository, PkmsFileSystem fs, String alias) {
        this.repository = repository;
        this.files = repository.files();
        this.fs = fs;
        this.processors = new Processors();
        this.alias = alias;
    }

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RepositoryFileLoader.class);

    // Parse/hash has no shared mutable state (per-call Yaml/Blake3/regex instances);
    // DB phases below stay serial on the single-writer connection. 2x cores covers
    // CPU-bound hashing and I/O-bound vaults alike.
    private static final int PARSE_THREADS =
            Math.min(2 * Runtime.getRuntime().availableProcessors(), 16);

    // System dirs are never indexed (.pkspkms-cache/.pkspkms-cache hold the
    // plugin's cached copies, dot-named and plain); everything else follows
    // Obsidian's Excluded files list
    private static final List<String> ALWAYS_EXCLUDED =
            List.of(".trash", ".obsidian", ".pkspkms-cache", "pkspkms-cache");

    public void load() throws IOException, SQLException {
        // 1. Walk the filesystem to collect all candidate file entries.
        // Defensive copy: fs.listFiles may return an immutable list.
        List<PkmsEntry> diskEntries = new ArrayList<>(fs.listFiles(ALWAYS_EXCLUDED));
        Predicate<String> ignores = ObsidianIgnores.from(fs);
        diskEntries.removeIf(entry -> ignores.test(entry.relativePath()));
        logger.debug("Walked {} files (excluding: {} + userIgnoreFilters)", diskEntries.size(), ALWAYS_EXCLUDED);

        // 2. Load all existing file paths and their last modified times from the database
        java.util.Map<String, Long> dbModifiedTimes = files.modifiedTimes();

        // 3. Determine new/modified files to parse, and active paths in current vault
        String prefix = (alias != null && !alias.isEmpty()) ? "@" + alias + "/" : "";
        java.util.List<PkmsEntry> filesToParse = new java.util.ArrayList<>();
        java.util.Set<String> activeDbPaths = new java.util.HashSet<>();

        for (PkmsEntry entry : diskEntries) {
            String relativeStr = entry.relativePath();
            String dbPath = prefix + relativeStr;
            activeDbPaths.add(dbPath);

            Long dbModified = dbModifiedTimes.get(dbPath);
            long diskModified = entry.lastModified();

            if (dbModified == null || dbModified != diskModified) {
                filesToParse.add(entry);
            }
        }

        // 4. Determine and remove any deleted files belonging to the current vault
        java.util.List<String> deletedDbPaths = new java.util.ArrayList<>();
        for (String dbPath : dbModifiedTimes.keySet()) {
            boolean belongsToCurrentVault = (alias != null && !alias.isEmpty())
                    ? dbPath.startsWith(prefix)
                    : !dbPath.startsWith("@");

            // Always-excluded cache rows (BUGS.md H1): the walk can never see
            // `.pkspkms-cache`/`pkspkms-cache`, so their absence from
            // activeDbPaths says nothing — delete-by-absence used to wipe
            // every `/cache`-upserted FILES row on every reload. Rows cached
            // into walked directories keep matching disk files anyway.
            if (belongsToCurrentVault && !activeDbPaths.contains(dbPath)
                    && !isInAlwaysExcludedDir(dbPath)) {
                deletedDbPaths.add(dbPath);
            }
        }

        if (!deletedDbPaths.isEmpty()) {
            files.deleteFilesWithEdges(deletedDbPaths);
            logger.info("Removed {} deleted files from database", deletedDbPaths.size());
        }

        logger.info("{} new/modified, {} unchanged, {} deleted",
                filesToParse.size(), diskEntries.size() - filesToParse.size(), deletedDbPaths.size());

        // 5. If there are no new or modified files, we are completely done!
        if (filesToParse.isEmpty()) {
            return;
        }

        // 6. Parse and hash the modified/new files in parallel
        List<PksFile> pksFiles = new ArrayList<>(filesToParse.size());
        long parseStartNs = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(PARSE_THREADS);
        try {
            List<Future<PksFile>> futures = new ArrayList<>(filesToParse.size());
            for (PkmsEntry entry : filesToParse) {
                futures.add(pool.submit(() -> processors.initialReadOnlyParse(entry, fs)));
            }
            for (Future<PksFile> future : futures) {
                try {
                    PksFile pksFile = future.get();
                    if (pksFile != null) {
                        pksFiles.add(pksFile);
                    }
                } catch (ExecutionException e) {
                    logger.error("Parse task failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while parsing", e);
                }
            }
        } finally {
            pool.shutdown();
        }
        logger.info("Parsed {} files in {}ms ({} threads)",
                pksFiles.size(), (System.nanoTime() - parseStartNs) / 1_000_000, PARSE_THREADS);

        if (alias != null && !alias.isEmpty()) {
            List<PksFile> prefixedFiles = new java.util.ArrayList<>();
            for (PksFile pksFile : pksFiles) {
                String newFilePath = prefix + pksFile.getFilePath();
                PksFile prefixedFile = new PksFile(pksFile, newFilePath);
                prefixedFiles.add(prefixedFile);
            }
            pksFiles = prefixedFiles;
        }

        insertFiles(pksFiles, alias);
        resolveWikilinks(pksFiles, alias);
        updatePropertiesAndLinks(pksFiles, alias);
    }

    private static boolean isInAlwaysExcludedDir(String path) {
        for (String segment : path.split("/")) {
            if (ALWAYS_EXCLUDED.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private void insertFiles(List<PksFile> pksFiles, String alias) throws SQLException {
        Integer vaultAliasId = null;
        if (alias != null && !alias.isEmpty()) {
            vaultAliasId = files.aliasId(alias);
        }
        files.insertFiles(pksFiles, vaultAliasId);
    }

    private void resolveWikilinks(List<PksFile> pksFiles, String alias) throws SQLException {
        // Vault-scoped lookups (BUGS.md B6): alias-null rows for the main
        // vault, the alias's rows for a virtual vault — cross-vault name
        // collisions can never leak into load-time resolution. The merge +
        // resolution + dangler logic is pure and lives in LinkPlanner.
        var nameIndex = files.nameIndex(alias);
        LinkPlanner.plan(pksFiles, nameIndex);
    }

    // Interim attribution lists: distinct, and for virtual vaults prefix any
    // link that is not already alias-led (bare names and subfolder paths both
    // belong inside @alias/ — the old contains('/') heuristic left subfoldered
    // markdown links broken (B6 family)).
    private static List<String> distinctPrefixed(List<String> source, String alias) {
        List<String> distinct = source.stream().filter(Objects::nonNull).distinct().toList();
        if (alias == null || alias.isEmpty()) {
            return distinct;
        }
        String prefix = "@" + alias + "/";
        return distinct.stream().map(link -> link.startsWith("@") ? link : prefix + link).toList();
    }

    private void updatePropertiesAndLinks(List<PksFile> pksFiles, String alias) throws SQLException {
        // B10: per-row link plan keyed by id — {outgoing targets, wikilink
        // targets}. Attribution snapshot lives in interim properties; both are
        // removed before the JSON is written.
        java.util.Map<Integer, java.util.List<java.util.List<String>>> linkPlans = new java.util.LinkedHashMap<>();
        List<SqliteFiles.FilePropertyRow> propertyRows = new ArrayList<>();

        // A. Load all path -> id mappings in one query
        java.util.Map<String, Integer> pathIdMap = files.idsByPath();

        for (PksFile pksFile : pksFiles) {
            Integer id = pathIdMap.get(pksFile.getFilePath());
            if (id == null) continue;

            List<String> wikiTargets = distinctPrefixed(
                    pksFile.<String>getAsList("wikilinkTargets"), alias);
            List<String> markdownTargets = distinctPrefixed(
                    pksFile.<String>getAsList("markdownTargets"), alias);
            // JSON `links` keeps the merged-union shape the API has always
            // exposed: wikilink targets were added during resolution.
            // Parity rule: an EMPTY list never overwrites the property —
            // frontmatter `links` may be a scalar string, not a parsed list.
            List<String> links = distinctPrefixed(
                    pksFile.<String>getAsList("links"), alias);
            if (!links.isEmpty()) {
                pksFile.getMutableProperties().put("links", links);
            }
            pksFile.getMutableProperties().remove("wikilinkTargets");
            pksFile.getMutableProperties().remove("markdownTargets");

            // `modified` rides the properties JSON, so recency queries
            // (ranges, exists, wildcards) resolve through the standard
            // property machinery like any other value. It is the file's own
            // mtime (not the index-update wall clock), ISO-8601.
            long mtime = pksFile.getLastModified();
            if (mtime > 0) {
                pksFile.getMutableProperties().put("modified",
                        java.time.Instant.ofEpochMilli(mtime).toString());
            }

            linkPlans.put(id, List.of(markdownTargets, wikiTargets));

            String propertiesJson;
            try {
                propertiesJson = repository.getJsonMapper().writeValueAsString(pksFile.getMutableProperties());
            } catch (JsonProcessingException e) {
                // Fail fast: persisting "{}" would silently falsify this file's properties
                throw new SQLException("Failed to serialize properties for: " + pksFile.getFilePath(), e);
            }

            propertyRows.add(new SqliteFiles.FilePropertyRow(
                    id, propertiesJson, pksFile.getHash(), pksFile.getLastModified(), pksFile.getBody()));
        }

        files.updateFileProperties(propertyRows);

        // B. Batch set links and embeds
        for (PksFile pksFile : pksFiles) {
            Integer id = pathIdMap.get(pksFile.getFilePath());
            if (id == null) continue;

            // setLinks deletes the type's rows before inserting, so both
            // kinds are refreshed unconditionally — one row per
            // (source, target, type), never doubled (BUGS.md B10)
            java.util.List<java.util.List<String>> plan = linkPlans.get(id);
            if (plan != null) {
                files.setLinks(id, plan.get(0), "outgoing");
                files.setLinks(id, plan.get(1), "wikilink");
            }

            files.deleteEmbedsFor(id);
            files.addEmbedsToTable(id, pksFile, "bases", "base");
            files.addEmbedsToTable(id, pksFile, "luabases", "luabase");
            files.addEmbedsToTable(id, pksFile, "embeds", "embed");
        }
    }
}