package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.parser.Parsers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Scans a directory, parses files, and populates the repository with file metadata,
 * resolved links, and embed references.
 */
final class FileLoader {
    private final SQLitePksFileRepository repository;
    private final Parsers parsers;

    FileLoader(SQLitePksFileRepository repository, String dbUrl) {
        this.repository = repository;
        this.parsers = new Parsers(dbUrl);
    }

    void load(String directory) throws IOException, SQLException {
        List<String> excludedDirectories = List.of();
        Path rootPath = Paths.get(directory);

        List<PksFile> pksFiles = Files.find(rootPath, Integer.MAX_VALUE, (filePath, fileAttr) -> fileAttr.isRegularFile())
                .filter(path -> {
                    Path relative = rootPath.relativize(path);
                    for (Path segment : relative) {
                        if (excludedDirectories.contains(segment.toString())) {
                            return false;
                        }
                    }
                    return true;
                })
                .map(file -> parsers.initialReadOnlyParse(file, directory))
                .filter(Objects::nonNull)
                .toList();

        insertFiles(pksFiles);
        resolveWikilinks(pksFiles);
        updatePropertiesAndLinks(pksFiles);
    }

    private void insertFiles(List<PksFile> pksFiles) throws SQLException {
        Connection initConn = repository.getConnection();
        initConn.setAutoCommit(false);
        try {
            for (PksFile file : pksFiles) {
                repository.insertFile(file.getFilePath());
            }
            initConn.commit();
        } catch (SQLException e) {
            initConn.rollback();
            throw e;
        } finally {
            initConn.setAutoCommit(true);
        }
    }

    private void resolveWikilinks(List<PksFile> pksFiles) throws SQLException {
        WikilinkFinder wikilinkFinder = new WikilinkFinder();
        Connection conn = repository.getConnection();

        for (PksFile pksFile : pksFiles) {
            List<String> resolved = pksFile.getAsList("wikilinks").stream()
                    .map(obj -> wikilinkFinder.resolveWikilink(conn, obj.toString()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (resolved.isEmpty()) {
                continue;
            }
            pksFile.addToProperty("links", resolved);
        }
    }

    private void updatePropertiesAndLinks(List<PksFile> pksFiles) throws SQLException {
        Connection conn = repository.getConnection();
        conn.setAutoCommit(false);

        try {
            for (PksFile pksFile : pksFiles) {
                // Deduplicate links before persisting so JSON properties and LINKS table stay consistent
                List<String> links = pksFile.<String>getAsList("links").stream().distinct().toList();
                if (!links.isEmpty()) {
                    pksFile.getProperties().put("links", links);
                }

                int id = repository.updatePropertiesByPath(pksFile);
                if (id == -1) continue;

                if (!links.isEmpty()) {
                    repository.setLinks(id, links, "outgoing");
                    repository.setLinks(id, links, "wikilink");
                }

                repository.addEmbedsToTable(id, pksFile, "bases", "base");
                repository.addEmbedsToTable(id, pksFile, "luabases", "luabase");
                repository.addEmbedsToTable(id, pksFile, "embeds", "embed");
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }
}
