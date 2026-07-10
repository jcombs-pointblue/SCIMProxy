// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.server.backend.InMemoryBackend;
import com.pointblue.scim.server.config.ServerConfig;
import com.pointblue.scim.server.http.ScimHttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Boots the real server (in-memory backend) and exercises the SCIM surface over HTTP. */
@TestMethodOrder(OrderAnnotation.class)
class ScimServerIntegrationTest {

    private static final String TOKEN = "test-token-123";
    private static final String CUSTOM = "urn:pointblue:scim:schemas:extension:custom:2.0:User";

    @TempDir
    static Path tmp;

    private static ScimHttpServer server;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();

    private static String userId;
    private static String user2Id;
    private static String groupId;

    @BeforeAll
    static void start() throws IOException {
        Path schemas = Files.createDirectory(tmp.resolve("schemas"));
        Files.writeString(schemas.resolve("custom-user.json"), """
                {"id":"%s","name":"PointBlueUser","description":"Custom attrs",
                 "attributes":[
                   {"name":"costCenter","type":"string"},
                   {"name":"badges","type":"string","multiValued":true}
                 ]}""".formatted(CUSTOM));
        Path configFile = tmp.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "server": {"port": 0},
                  "auth": {"bearerTokens": [{"name": "test", "token": "%s"}]},
                  "schemas": {"dir": "%s"},
                  "resourceTypes": {"User": {"extensions": [{"schema": "%s", "required": false}]}},
                  "backend": {"type": "memory"}
                }""".formatted(TOKEN, schemas, CUSTOM));
        ServerConfig config = ServerConfig.load(configFile);
        SchemaRegistry registry = SchemaRegistry.withDefaults();
        config.applySchemas(registry);
        server = new ScimHttpServer(config, registry, new InMemoryBackend(registry));
        int port = server.start();
        base = "http://localhost:" + port + "/scim/v2";
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop();
    }

    // ---- helpers ----

    private static HttpResponse<String> send(String method, String path, String body, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (body != null) {
                b.header("Content-Type", "application/scim+json");
                b.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpResponse<String> call(String method, String path) { return send(method, path, null, TOKEN); }
    private static HttpResponse<String> call(String method, String path, String body) { return send(method, path, body, TOKEN); }
    private static JsonObject json(HttpResponse<String> r) { return Json.parseObject(r.body()); }

    // ---- tests ----

    @Test @Order(1)
    void rejectsMissingAndBadTokens() {
        assertEquals(401, send("GET", "/Users", null, null).statusCode());
        assertEquals(401, send("GET", "/Users", null, "wrong-token").statusCode());
    }

    @Test @Order(2)
    void servesDiscoveryEndpoints() {
        HttpResponse<String> spc = call("GET", "/ServiceProviderConfig");
        assertEquals(200, spc.statusCode());
        assertTrue(spc.headers().firstValue("Content-Type").orElse("").startsWith("application/scim+json"));
        assertTrue(json(spc).get("patch").asObject().get("supported").asBoolean());

        JsonObject schemas = json(call("GET", "/Schemas"));
        boolean hasCustom = false;
        for (var s : schemas.get("Resources").asArray()) {
            if (CUSTOM.equals(s.asObject().optString("id", ""))) hasCustom = true;
        }
        assertTrue(hasCustom, "custom extension schema is published");

        JsonObject rt = json(call("GET", "/ResourceTypes/User"));
        assertEquals("/Users", rt.get("endpoint").asString());
        JsonArray exts = rt.get("schemaExtensions").asArray();
        boolean bound = false;
        for (var e : exts) if (CUSTOM.equals(e.asObject().optString("schema", ""))) bound = true;
        assertTrue(bound, "custom extension is bound to User");

        assertEquals(404, call("GET", "/Schemas/urn:nope").statusCode());
        assertEquals(501, call("GET", "/Me").statusCode());
    }

    @Test @Order(3)
    void createsUserWithExtensions() {
        HttpResponse<String> r = call("POST", "/Users", """
                {"userName":"bjensen","active":true,"password":"s3cret",
                 "name":{"givenName":"Barbara","familyName":"Jensen"},
                 "emails":[{"value":"bjensen@example.com","type":"work"}],
                 "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User":{"department":"Eng"},
                 "%s":{"costCenter":"CC-1","badges":["red"]}}""".formatted(CUSTOM));
        assertEquals(201, r.statusCode());
        JsonObject u = json(r);
        userId = u.get("id").asString();
        assertNull(u.get("password"), "password is never returned");
        assertEquals("CC-1", u.get(CUSTOM).asObject().get("costCenter").asString());
        String location = r.headers().firstValue("Location").orElseThrow();
        assertEquals(base + "/Users/" + userId, location);
        assertEquals(location, u.get("meta").asObject().get("location").asString());
        assertEquals(3, u.get("schemas").asArray().size());
    }

    @Test @Order(4)
    void rejectsDuplicateUserNameAndBadPayloads() {
        assertEquals(409, call("POST", "/Users", "{\"userName\":\"BJENSEN\"}").statusCode());
        HttpResponse<String> bad = call("POST", "/Users", "{\"noUserName\":true}");
        assertEquals(400, bad.statusCode());
        assertEquals("invalidValue", json(bad).optString("scimType", null));
        assertEquals(400, call("POST", "/Users", "not json").statusCode());
    }

    @Test @Order(5)
    void getsAndFiltersUsers() {
        JsonObject u = json(call("GET", "/Users/" + userId));
        assertEquals("bjensen", u.get("userName").asString());

        JsonObject list = json(call("GET", "/Users?filter=" + urlEncode("userName eq \"bjensen\"")));
        assertEquals(1, list.get("totalResults").asNumber().intValue());

        // filter on a config-defined custom extension attribute
        JsonObject byExt = json(call("GET", "/Users?filter="
                + urlEncode(CUSTOM + ":costCenter eq \"cc-1\"")));
        assertEquals(1, byExt.get("totalResults").asNumber().intValue());

        HttpResponse<String> badFilter = call("GET", "/Users?filter=" + urlEncode("userName xx 1"));
        assertEquals(400, badFilter.statusCode());
        assertEquals("invalidFilter", json(badFilter).optString("scimType", null));
    }

    @Test @Order(6)
    void patchesCoreAndExtensionAttributes() {
        HttpResponse<String> r = call("PATCH", "/Users/" + userId, """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[
                   {"op":"replace","path":"active","value":false},
                   {"op":"add","path":"emails","value":{"value":"second@example.com","type":"home"}},
                   {"op":"replace","path":"%s:costCenter","value":"CC-2"}
                 ]}""".formatted(CUSTOM));
        assertEquals(200, r.statusCode());
        JsonObject u = json(r);
        assertFalse(u.get("active").asBoolean());
        assertEquals(2, u.get("emails").asArray().size());
        assertEquals("CC-2", u.get(CUSTOM).asObject().get("costCenter").asString());
    }

    @Test @Order(7)
    void replacesWithPut() {
        HttpResponse<String> r = call("PUT", "/Users/" + userId, """
                {"userName":"bjensen","displayName":"Babs","active":true}""");
        assertEquals(200, r.statusCode());
        JsonObject u = json(r);
        assertEquals("Babs", u.get("displayName").asString());
        assertNull(u.get("emails"), "PUT is a full replace");
        assertEquals(userId, u.get("id").asString());
    }

    @Test @Order(8)
    void appliesAttributeProjection() {
        JsonObject u = json(call("GET", "/Users/" + userId + "?attributes=userName"));
        assertNotNull(u.get("userName"));
        assertNotNull(u.get("id"));
        assertNull(u.get("displayName"));

        JsonObject ex = json(call("GET", "/Users/" + userId + "?excludedAttributes=displayName"));
        assertNull(ex.get("displayName"));
        assertNotNull(ex.get("userName"));
    }

    @Test @Order(9)
    void searchEndpointWorks() {
        HttpResponse<String> r = call("POST", "/Users/.search", """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:SearchRequest"],
                 "filter":"userName sw \\"bj\\"","startIndex":1,"count":10}""");
        assertEquals(200, r.statusCode());
        assertEquals(1, json(r).get("totalResults").asNumber().intValue());
    }

    @Test @Order(10)
    void groupLifecycleWithMemberPatch() {
        user2Id = json(call("POST", "/Users", "{\"userName\":\"mandy\"}")).get("id").asString();

        HttpResponse<String> created = call("POST", "/Groups", """
                {"displayName":"Tour Guides","members":[{"value":"%s"},{"value":"%s"}]}"""
                .formatted(userId, user2Id));
        assertEquals(201, created.statusCode());
        groupId = json(created).get("id").asString();

        HttpResponse<String> patched = call("PATCH", "/Groups/" + groupId, """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"remove","path":"members[value eq \\"%s\\"]"}]}"""
                .formatted(user2Id));
        assertEquals(200, patched.statusCode());
        JsonArray members = json(patched).get("members").asArray();
        assertEquals(1, members.size());
        assertEquals(userId, members.get(0).asObject().get("value").asString());

        JsonObject list = json(call("GET", "/Groups"));
        assertEquals(1, list.get("totalResults").asNumber().intValue());
    }

    @Test @Order(11)
    void deletesResources() {
        assertEquals(204, call("DELETE", "/Groups/" + groupId).statusCode());
        assertEquals(404, call("GET", "/Groups/" + groupId).statusCode());
        assertEquals(204, call("DELETE", "/Users/" + userId).statusCode());
        HttpResponse<String> gone = call("GET", "/Users/" + userId);
        assertEquals(404, gone.statusCode());
        assertEquals("404", json(gone).optString("status", null));
    }

    @Test @Order(12)
    void paginationBehaves() {
        for (int i = 0; i < 5; i++) {
            assertEquals(201, call("POST", "/Users", "{\"userName\":\"page" + i + "\"}").statusCode());
        }
        JsonObject page = json(call("GET", "/Users?startIndex=2&count=2&filter="
                + urlEncode("userName sw \"page\"")));
        assertEquals(5, page.get("totalResults").asNumber().intValue());
        assertEquals(2, page.get("itemsPerPage").asNumber().intValue());
        assertEquals(2, page.get("startIndex").asNumber().intValue());
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
