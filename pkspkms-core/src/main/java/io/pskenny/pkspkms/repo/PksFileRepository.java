package io.pskenny.pkspkms.repo;

import io.pskenny.pkspkms.io.PksFile;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Abstract repository for PksFile persistence and querying.
 * Implementations may use SQLite, an in-memory store, or a platform-specific database.
 */
public interface PksFileRepository extends AutoCloseable {

    void loadDirectoryIntoRepository(String directory) throws IOException, SQLException;

    List<PksFile> searchRegular(String query);

    List<PksFile> searchWithLuaFilter(String luaScript) throws SQLException;

    PksFile getFileWithBacklinks(String filePath);

    String buildQueryWithBacklinks(String whereClause);

    /**
     * Resolves a wikilink string to a full file path.
     *
     * @param wikilink raw wikilink text (e.g. "MyNote")
     * @return resolved file path or null if not found
     */
    String resolveWikilink(String wikilink);

    @Override
    void close() throws SQLException;
}
