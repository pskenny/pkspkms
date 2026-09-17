package io.pskenny.pkspkms.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pskenny.pkspkms.io.PksFile;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface SqliteRepository extends PksFileRepository {
    Connection getConnection();
    ObjectMapper getJsonMapper();
    void setLinks(int sourceFileId, List<String> targetFilePaths, String linkType) throws SQLException;
    void addEmbedsToTable(int fileId, PksFile pksFile, String key, String type) throws SQLException;
}
