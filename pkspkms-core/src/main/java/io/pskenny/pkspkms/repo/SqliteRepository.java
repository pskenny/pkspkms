package io.pskenny.pkspkms.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.repo.sqlite.SqliteFiles;

/**
 * Repository-level contract. The raw connection stays private to the
 * {@link FilesDao} — this interface and its implementers never hand one out
 * (BUGS.md P3: the old `getConnection()` leak). Test suites that need direct
 * SQL use `SQLitePksFileRepository`'s concrete `@Deprecated` connection
 * accessor, not this interface.
 */
public interface SqliteRepository extends PksFileRepository {
    ObjectMapper getJsonMapper();

    /** Typed SQL surface for the FILES row graph. */
    SqliteFiles files();
}
