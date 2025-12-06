package io.github.pskenny.repo;

import io.github.pskenny.io.PksFile;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Repositories are only for reading and updating.
public interface PksFileRepository {

    void init();

    PksFile save(PksFile pksFile) throws SQLException;

    Optional<PksFile> findByFileName(String fileName) throws SQLException;
    List<PksFile> findByTag(String tag) throws SQLException;
    List<PksFile> findByProperties(Map<String, Object> includes, Map<String, Object> excludes) throws SQLException;
    Optional<PksFile> findById(long id) throws SQLException;
    List<PksFile> findAll();
    List<PksFile> findBacklinks(long fileId) throws SQLException;
    List<PksFile> findOutgoingLinks(long fileId) throws SQLException;
    PksFile update(PksFile pksFile) throws SQLException;
}