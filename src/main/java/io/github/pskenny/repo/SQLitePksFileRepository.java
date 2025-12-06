package io.github.pskenny.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.pskenny.io.PksFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class SQLitePksFileRepository implements PksFileRepository {

    private static final Logger logger = LoggerFactory.getLogger(SQLitePksFileRepository.class);

    private final String jdbcUrl;
    private final ObjectMapper objectMapper;

    public SQLitePksFileRepository(String databasePath) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule()); // For JSR310 date types
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Make dates readable
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    @Override
    public void init() {
        /*
        Notes:
          Todo:
            Indexes on:
            - id (md5)
            - file paths
            - tags
            - links/backlinks
            Other
            - MD5 as id
            - backlinks are created on file insert

          \What's important:
             - file paths
             - links
             - tags
             - backlinks

         Tables:
            files
            - id (md5 of path)
            - path (text)
            - links (extracted from properties?)
            - tags (extracted from properties?)
            - properties_json
                 */
        String createFilesTableSQL = """
            CREATE TABLE files (
                id              INTEGER PRIMARY KEY AUTOINCREMENT, -- change to md5 of path
                path            TEXT    NOT NULL
                                        UNIQUE,
                created_at      TEXT    DEFAULT CURRENT_TIMESTAMP,
                updated_at      TEXT    DEFAULT CURRENT_TIMESTAMP,
                properties_json JSON
            );
            """;

        String createFilesFileNameIndexSQL = "CREATE INDEX IF NOT EXISTS idx_files_file_name ON files (path);";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createFilesTableSQL);
            stmt.execute(createFilesFileNameIndexSQL);
            logger.info("Database schema initialized successfully.");
        } catch (SQLException e) {
            logger.error("Error initializing database schema", e);
        }
    }

    @Override
    public PksFile save(PksFile pksFile) throws SQLException {
        String sql = "INSERT INTO files (path, properties_json) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            String propertiesJsonString = objectMapper.writeValueAsString(pksFile.getProperties());
            pstmt.setString(1, pksFile.getFilePath());
            pstmt.setString(2, propertiesJsonString);

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows == 0) {
                throw new SQLException("Creating PksFile failed, no rows affected.");
            }

            try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    long id = generatedKeys.getLong(1);
                    logger.info("Saved PksFile: {} with ID: {}", pksFile.getFilePath(), id);
                    // Since PksFile record doesn't have an ID, we can't return it directly in the record.
                    // If you modify PksFile to include a Long id, then you can return new PksFile(id, ...)
                    // For now, this just saves it. The calling code should understand the ID is generated.
                    return pksFile; // Or consider throwing a custom exception if ID is crucial for caller
                } else {
                    throw new SQLException("Creating PksFile failed, no ID obtained.");
                }
            }
        } catch (JsonProcessingException e) {
            logger.error("Error converting properties to JSON for file: {}", pksFile.getFilePath(), e);
            throw new SQLException("Failed to serialize properties to JSON.", e);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                logger.warn("Attempted to save duplicate file name: {}", pksFile.getFilePath());
//                throw new SQLException("File with this name already exists: " + pksFile.getFilePath(), e);
                return null;
            } else {
                logger.error("Error saving PksFile: {}", pksFile.getFilePath(), e);
                throw e;
            }
        }
    }

    @Override
    public Optional<PksFile> findByFileName(String fileName) throws SQLException {
        String sql = "SELECT id, path, properties_json FROM files WHERE path = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fileName);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapResultSetToPksFile(rs));
            }
        } catch (SQLException e) {
            logger.error("Error finding PksFile by file name: {}", fileName, e);
            throw e;
        }
        return Optional.empty();
    }

    @Override
    public Optional<PksFile> findById(long id) throws SQLException {
        String sql = "SELECT id, path, properties_json FROM files WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapResultSetToPksFile(rs));
            }
        } catch (SQLException e) {
            logger.error("Error finding PksFile by ID: {}", id, e);
            throw e;
        }
        return Optional.empty();
    }

    @Override
    public List<PksFile> findByTag(String tag) throws SQLException {
        List<PksFile> files = new ArrayList<>();
        String sql =
            """
            SELECT * FROM files
            WHERE EXISTS (
              SELECT * FROM json_each(files.properties_json, '$.tags')
              WHERE json_each.value = ?
            );
            """;
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tag);
            ResultSet rs = pstmt.executeQuery();
            while(rs.next()) {
                files.add(mapResultSetToPksFile(rs));
            }
        } catch (SQLException e) {
            logger.error("Error finding PksFile by ID: {}", tag, e);
            throw e;
        }
        return files;
    }

    @Override
    public List<PksFile> findByProperties(Map<String, Object> includes, Map<String, Object> excludes) throws SQLException {
        // if it's not one of the reserved properties it's in the json
        System.err.println("findByProperties not implemented...");
        return List.of();
    }

    @Override
    public List<PksFile> findAll() {
        List<PksFile> pksFiles = new ArrayList<>();
        String sql = "SELECT id, path, properties_json FROM files";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                pksFiles.add(mapResultSetToPksFile(rs));
            }
        } catch (SQLException e) {
            logger.error("Error finding all PksFiles", e);
            return pksFiles;
        }
        return pksFiles;
    }

    @Override
    public PksFile update(PksFile pksFile) throws SQLException {
        // This method assumes you can uniquely identify the file to update.
        // If PksFile doesn't have an ID, you'd update by filePath.
        // If it does have an ID (recommended), you'd update by ID.
        // For now, I'll update by path as it's the unique key in your PksFile record.
        String sql = "UPDATE files SET properties_json = ?, updated_at = CURRENT_TIMESTAMP WHERE path = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            String propertiesJsonString = objectMapper.writeValueAsString(pksFile.getProperties());
            pstmt.setString(1, propertiesJsonString);
            pstmt.setString(2, pksFile.getFilePath()); // Use filePath to identify the record

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows == 0) {
                throw new SQLException("Updating PksFile failed, no rows affected. File not found: " + pksFile.getFilePath());
            }
            logger.info("Updated PksFile: {}", pksFile.getFilePath());
            return pksFile;
        } catch (JsonProcessingException e) {
            logger.error("Error converting properties to JSON for file: {}", pksFile.getFilePath(), e);
            throw new SQLException("Failed to serialize properties to JSON.", e);
        } catch (SQLException e) {
            logger.error("Error updating PksFile: {}", pksFile.getFilePath(), e);
            throw e;
        }
    }

    @Override
    public List<PksFile> findBacklinks(long fileId) throws SQLException {
        List<PksFile> backlinks = new ArrayList<>();
        String sql = """
            SELECT f.id, f.path, f.properties_json
            FROM files f
            JOIN file_links fl ON f.id = fl.from_file_id
            WHERE fl.to_file_id = ?;
            """;
        System.out.println("Find backlinks not implemented!!!");
//        try (Connection conn = getConnection();
//             PreparedStatement pstmt = conn.prepareStatement(sql)) {
//            pstmt.setLong(1, fileId);
//            ResultSet rs = pstmt.executeQuery();
//            while (rs.next()) {
//                backlinks.add(mapResultSetToPksFile(rs));
//            }
//        } catch (SQLException e) {
//            logger.error("Error finding backlinks for file ID: {}", fileId, e);
//            throw e;
//        }
        return backlinks;
    }

    @Override
    public List<PksFile> findOutgoingLinks(long fileId) throws SQLException {
        List<PksFile> outgoingLinks = new ArrayList<>();
        String sql = """
            SELECT f.id, f.path, f.properties_json
            FROM files f
            JOIN file_links fl ON f.id = fl.to_file_id
            WHERE fl.from_file_id = ?;
            """;
        System.out.println("Find outgoing links not implemented!!!");
//        try (Connection conn = getConnection();
//             PreparedStatement pstmt = conn.prepareStatement(sql)) {
//            pstmt.setLong(1, fileId);
//            ResultSet rs = pstmt.executeQuery();
//            while (rs.next()) {
//                outgoingLinks.add(mapResultSetToPksFile(rs));
//            }
//        } catch (SQLException e) {
//            logger.error("Error finding outgoing links for file ID: {}", fileId, e);
//            throw e;
//        }
        return outgoingLinks;
    }

    // --- Internal Mapping Helper ---
    private PksFile mapResultSetToPksFile(ResultSet rs) throws SQLException {
        String path = rs.getString("path");
        String propertiesJson = rs.getString("properties_json");
        Map<String, Object> properties = new HashMap<>();
        if (propertiesJson != null && !propertiesJson.isEmpty()) {
            try {
                properties = objectMapper.readValue(propertiesJson, Map.class);
            } catch (JsonProcessingException e) {
                logger.error("Error deserializing properties JSON for file: {}", path, e);
                // Depending on strictness, you might throw here or return empty properties
            }
        }
        return new PksFile(path, properties);
    }
}

