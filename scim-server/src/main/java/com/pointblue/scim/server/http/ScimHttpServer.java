package com.pointblue.scim.server.http;

import com.pointblue.scim.ScimException;
import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonException;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.CoreSchemas;
import com.pointblue.scim.schema.ResourceType;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.server.config.ServerConfig;
import com.pointblue.scim.server.endpoints.DiscoveryEndpoints;
import com.pointblue.scim.server.endpoints.ResourceEndpoint;
import com.pointblue.scim.server.endpoints.Response;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.ScimBackend;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDK HttpServer front end: TLS-less dev listener or HTTPS (configured
 * keystore), virtual-thread-per-request, bearer auth, routing, error
 * rendering, access logging.
 */
public final class ScimHttpServer {

    private static final Logger log = Logger.getLogger("scim.access");
    private static final String SCIM_CONTENT_TYPE = "application/scim+json; charset=utf-8";

    private final ServerConfig config;
    private final SchemaRegistry registry;
    private final ScimBackend backend;
    private final Map<String, ResourceEndpoint> endpointsByPath = new LinkedHashMap<>(); // "users" -> endpoint
    private final Map<String, byte[]> tokenDigests = new HashMap<>();
    private final Map<String, String> tokenNames;

    private DiscoveryEndpoints discovery;
    private HttpServer httpServer;
    private String baseUrl;

    public ScimHttpServer(ServerConfig config, SchemaRegistry registry, ScimBackend backend) {
        this.config = config;
        this.registry = registry;
        this.backend = backend;
        this.tokenNames = config.bearerTokens();
        if (tokenNames.isEmpty()) {
            throw new IllegalArgumentException("no auth.bearerTokens configured; refusing to start unauthenticated");
        }
        for (String token : tokenNames.keySet()) {
            tokenDigests.put(token, sha256(token));
        }
    }

    public String baseUrl() { return baseUrl; }

