package io.pskenny.pkspkms.repo.query;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runtime pins for the SQLite JSON1 path dialect against the shared
 * {@link QueryCompiler#jsonPath} (BUGS.md D3). Values written by Jackson
 * (the same JSON the properties column holds).
 */
public class JsonPathEscapeProbeTest {

    private static final String KEY_QUOTED = "say \"hi\"";
    private static final String KEY_SLASH = "a\\b";

    @Test
    void probe() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS T");
            stmt.execute("CREATE TABLE T (properties TEXT)");
            stmt.execute("INSERT INTO T (properties) VALUES ('{\"say \\\"hi\\\"\": 2, \"a\\\\b\": 3}')");

            for (var probe : new String[][]{
                    {KEY_QUOTED, "2"},
                    {KEY_SLASH, "3"},
            }) {
                String path = QueryCompiler.jsonPath(probe[0]);
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT json_extract(properties, '" + path.replace("'", "''") + "') FROM T")) {
                    rs.next();
                    assertEquals(probe[1], rs.getString(1),
                            "key=" + probe[0] + " path=" + path);
                }
            }
        }
    }
}