/*
    public static void main(String[] args) throws IOException {
        String dbPath = "pk.db";
        System.out.println("Making repo...");
        PksFileRepository repository = new SQLitePksFileRepository(dbPath);
        System.out.println("Getting all pksfiles...");
        List<PksFile> files = FileThings.listPksFiles("/path/", true);
        System.out.println("Getting Wikilinks...");
        Map<String, Set<String>> wikilinksToFilePaths = WikilinkResolver.getWikilinkToFilePaths(files);
        System.out.println("Resolving Wikilinks...");
        files = files.stream()
                .map(pksFile -> Server.resolveWikilinks(pksFile, wikilinksToFilePaths, "/path/"))
                .collect(Collectors.toList());
        System.out.println("Adding backlinks...");
        addBacklinks(files, files);

        try {
            System.out.println("Init schema...");
            repository.init();


            System.out.println("Saving all files...");
            for(PksFile file : files) {
                repository.save(file);
            }

            // --- 2. Retrieve Files by Name ---
//            Optional<PksFile> retrievedFile1Opt = repository.findByFileName("/path/test/data/example/Example.md");
            Optional<PksFile> retrievedFile1Opt = repository.findByFileName("/path/Home.md");
            retrievedFile1Opt.ifPresent(f -> {
                System.out.println("Retrieved File: " + f.getFilePath());
                System.out.println("Properties: " + f.getProperties());
            });

            // --- 3. Get File IDs for Linking (Crucial step if PksFile has no ID field) ---
            long file1Id = -1;
            long file2Id = -1;
//            long file3Id = -1;

            Optional<PksFile> dbFile1 = repository.findByFileName("/path/Home.md");
            if (dbFile1.isPresent()) {
                // This assumes PksFile has an ID. If not, you'd need to fetch ID from DB:
                // SELECT id FROM files WHERE file_name = '/path/Notes/YTCH.md'
                // For this example, let's just assume we get the ID by a helper method or direct query for simplicity
//                file1Id = getIdForFileName(repository, "/path/example/Example.md");
                file1Id = getIdForFileName(repository, "/path/Notes/Shopping.md");
                file2Id = getIdForFileName(repository, "/path/Notes/TODO.md");
//                file3Id = getIdForFileName(repository, "/path/example/README.md");

                System.out.println("\nFile IDs for linking:");
                System.out.println("Shopping.md ID: " + file1Id);
                System.out.println("TODO.md ID: " + file2Id);
//                System.out.println("README.md ID: " + file3Id);

                System.out.println("\nGetting by tag...");
                var ff = repository.findByTag("Meta");
                ff.forEach(pksFile -> System.out.println(pksFile.getFilePath()));

                // --- 5. Retrieve Backlinks ---
//                System.out.println("\nBacklinks for Example.md:");
//                List<PksFile> backlinksToYTCH = repository.findBacklinks(file1Id);
//                backlinksToYTCH.forEach(f -> System.out.println("- " + f.getFilePath()));

                // --- 6. Retrieve Outgoing Links ---
//                System.out.println("\nOutgoing links from Home.md:");
//                List<PksFile> outgoingFromHome = repository.findOutgoingLinks(file2Id);
//                outgoingFromHome.forEach(f -> System.out.println("- " + f.getFilePath()));

//                System.out.println("\nOutgoing links from PKSPKMS.md:");
//                outgoingFromHome = repository.findOutgoingLinks(file2Id);
//                outgoingFromHome.forEach(f -> System.out.println("- " + f.getFilePath())); // Programming.md should be gone
            }
        } catch (SQLException e) {
            System.err.println("Database operation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Helper method to get ID from file name for demonstration purposes
    // In a real app, if PksFile had an ID, you'd save it and then use the returned ID
    private static long getIdForFileName(PksFileRepository repo, String filePath) throws SQLException {
        Optional<PksFile> pksFileOpt = repo.findByFileName(filePath);
        if (pksFileOpt.isPresent()) {
            // This is a placeholder. If PksFile doesn't have an ID, you'd need
            // a custom query like SELECT id FROM files WHERE file_name = ?
            // Let's implement a quick helper for this internal demo purpose:
            String sql = "SELECT id FROM files WHERE path = ?";
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:pks.db");
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, filePath);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }
        return -1; // Not found
    }
 */