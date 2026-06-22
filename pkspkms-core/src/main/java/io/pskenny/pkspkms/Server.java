package io.pskenny.pkspkms;

import fi.iki.elonen.NanoHTTPD;
import io.pskenny.pkspkms.io.JsonUtil;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.repo.sqlite.SqlQueryParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Server extends NanoHTTPD {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private static final DateTimeFormatter APACHE_FMT = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);

    private final PksFileRepository repository;
    private final String directory;
    private final SqlQueryParser parser = new SqlQueryParser();

    public Server(int port, PksFileRepository repository, String directory) {
        super(port);
        this.repository = repository;
        this.directory = directory;
    }

    @Override
    public Response serve(IHTTPSession session) {
        long startNs = System.nanoTime();
        String ip = session.getRemoteIpAddress();
        String ua = session.getHeaders().get("user-agent");
        String uri = session.getUri();
        Response res;
        var params = session.getParameters();

        if (session.getMethod() == Method.OPTIONS) {
            res = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
        } else if (session.getMethod() != Method.GET) {
            res = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "");
        } else if ("/ping".equals(uri)) {
            res = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
        } else if ("/files/list".equals(uri)) {
            var query = getParam(params, "query");
            var sqliteQuery = parser.parseToFullJoinQuery(query);
            var files = repository.searchRegular(sqliteQuery);
            res = newFixedLengthResponse(Response.Status.OK, "application/json", JsonUtil.pksFilesToJson(files));
        } else if ("/files/search".equals(uri)) {
            var query = getParam(params, "query");
            var sqliteQuery = parser.parseToFullJoinQuery(query);
            var files = repository.searchRegular(sqliteQuery);
            res = newFixedLengthResponse(Response.Status.OK, "application/json", JsonUtil.pksFilesToJson(files));
        } else if ("/files/list/graph".equals(uri)) {
            var query = getParam(params, "query");
            var sqliteQuery = parser.parseToFullJoinQuery(query);
            var files = repository.searchRegular(sqliteQuery);
            files.forEach(f -> f.filterProperties(List.of("links", "backlinks", "tags", "filePath"), List.of()));
            res = newFixedLengthResponse(Response.Status.OK, "application/json", JsonUtil.pksFilesToJson(files));
        } else if ("/config".equals(uri)) {
            var json = "{\"vaultPath\":\"" + directory + "\"}";
            res = newFixedLengthResponse(Response.Status.OK, "application/json", json);
        } else if ("/webui".equals(uri) || "/webui/".equals(uri)) {
            res = newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "");
            res.addHeader("Location", "/webui/index.html");
        } else if ("/webui/index.html".equals(uri)) {
            InputStream htmlStream = getClass().getResourceAsStream("/webui/index.html");
            res = (htmlStream != null)
                ? newChunkedResponse(Response.Status.OK, "text/html", htmlStream)
                : newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "UI resource missing");
        } else if ("/webui/brain-icon.png".equals(uri)) {
            InputStream iconStream = getClass().getResourceAsStream("/webui/brain-icon.png");
            res = (iconStream != null)
                ? newChunkedResponse(Response.Status.OK, "image/png", iconStream)
                : newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Icon not found");
        } else {
            res = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }

        res.addHeader("Access-Control-Allow-Origin", "*");
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");

        long ms = (System.nanoTime() - startNs) / 1_000_000;
        String ts = ZonedDateTime.now(ZoneId.systemDefault()).format(APACHE_FMT);
        int status = res.getStatus().getRequestStatus();
        String queryString = session.getQueryParameterString();
        String fullUri = uri + (queryString != null && !queryString.isEmpty() ? "?" + queryString : "");
        logger.info("{} - - [{}] \"{} {}\" {} - ({}ms) \"-\" \"{}\"",
            ip != null ? ip : "-", ts, session.getMethod(), fullUri,
            status, ms, ua != null ? ua : "-");

        return res;
    }

    public void loadRepo(String dir) throws SQLException, IOException {
        repository.loadDirectoryIntoRepository(dir);
    }

    private static String getParam(Map<String, List<String>> params, String key) {
        var values = params.get(key);
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.get(0);
    }
}
