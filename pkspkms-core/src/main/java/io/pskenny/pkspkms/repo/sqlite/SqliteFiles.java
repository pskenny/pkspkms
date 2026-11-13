package io.pskenny.pkspkms.repo.sqlite;

import io.pskenny.pkspkms.io.PksFile;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed SQL contract for the FILES row graph (loader-facing). Every statement
 * lives in the {@link FilesDao} implementation — the loader and repository
 * layers never see raw {@link java.sql.Connection} (BUGS.md P3).
 */
public interface SqliteFiles {

    Map<String, Long> modifiedTimes() throws SQLException;

    void deleteFilesWithEdges(List<String> paths) throws SQLException;

    void insertFiles(List<PksFile> files, Integer vaultAliasId) throws SQLException;

    Map<String, Integer> idsByPath() throws SQLException;

    void updateFileProperties(List<FilePropertyRow> rows) throws SQLException;

    NameIndex nameIndex(String alias) throws SQLException;

    Optional<String> resolveName(String cleanLink) throws SQLException;

    void setLinks(int fileId, List<String> targets, String linkType) throws SQLException;

    void deleteEmbedsFor(int fileId) throws SQLException;

    void addEmbedsToTable(int fileId, PksFile pksFile, String propertyName, String type) throws SQLException;

    Integer aliasId(String alias) throws SQLException;

    static String normalizeBaseText(String text) {
        return FilesDao.normalizeBaseText(text);
    }

    /**
     * One FILES update row. {@code body} is the frontmatter-stripped markdown
     * fed to the FTS5 index (null for binaries/media — unindexed).
     */
    record FilePropertyRow(Integer id, String propertiesJson, String blake3, long lastModified, String body) {}

    /** record: the three wikilink-resolution lookup maps. */
    record NameIndex(Map<String, String> byPath, Map<String, String> byNameExt, Map<String, String> byName) {}
}