    public int start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(config.port()), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.createContext("/", this::dispatch);
        int port = httpServer.getAddress().getPort(); // bound at create()
        // endpoints need the externally visible base URL, which may derive from the bound port
        baseUrl = config.baseUrl(port);
        discovery = new DiscoveryEndpoints(registry, backend.capabilities(),
                baseUrl, backend.capabilities().maxInMemoryScan());
        for (ResourceType rt : registry.allResourceTypes()) {
            ResourceBackend rb = backend.resource(rt.name());
            if (rb == null) continue;
            String segment = rt.endpoint().startsWith("/") ? rt.endpoint().substring(1) : rt.endpoint();
            endpointsByPath.put(segment.toLowerCase(), new ResourceEndpoint(
                    registry, rt, rb, backend.capabilities(), baseUrl, config.defaultPageSize()));
        }
        httpServer.start();
        return port;
    }

    public void stop() {
        if (httpServer != null) httpServer.stop(0);
        backend.close();
    }

    // ---- request handling ----

    private void dispatch(HttpExchange ex) throws IOException {
        long startNanos = System.nanoTime();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String principal = null;
        int status;
        try {
            if (path.equals("/healthz")) {
                backend.healthCheck();
                status = sendRaw(ex, 200, "{\"status\":\"ok\"}", "application/json");
            } else {
                principal = authenticate(ex);
                if (principal == null) {
                    ex.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"scim\"");
                    status = sendError(ex, new ScimException(401, null, "authentication required"));
                } else {
                    status = route(ex, method, path);
                }
            }
        } catch (ScimException e) {
            status = sendError(ex, e);
        } catch (JsonException e) {
            status = sendError(ex, ScimException.invalidSyntax("malformed JSON: " + e.getMessage()));
        } catch (Exception e) {
            log.log(Level.SEVERE, "unhandled error for " + method + " " + path, e);
            status = sendError(ex, new ScimException(500, null, "internal error"));
        } finally {
            ex.close();
        }
        long micros = (System.nanoTime() - startNanos) / 1_000;
        int finalStatus = status;
        String who = principal == null ? "-" : principal; // client name only; token values are never logged
        log.info(() -> method + " " + path + " " + finalStatus + " " + who + " " + micros / 1000.0 + "ms");
    }

    private int route(HttpExchange ex, String method, String path) throws IOException {
        String context = config.contextPath();
        if (!path.startsWith(context + "/") && !path.equals(context)) {
            return sendError(ex, ScimException.notFound("no such endpoint: " + path));
        }
        String rest = path.length() > context.length() ? path.substring(context.length() + 1) : "";
        // trim trailing slash
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        String[] segments = rest.isEmpty() ? new String[0] : rest.split("/");

        if (segments.length == 0) return sendError(ex, ScimException.notFound("no such endpoint: " + path));
        String head = segments[0];

        // discovery endpoints
        if (head.equalsIgnoreCase("ServiceProviderConfig") && segments.length == 1) {
            requireMethod(method, "GET");
            return sendJson(ex, 200, discovery.serviceProviderConfig(), Map.of());
        }
        if (head.equalsIgnoreCase("Schemas")) {
            requireMethod(method, "GET");
            if (segments.length == 1) return sendJson(ex, 200, discovery.schemasList(), Map.of());
            JsonObject s = discovery.schema(decode(segments[1]));
            if (s == null) return sendError(ex, ScimException.notFound("unknown schema " + segments[1]));
            return sendJson(ex, 200, s, Map.of());
        }
        if (head.equalsIgnoreCase("ResourceTypes")) {
            requireMethod(method, "GET");
            if (segments.length == 1) return sendJson(ex, 200, discovery.resourceTypesList(), Map.of());
            JsonObject rt = discovery.resourceType(decode(segments[1]));
            if (rt == null) return sendError(ex, ScimException.notFound("unknown resource type " + segments[1]));
            return sendJson(ex, 200, rt, Map.of());
        }
        if (head.equalsIgnoreCase("Me")) {
            return sendError(ex, new ScimException(501, null, "/Me is not supported"));
        }
        if (head.equalsIgnoreCase("Bulk")) {
            return sendError(ex, new ScimException(501, null, "/Bulk is not yet supported"));
        }

        ResourceEndpoint endpoint = endpointsByPath.get(head.toLowerCase());
        if (endpoint == null) return sendError(ex, ScimException.notFound("no such endpoint: " + path));

        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        Response r;
        if (segments.length == 1) {
            r = switch (method) {
                case "GET" -> endpoint.list(query);
                case "POST" -> endpoint.create(readBody(ex));
                default -> throw methodNotAllowed(method);
            };
        } else if (segments.length == 2 && segments[1].equals(".search")) {
            requireMethod(method, "POST");
            r = endpoint.search(readBody(ex));
        } else if (segments.length == 2) {
            String id = decode(segments[1]);
            r = switch (method) {
                case "GET" -> endpoint.get(id, query);
                case "PUT" -> endpoint.put(id, readBody(ex));
                case "PATCH" -> endpoint.patch(id, readBody(ex));
                case "DELETE" -> endpoint.delete(id);
                default -> throw methodNotAllowed(method);
            };
        } else {
            return sendError(ex, ScimException.notFound("no such endpoint: " + path));
        }
        return sendJson(ex, r.status(), r.body(), r.headers());
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) throw methodNotAllowed(actual);
    }

    private static ScimException methodNotAllowed(String method) {
        return new ScimException(405, null, "method " + method + " not allowed here");
    }

    // ---- auth ----

    /** Returns the client name, or null when unauthenticated. Constant-time comparison. */
    private String authenticate(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        byte[] presented = sha256(header.substring(7).trim());
        String match = null;
        for (Map.Entry<String, byte[]> e : tokenDigests.entrySet()) {
            if (MessageDigest.isEqual(presented, e.getValue())) {
                match = tokenNames.get(e.getKey());
            }
        }
        return match;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- I/O ----

    private JsonObject readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(config.maxPayloadBytes() + 1);
            if (data.length > config.maxPayloadBytes()) {
                throw new ScimException(413, null, "request body exceeds " + config.maxPayloadBytes() + " bytes");
            }
            if (data.length == 0) throw ScimException.invalidSyntax("request body is empty");
            return Json.parseObject(new String(data, StandardCharsets.UTF_8));
        }
    }

    private int sendJson(HttpExchange ex, int status, JsonObject body, Map<String, String> headers)
            throws IOException {
        headers.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return status;
        }
        return sendRaw(ex, status, Json.write(body), SCIM_CONTENT_TYPE);
    }

    private int sendRaw(HttpExchange ex, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
        return status;
    }

    private int sendError(HttpExchange ex, ScimException e) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add(CoreSchemas.ERROR_URN);
        body.put("schemas", schemas);
        body.put("status", String.valueOf(e.status()));
        if (e.scimType() != null) body.put("scimType", e.scimType());
        if (e.getMessage() != null) body.put("detail", e.getMessage());
        return sendRaw(ex, e.status(), Json.write(body), SCIM_CONTENT_TYPE);
    }

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) params.put(decode(pair), "");
            else params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return params;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new UncheckedIOException(new IOException("bad URL encoding: " + s));
        }
    }
}
