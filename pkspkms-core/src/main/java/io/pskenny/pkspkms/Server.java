package io.pskenny.pkspkms;

import fi.iki.elonen.NanoHTTPD;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pskenny.pkspkms.io.JsonUtil;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.repo.PksFileRepository;
import io.pskenny.pkspkms.repo.RepositoryException;
import io.pskenny.pkspkms.repo.query.CompiledQuery;
import io.pskenny.pkspkms.repo.query.QueryCompiler;
import io.pskenny.pkspkms.repo.query.QueryParseException;
import io.pskenny.pkspkms.repo.query.QueryParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

public class Server extends NanoHTTPD {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private static final DateTimeFormatter APACHE_FMT = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);

    private static final String OPENAPI_RESOURCE = "/webui/openapi.json";
    private static final ObjectMapper SPEC_MAPPER = new ObjectMapper();

    // Webjar version and the pom.xml dependency must move together; pinned by
    // ServerTest.testSwaggerUiVersionSync
    static final String SWAGGER_UI_WEBJAR_VERSION = "5.32.15";
    private static final String SWAGGER_UI_WEBJAR =
            "/META-INF/resources/webjars/swagger-ui/" + SWAGGER_UI_WEBJAR_VERSION + "/";

    private final PksFileRepository repository;
    private final String bearerToken;

    public Server(int port, PksFileRepository repository) {
        this("127.0.0.1", port, null, repository);
    }

    // Loopback-only by default: a local personal server has no business being
    // reachable from the network. token != null enables bearer auth on
    // every route except /ping and /webui.
    public Server(String bind, int port, String token, PksFileRepository repository) {
        super(bind, port);
        this.repository = repository;
        this.bearerToken = token;
    }

    private static final String BEARER_PREFIX = "Bearer ";
    private static final java.util.Set<String> ALLOWED_HOSTS =
            java.util.Set.of("localhost", "127.0.0.1", "[::1]", "[::1]:", "localhost.localdomain");

    @Override
    public Response serve(IHTTPSession session) {
        long startNs = System.nanoTime();
        String ip = session.getRemoteIpAddress();
        String ua = session.getHeaders().get("user-agent");
        String uri = session.getUri();
        var params = session.getParameters();
        Response res;

        // Host allow-list: browser DNS-rebinding requests arrive with an
        // attacker-chosen Host header — refuse anything but loopback hosts
        if (!isAllowedHost(session)) {
            res = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Unrecognized Host header");
        } else if (requiresAuth(uri) && !authorized(session)) {
            res = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                    JsonUtil.mapToJson(java.util.Map.of("status", "error", "message", "Missing or invalid Authorization header")));
        } else if (session.getMethod() == Method.OPTIONS) {
            res = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
        } else if (uri.startsWith("/cache/")) {
            if (session.getMethod() != Method.POST) {
                // State changes ride POST only — a browser <img> can't mutate the vault
                res = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Use POST");
            } else {
                res = handleCacheEndpoint(uri, params);
            }
        } else if (session.getMethod() != Method.GET) {
            res = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "");
        } else if ("/ping".equals(uri)) {
            res = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
        } else if ("/files/list".equals(uri)) {
            var query = getParam(params, "query");
            res = streamJsonResponse(query);
        } else if ("/files/list/graph".equals(uri)) {
            var query = getParam(params, "query");
            res = streamJsonResponse(query, pksFile ->
                    pksFile.filterProperties(List.of("links", "backlinks", "tags", "filePath"), List.of()));
        } else if ("/files/manifest".equals(uri)) {
            res = newFixedLengthResponse(Response.Status.OK, "application/json", JsonUtil.mapToJson(repository.manifest()));
        } else if (uri.startsWith("/cache/")) {
            res = handleCacheEndpoint(uri, params);
        } else if ("/webui".equals(uri) || "/webui/".equals(uri)) {
            res = newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "");
            res.addHeader("Location", "/webui/index.html");
        } else if ("/webui/index.html".equals(uri)) {
            InputStream htmlStream = getClass().getResourceAsStream("/webui/index.html");
            res = (htmlStream != null)
                ? newChunkedResponse(Response.Status.OK, "text/html", htmlStream)
                : newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "UI resource missing");
            addBrowserHardeningHeaders(res);
        } else if ("/webui/app.js".equals(uri)) {
            InputStream jsStream = getClass().getResourceAsStream("/webui/app.js");
            res = (jsStream != null)
                ? newChunkedResponse(Response.Status.OK, "text/javascript", jsStream)
                : newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
            addBrowserHardeningHeaders(res);
        } else if ("/webui/pk.png".equals(uri)) {
            InputStream iconStream = getClass().getResourceAsStream("/webui/pk.png");
            res = (iconStream != null)
                ? newChunkedResponse(Response.Status.OK, "image/png", iconStream)
                : newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Icon not found");
        } else if ("/openapi.json".equals(uri)) {
            res = serveOpenApi();
        } else if ("/webui/swagger".equals(uri) || "/webui/swagger/".equals(uri)) {
            res = newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "");
            res.addHeader("Location", "/webui/swagger/index.html");
        } else if (uri.startsWith("/webui/swagger/")) {
            res = serveSwaggerAsset(uri.substring("/webui/swagger/".length()));
        } else {
            res = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }

        // No CORS: the webui is same-origin, and the API is loopback-only.
        // Cross-origin access to a personal vault was an open door, not a feature.

        long ms = (System.nanoTime() - startNs) / 1_000_000;
        String ts = ZonedDateTime.now(ZoneId.systemDefault()).format(APACHE_FMT);
        int status = res.getStatus().getRequestStatus();
        // Log the path only — query strings carry note metadata (log hygiene)
        logger.info("{} - - [{}] \"{} {}\" {} - ({}ms) \"-\" \"{}\"",
            ip != null ? ip : "-",
            ts,
            session.getMethod(),
            uri,
            status,
            ms,
            ua != null ? ua : "-"
        );

        return res;
    }

    // Browser-hardening backstop: even if escaping ever regresses, the browser blocks inline
    // scripts and framing
    private static void addBrowserHardeningHeaders(Response res) {
        res.addHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src *; frame-ancestors 'none'");
        res.addHeader("X-Frame-Options", "DENY");
        res.addHeader("X-Content-Type-Options", "nosniff");
        res.addHeader("Referrer-Policy", "no-referrer");
    }

    private static boolean isAllowedHost(IHTTPSession session) {
        String host = session.getHeaders().get("host");
        if (host == null) {
            return true; // HTTP/1.0-style client — loopback tests use it too
        }
        String hostname = host.toLowerCase(Locale.ROOT);
        int colon = hostname.startsWith("[") ? hostname.indexOf(']') + 1 : hostname.indexOf(':');
        String bare = colon > 0 ? hostname.substring(0, colon) : hostname;
        return bare.equals("localhost") || bare.equals("127.0.0.1") || bare.equals("[::1]") || bare.equals("::1");
    }

    private boolean requiresAuth(String uri) {
        return bearerToken != null && !"/ping".equals(uri) && !uri.startsWith("/webui");
    }

    private boolean authorized(IHTTPSession session) {
        String auth = session.getHeaders().get("authorization");
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return false;
        }
        // Constant-time comparison: token lengths never leak through timing
        return java.security.MessageDigest.isEqual(
                (BEARER_PREFIX + bearerToken).getBytes(StandardCharsets.UTF_8),
                auth.getBytes(StandardCharsets.UTF_8));
    }

    private Response handleCacheEndpoint(String uri, Map<String, List<String>> params) {
        String cacheDirectory = getParam(params, "directory");
        if (cacheDirectory == null || cacheDirectory.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", "Missing 'directory' query parameter");
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", JsonUtil.mapToJson(error));
        }

        String pathPart = uri.substring("/cache/".length());
        int slashIdx = pathPart.indexOf('/');
        if (slashIdx == -1) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", "Invalid cache path. Expected: /cache/[address]/[location]");
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", JsonUtil.mapToJson(error));
        }

        String address = pathPart.substring(0, slashIdx);
        String location = pathPart.substring(slashIdx + 1);

        try {
            Map<String, Object> result = repository.cacheFile(address, location, cacheDirectory);
            return newFixedLengthResponse(Response.Status.OK, "application/json", JsonUtil.mapToJson(result));
        } catch (RepositoryException e) {
            logger.error("Cache endpoint error: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", "Check logs for error message");
            var status = (e.getCause() instanceof IOException)
                    ? Response.Status.NOT_FOUND
                    : Response.Status.INTERNAL_ERROR;
            return newFixedLengthResponse(status, "application/json", JsonUtil.mapToJson(error));
        }
    }

    public void loadRepo() {
        repository.loadDirectoryIntoRepository();
    }

    public void processEmbeds() {
        repository.processEmbeds();
    }

    // Spec served with the running port so Swagger UI Try-it-out hits the right origin
    private Response serveOpenApi() {
        try (InputStream in = getClass().getResourceAsStream(OPENAPI_RESOURCE)) {
            if (in == null) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "OpenAPI spec missing");
            }

            JsonNode spec = SPEC_MAPPER.readTree(in);
            ((ObjectNode) spec.get("servers").get(0)).put("url", "http://localhost:" + getListeningPort());
            return newFixedLengthResponse(Response.Status.OK, "application/json", spec.toString());
        } catch (IOException e) {
            logger.error("Failed to serve OpenAPI spec", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal error");
        }
    }

    // Serves webjar assets under a version-less /webui/swagger/ prefix
    private Response serveSwaggerAsset(String file) {
        if (file.isEmpty() || file.contains("/") || file.contains("\\") || file.contains("..")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }

        // Our docs page shadows the webjar's index.html, which defaults to petstore
        String resource = file.equals("index.html")
                ? "/webui/swagger/index.html"
                : SWAGGER_UI_WEBJAR + file;
        InputStream in = getClass().getResourceAsStream(resource);
        if (in == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }
        return newChunkedResponse(Response.Status.OK, mimeFor(file), in);
    }

    private static String mimeFor(String file) {
        if (file.endsWith(".html")) return "text/html";
        if (file.endsWith(".css")) return "text/css";
        if (file.endsWith(".js") || file.endsWith(".mjs")) return "text/javascript";
        if (file.endsWith(".map")) return "application/json";
        if (file.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    public void loadVirtualVault(PkmsFileSystem aliasFs, String alias) {
        repository.loadVirtualVault(aliasFs, alias);
    }

    private static String getParam(Map<String, List<String>> params, String key) {
        var values = params.get(key);
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.get(0);
    }

    private Response streamJsonResponse(String sqliteQuery) {
        return streamJsonResponse(sqliteQuery, null);
    }

    private Response streamJsonResponse(String sqliteQuery, Consumer<PksFile> transformer) {
        CompiledQuery compiled;
        try {
            compiled = new QueryCompiler().compile(new QueryParser().parse(sqliteQuery), repository.getPropertyTypes());
        } catch (QueryParseException e) {
            logger.warn("Bad query: {}", e.getMessage());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", JsonUtil.mapToJson(error));
        }
        PipedOutputStream pos = new PipedOutputStream();
        PipedInputStream pis;
        try {
            pis = new PipedInputStream(pos, 64 * 1024);
        } catch (IOException e) {
            logger.error("Failed to create piped stream", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal error");
        }

        Thread writer = new Thread(() -> {
            try {
                JsonUtil.writePksFilesToJson(repository, compiled, transformer, pos);
            } catch (Exception e) {
                logger.error("Error writing JSON response", e);
            } finally {
                try {
                    pos.close();
                } catch (IOException ignored) {
                }
            }
        });
        writer.setDaemon(true);
        writer.start();

        return newChunkedResponse(Response.Status.OK, "application/json", pis);
    }
}
