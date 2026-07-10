// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.okta;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.FilterEvaluator;
import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory imitation of the slice of the Okta Management API the connector
 * uses. Okta search expressions are evaluated with the core filter engine
 * (they share SCIM's filter syntax over Okta paths like profile.login).
 */
final class FakeOkta {

    static final String TOKEN = "fake-okta-token";

    final Map<String, JsonObject> users = new LinkedHashMap<>();
    final Map<String, JsonObject> groups = new LinkedHashMap<>();
    final Map<String, Set<String>> members = new LinkedHashMap<>();
    volatile String lastSearch;
    volatile String lastAssertion;
    volatile String lastClientSecret;

    private final HttpServer server;
    private final FilterEvaluator eval = new FilterEvaluator("", FilterEvaluator.CaseExactness.DEFAULT);

    FakeOkta() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() { return "http://localhost:" + server.getAddress().getPort(); }
    void stop() { server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (RuntimeException e) {
            error(ex, 500, e.toString());
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (path.equals("/oauth2/v1/token") && method.equals("POST")) {
            Map<String, String> form = parseForm(body);
            lastAssertion = form.get("client_assertion");
            lastClientSecret = form.get("client_secret");
            boolean jwtOk = lastAssertion != null && lastAssertion.split("\\.").length == 3;
            boolean secretOk = lastClientSecret != null && form.get("client_id") != null;
            if (!"client_credentials".equals(form.get("grant_type")) || (!jwtOk && !secretOk)) {
                error(ex, 400, "invalid token request");
                return;
            }
            send(ex, 200, Json.parseObject(
                    "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
            return;
        }

        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + TOKEN).equals(auth)) { error(ex, 401, "invalid token"); return; }

        Map<String, String> q = parseForm(ex.getRequestURI().getRawQuery());
        String[] seg = path.substring(1).split("/"); // api v1 users [id] [lifecycle op]

        if (seg.length >= 3 && seg[0].equals("api") && seg[1].equals("v1")) {
            switch (seg[2]) {
                case "users" -> { handleUsers(ex, method, seg, q, body); return; }
                case "groups" -> { handleGroups(ex, method, seg, q, body); return; }
            }
        }
        error(ex, 404, "no route " + method + " " + path);
    }

    // ---- users ----

    private void handleUsers(HttpExchange ex, String method, String[] seg,
                             Map<String, String> q, String body) throws IOException {
        if (seg.length == 3) {
            if (method.equals("POST")) {
                JsonObject req = Json.parseObject(body);
                JsonObject profile = req.get("profile").asObject();
                String login = profile.optString("login", null);
                for (JsonObject u : users.values()) {
                    if (login != null && login.equalsIgnoreCase(
                            u.get("profile").asObject().optString("login", ""))) {
                        error(ex, 400, "login: An object with this field already exists");
                        return;
                    }
                }
                boolean activate = "true".equals(q.getOrDefault("activate", "true"));
                JsonObject u = new JsonObject();
                u.put("id", "00u" + UUID.randomUUID().toString().replace("-", "").substring(0, 17));
                u.put("status", activate ? "ACTIVE" : "STAGED");
                u.put("created", Instant.now().toString());
                u.put("lastUpdated", Instant.now().toString());
                u.put("profile", profile.copy());
                users.put(u.optString("id", null), u);
                send(ex, 200, u);
            } else {
                list(ex, users, q);
            }
            return;
        }
        String id = seg[3];
        JsonObject u = users.get(id);
        if (u == null) { error(ex, 404, "Not found: " + id); return; }

        if (seg.length == 4) {
            switch (method) {
                case "GET" -> send(ex, 200, u);
                case "POST" -> { // partial profile update; null clears
                    JsonObject req = Json.parseObject(body);
                    if (req.get("profile") instanceof JsonObject patch) {
                        JsonObject profile = u.get("profile").asObject();
                        patch.forEach((k, v) -> {
                            if (v.isNull()) profile.remove(k);
                            else profile.put(k, v.copy());
                        });
                    }
                    touch(u);
                    send(ex, 200, u);
                }
                case "PUT" -> {
                    JsonObject req = Json.parseObject(body);
                    u.put("profile", req.get("profile").copy());
                    touch(u);
                    send(ex, 200, u);
                }
                case "DELETE" -> {
                    if (!"DEPROVISIONED".equals(u.optString("status", ""))) {
                        error(ex, 400, "user must be deactivated first");
                    } else {
                        users.remove(id);
                        empty(ex, 204);
                    }
                }
                default -> error(ex, 405, method);
            }
            return;
        }
        if (seg.length == 6 && seg[4].equals("lifecycle") && method.equals("POST")) {
            switch (seg[5]) {
                case "activate" -> u.put("status", "ACTIVE");
                case "deactivate" -> u.put("status", "DEPROVISIONED");
                case "unsuspend" -> u.put("status", "ACTIVE");
                case "suspend" -> u.put("status", "SUSPENDED");
                default -> { error(ex, 404, seg[5]); return; }
            }
            touch(u);
            send(ex, 200, u);
            return;
        }
        error(ex, 404, "no user route");
    }

    // ---- groups ----

    private void handleGroups(HttpExchange ex, String method, String[] seg,
                              Map<String, String> q, String body) throws IOException {
        if (seg.length == 3) {
            if (method.equals("POST")) {
                JsonObject req = Json.parseObject(body);
                JsonObject g = new JsonObject();
                g.put("id", "00g" + UUID.randomUUID().toString().replace("-", "").substring(0, 17));
                g.put("type", "OKTA_GROUP");
                g.put("created", Instant.now().toString());
                g.put("lastUpdated", Instant.now().toString());
                g.put("profile", req.get("profile").copy());
                groups.put(g.optString("id", null), g);
                members.put(g.optString("id", null), new LinkedHashSet<>());
                send(ex, 200, g);
            } else {
                list(ex, groups, q);
            }
            return;
        }
        String id = seg[3];
        JsonObject g = groups.get(id);
        if (g == null) { error(ex, 404, "Not found: " + id); return; }

        if (seg.length == 4) {
            switch (method) {
                case "GET" -> send(ex, 200, g);
                case "PUT" -> {
                    g.put("profile", Json.parseObject(body).get("profile").copy());
                    touch(g);
                    send(ex, 200, g);
                }
                case "DELETE" -> { groups.remove(id); members.remove(id); empty(ex, 204); }
                default -> error(ex, 405, method);
            }
            return;
        }
        if (seg.length == 5 && seg[4].equals("users") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (String uid : members.get(id)) {
                JsonObject u = users.get(uid);
                if (u != null) arr.add(u.copy());
            }
            sendRaw(ex, 200, Json.write(arr), null);
            return;
        }
        if (seg.length == 6 && seg[4].equals("users")) {
            String uid = seg[5];
            if (method.equals("PUT")) {
                if (!users.containsKey(uid)) { error(ex, 404, "no user " + uid); return; }
                members.get(id).add(uid);
                empty(ex, 204);
            } else if (method.equals("DELETE")) {
                members.get(id).remove(uid);
                empty(ex, 204);
            } else {
                error(ex, 405, method);
            }
            return;
        }
        error(ex, 404, "no group route");
    }

    // ---- shared ----

    private void list(HttpExchange ex, Map<String, JsonObject> store, Map<String, String> q)
            throws IOException {
        lastSearch = q.get("search");
        Filter filter = lastSearch == null ? null : FilterParser.parse(lastSearch);
        List<JsonObject> matched = new ArrayList<>();
        for (JsonObject o : store.values()) {
            if (filter == null || eval.matches(filter, o)) matched.add(o);
        }
        int limit = Integer.parseInt(q.getOrDefault("limit", "200"));
        int after = q.containsKey("after") ? Integer.parseInt(q.get("after")) : 0;
        int end = Math.min(after + limit, matched.size());
        JsonArray arr = new JsonArray();
        for (JsonObject o : matched.subList(Math.min(after, matched.size()), end)) arr.add(o.copy());
        String link = end < matched.size()
                ? "<" + url() + ex.getRequestURI().getPath() + "?after=" + end + ">; rel=\"next\""
                : null;
        sendRaw(ex, 200, Json.write(arr), link);
    }

    private static void touch(JsonObject o) { o.put("lastUpdated", Instant.now().toString()); }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                      URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private void send(HttpExchange ex, int status, JsonValue body) throws IOException {
        sendRaw(ex, status, Json.write(body), null);
    }

    private void error(HttpExchange ex, int status, String summary) throws IOException {
        sendRaw(ex, status, Json.write(new JsonObject()
                .put("errorCode", "E0000000").put("errorSummary", summary)), null);
    }

    private void empty(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
    }

    private void sendRaw(HttpExchange ex, int status, String body, String linkHeader) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (linkHeader != null) ex.getResponseHeaders().set("Link", linkHeader);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
