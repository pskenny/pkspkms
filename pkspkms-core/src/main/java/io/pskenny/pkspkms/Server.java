package io.pskenny.pkspkms;

import fi.iki.elonen.NanoHTTPD;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pskenny.pkspkms.io.JsonUtil;
import io.pskenny.pkspkms.io.PksFile;
import io.pskenny.pkspkms.io.fs.PkmsFileSystem;
import io.pskenny.pkspkms.mcp.McpDispatcher;
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
    private final java.util.Set<String> corsOrigins;
    private final McpDispatcher mcpDispatcher;
    // MCP handlers touch the single shared SQLite connection; serialize them so
    // compound tool steps (search then read) never interleave mid-request
    private final Object mcpLock = new Object();

    public Server(int port, PksFileRepository repository) {
        this("127.0.0.1", port, null, repository);
    }

    // Loopback-only by default: a local personal server has no business being
    // reachable from the network. token != null enables bearer auth on
    // every route except /ping and /webui. corsOrigins empty = default closed.
    public Server(String bind, int port, String token, PksFileRepository repository) {
        this(bind, port, token, java.util.Set.of(), repository);
    }

    public Server(String bind, int port, String token, java.util.Set<String> corsOrigins,
                  PksFileRepository repository) {
        super(bind, port);
        this.repository = repository;
        this.bearerToken = token;
        this.corsOrigins = java.util.Set.copyOf(corsOrigins);
        this.mcpDispatcher = new McpDispatcher(repository);
        this.routes = buildRoutes();
    }

    private static final String BEARER_PREFIX = "Bearer ";

    // Route dispatch table: prefix/exact matches encode the old if/else
    // chain verbatim — the security gate order (host → preflight → auth)
    // stays OUTSIDE this table, applied to every route uniformly
    private interface RouteHandler {
        Response handle(IHTTPSession session, String uri, Map<String, List<String>> params);
    }

    private final List<Route> routes;

    private record Route(Matcher matcher, boolean getOnly, RouteHandler handler) {

        private interface Matcher {
            boolean matches(String uri);
        }

        static Matcher exact(String path) {
            return uri -> uri.equals(path);
        }

        static Matcher any(String... paths) {
            return uri -> java.util.Arrays.asList(paths).contains(uri);
        }

        static Matcher prefix(String path) {
            return uri -> uri.startsWith(path);
        }
    }

    // POST-only handlers carry their own 405 semantics: state-changing
    // endpoints must never respond to the GET/HEAD rules browsers issue by
    // themselves (a browser <img> can't mutate the vault)
    private static Response postOnly(String message, boolean allowHeader) {
        Response notAllowed = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", message);
        if (allowHeader) {
            notAllowed.addHeader("Allow", "POST");
        }
        return notAllowed;
    }

    // Case-insensitive marker check: NanoHTTPD preserves the client's header
    // casing in the map, so route by lowercase name.
    private static final String CACHE_GATE_HEADER = "x-pkspkms-cache-request";

    private static boolean gateMarker(IHTTPSession session) {
        return session.getHeaders().entrySet().stream()
                .anyMatch(e -> CACHE_GATE_HEADER.equalsIgnoreCase(e.getKey())
                        && "1".equals(String.valueOf(e.getValue()).strip()));
    }

    private static Response gated(String message) {
        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json",
                JsonUtil.mapToJson(java.util.Map.of("status", "error", "message", message)));
    }

    private List<Route> buildRoutes() {
        return List.of(
                // /cache/<alias>/sub — POST only
                new Route(Route.prefix("/cache/"), false, (s, uri, params) -> {
                    if (s.getMethod() != Method.POST) {
                        return postOnly("Use POST", false);
                    }
                    // Anti-drive-by gate (BUGS.md H6): HTML form POSTs cannot
                    // set custom headers, and browser JS can only add one
                    // after a preflight (answered for allow-listed origins on
                    // OPTIONS). The marker is a constant, not a secret —
                    // non-browser clients just send it.
                    if (!gateMarker(s)) {
                        return gated("POST /cache requires the X-Pkspkms-Cache-Request: 1 header");
                    }
                    return handleCacheEndpoint(uri, params);
                }),
                new Route(Route.any("/mcp", "/mcp/"), false, (s, uri, params) -> {
                    // MCP streamable HTTP: POST-only, no GET stream, no sessions
                    // (draft 2026-07-28 shape). Requests already passed bearer
                    // auth + the Host allow-list — the DNS-rebinding defense
                    // MCP demands
                    if (s.getMethod() != Method.POST) {
                        return postOnly("Use POST (MCP streamable HTTP)", true);
                    }
                    return handleMcp(s);
                }),
                new Route(Route.exact("/ping"), true, (s, uri, p) ->
                        newFixedLengthResponse(Response.Status.OK, "text/plain", "")),
                new Route(Route.exact("/files/list"), true, (s, uri, params) ->
                        streamJsonResponse(getParam(params, "query"))),
                new Route(Route.exact("/files/list/graph"), true, (s, uri, params) ->
                        streamJsonResponse(getParam(params, "query"), file ->
                                file.filterProperties(List.of("links", "backlinks", "tags", "filePath"), List.of()))),
                new Route(Route.exact("/files/manifest"), true, (s, uri, p) ->
                        newFixedLengthResponse(Response.Status.OK, "application/json", JsonUtil.mapToJson(repository.manifest()))),
                new Route(Route.any("/webui", "/webui/"), true, (s, uri, p) -> {
                    Response redirect = newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "");
                    redirect.addHeader("Location", "/webui/index.html");
                    return redirect;
                }),
                new Route(Route.exact("/webui/index.html"), true, (s, uri, p) -> {
                    InputStream htmlStream = getClass().getResourceAsStream("/webui/index.html");
                    Response res = (htmlStream != null)
                            ? newChunkedResponse(Response.Status.OK, "text/html", htmlStream)
                            : newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "UI resource missing");
                    addBrowserHardeningHeaders(res);
                    return res;
                }),
                new Route(Route.exact("/webui/app.js"), true, (s, uri, p) -> {
                    InputStream jsStream = getClass().getResourceAsStream("/webui/app.js");
                    Response res = (jsStream != null)
                            ? newChunkedResponse(Response.Status.OK, "text/javascript", jsStream)
                            : newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
                    addBrowserHardeningHeaders(res);
                    return res;
                }),
                new Route(Route.exact("/webui/pk.png"), true, (s, uri, p) -> {
                    InputStream iconStream = getClass().getResourceAsStream("/webui/pk.png");
                    return (iconStream != null)
                            ? newChunkedResponse(Response.Status.OK, "image/png", iconStream)
                            : newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Icon not found");
                }),
                new Route(Route.exact("/openapi.json"), true, (s, uri, p) -> serveOpenApi()),
                new Route(Route.any("/webui/swagger", "/webui/swagger/"), true, (s, uri, p) -> {
                    Response redirect = newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "");
                    redirect.addHeader("Location", "/webui/swagger/index.html");
                    return redirect;
                }),
                new Route(Route.prefix("/webui/swagger/"), true, (s, uri, p) ->
                        serveSwaggerAsset(uri.substring("/webui/swagger/".length()))));
    }

    private Response route(IHTTPSession session, String uri, Map<String, List<String>> params) {
        for (Route entry : routes) {
            if (!entry.matcher().matches(uri)) {
                continue;
            }
            if (entry.getOnly() && session.getMethod() != Method.GET) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "");
            }
            return entry.handler().handle(session, uri, params);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
    }

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
        // !! Preflight CORS handling uses the same pre-gate options flow below
        if (!isAllowedHost(session)) {
            res = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Unrecognized Host header");
        } else if (session.getMethod() == Method.OPTIONS) {
            // CORS preflight (source: fetch spec). Preflights carry no custom
            // headers — the Authorization header is absent — so this branch
            // must run before the auth gate or browsers brick mid-flow.
            // Unauthenticated but data-free: the actual request still passes
            // the same auth barrier.
            res = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
            addCorsHeaders(res, session);
        } else if (requiresAuth(uri) && !authorized(session)) {
            res = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                    JsonUtil.mapToJson(java.util.Map.of("status", "error", "message", "Missing or invalid Authorization header")));
        } else {
            res = route(session, uri, params);
        }

        // CORS: default closed (empty allow-list = no headers). An allow-listed
        // Origin grants that site's pages API access — subject to the auth gate
        // when --enable-auth is on. Preflight handled in its own branch above.
        addCorsHeaders(res, session);

        long ms = (System.nanoTime() - startNs) / 1_000_000;
        String ts = ZonedDateTime.now(ZoneId.systemDefault()).format(APACHE_FMT);
        int status = res.getStatus().getRequestStatus();
        // Log the path only — query strings carry note metadata (log hygiene);
        // both values scrub CR/LF so a request cannot forge log lines (B24)
        logger.info("{} - - [{}] \"{} {}\" {} - ({}ms) \"-\" \"{}\"",
            io.pskenny.pkspkms.io.LogUtil.oneLine(ip != null ? ip : "-"),
            ts,
            session.getMethod(),
            io.pskenny.pkspkms.io.LogUtil.oneLine(uri),
            status,
            ms,
            io.pskenny.pkspkms.io.LogUtil.oneLine(ua != null ? ua : "-")
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

    // Lifecycle: NanoHTTPD stop, then the repository's clean close. Closing
    // the last connection is SQLite's trigger to checkpoint and delete the
    // -wal/-shm sidecars — without this the trio lingers after every run.
    public void stop() {
        super.stop();
        try {
            repository.close();
        } catch (RuntimeException e) {
            logger.warn("Repository close failed on shutdown", e);
        }
    }

    // CORS (browser extension / remote-origin consumption): default closed.
    // An allow-listed Origin grants that site's pages API access — subject to
    // the auth gate when --enable-auth is on. The origin is reflected
    // (echoed) rather than trusted verbatim; CR/LF control stops header
    // injection at the echo site.
    private void addCorsHeaders(Response res, IHTTPSession session) {
        if (corsOrigins.isEmpty()) {
            return;
        }
        String origin = session.getHeaders().get("origin");
        if (origin == null || origin.isEmpty()) {
            return; // same-origin request — no CORS headers needed
        }
        boolean wildcard = corsOrigins.contains("*");
        if (!wildcard && !corsAllowed(origin)) {
            return;
        }
        res.addHeader("Access-Control-Allow-Origin", wildcard ? "*" : origin);
        if (!wildcard) {
            res.addHeader("Vary", "Origin");
        }
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Pkspkms-Cache-Request");
        res.addHeader("Access-Control-Max-Age", "86400");
    }

    private boolean corsAllowed(String origin) {
        if (origin.indexOf('\r') >= 0 || origin.indexOf('\n') >= 0 || origin.indexOf('\0') >= 0) {
            return false; // header-injection hard stop at the echo site
        }
        for (String allowed : corsOrigins) {
            if ("*".equals(allowed) || allowed.equalsIgnoreCase(origin)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedHost(IHTTPSession session) {
        String host = session.getHeaders().get("host");        if (host == null) {
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
            logger.error("Cache endpoint error: {}", io.pskenny.pkspkms.io.LogUtil.oneLine(e.getMessage()), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", "Check logs for error message");
            var status = (e.getCause() instanceof IOException)
                    ? Response.Status.NOT_FOUND
                    : Response.Status.INTERNAL_ERROR;
            return newFixedLengthResponse(status, "application/json", JsonUtil.mapToJson(error));
        }
    }

    // POST-only MCP endpoint: one JSON-RPC request per POST, answered with a
    // single JSON object (spec allows skipping SSE for stateless servers).
    // Notifications answer 202 Accepted with no body. Older clients' GET,
    // DELETE and Mcp-Session-Id traffic is rejected/ignored per spec.
    private Response handleMcp(IHTTPSession session) {
        String raw;
        try {
            Map<String, String> body = new HashMap<>();
            session.parseBody(body);
            raw = body.get("postData");
        } catch (Exception e) {
            logger.warn("Failed to read MCP request body: {}", e.getMessage());
            raw = null;
        }

        String responseJson;
        synchronized (mcpLock) {
            responseJson = mcpDispatcher.handle(raw);
        }
        if (responseJson == null) {
            return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "");
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", responseJson);
    }

    public void loadRepo() {
        // No embed rendering here: Application renders embeds once, after every
        // vault has mounted (server.processEmbeds) — otherwise the main vault's
        // embeds would render twice
        repository.loadDirectoryIntoRepository(io.pskenny.pkspkms.repo.EmbedMode.DEFERRED);
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
            logger.warn("Bad query: {}", io.pskenny.pkspkms.io.LogUtil.oneLine(e.getMessage()));
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
                io.pskenny.pkspkms.repo.PksJsonWriter.writePksFilesToJson(repository, compiled, transformer, pos);
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
