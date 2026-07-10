// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.okta;

import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.patch.PatchOp;
import com.pointblue.scim.schema.Schema;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;
import com.pointblue.scim.spi.UnsupportedFilterException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OktaConnectorTest {

    private static final String CUSTOM = "urn:pointblue:scim:schemas:extension:custom:2.0:User";

    @TempDir
    static Path tmp;

    private static FakeOkta okta;
    private static OktaBackend backend;
    private static ResourceBackend users;
    private static ResourceBackend groups;

    @BeforeAll
    static void start() throws Exception {
        okta = new FakeOkta();
        Path pem = writePkcs8Key(tmp.resolve("okta-key.pem"));

        SchemaRegistry registry = SchemaRegistry.withDefaults();
        registry.register(Schema.fromJson(Json.parseObject("""
                {"id":"%s","name":"PointBlueUser",
                 "attributes":[{"name":"costCenter","type":"string"}]}""".formatted(CUSTOM))));
        registry.addExtension("User", CUSTOM, false);

        JsonObject config = Json.parseObject("""
                {"type":"okta",
                 "orgUrl":"%s",
                 "clientId":"0oatest",
                 "privateKeyPem":"%s",
                 "userMappings":[
                   {"scim":"externalId","okta":"profile.externalId"},
                   {"scim":"%s:costCenter","okta":"profile.costCenter"}
                 ]}""".formatted(okta.url(), pem, CUSTOM));
        backend = new OktaBackend(config, registry);
        users = backend.resource("User");
        groups = backend.resource("Group");
    }

    private static Path writePkcs8Key(Path file) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        byte[] pkcs8 = gen.generateKeyPair().getPrivate().getEncoded();
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8)
                + "\n-----END PRIVATE KEY-----\n");
        return file;
    }

    @AfterAll
    static void stop() {
        if (okta != null) okta.stop();
    }

    @BeforeEach
    void reset() {
        okta.users.clear();
        okta.groups.clear();
        okta.members.clear();
    }

    private JsonObject createUser(String userName) {
        return users.create(Json.parseObject("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"%s","active":true,
                 "name":{"givenName":"Barbara","familyName":"Jensen"},
                 "emails":[{"value":"%s@example.com","type":"work"},
                           {"value":"%s@home.example.org","type":"home"}],
                 "externalId":"ext-%s",
                 "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User":{"department":"Eng"},
                 "%s":{"costCenter":"CC-7"}}"""
                .formatted(userName, userName, userName, userName, CUSTOM)));
    }

    @Test
    void authUsesPrivateKeyJwtAssertion() {
        backend.healthCheck();
        assertNotNull(okta.lastAssertion, "token request carried a client assertion");
        assertEquals(3, okta.lastAssertion.split("\\.").length);
    }

    @Test
    void clientSecretAuthIsSupported() {
        JsonObject config = Json.parseObject("""
                {"type":"okta","orgUrl":"%s","clientId":"0oasecret",
                 "clientSecret":"s3cret-value"}""".formatted(okta.url()));
        OktaBackend secretBackend = new OktaBackend(config, SchemaRegistry.withDefaults());
        okta.lastAssertion = null;
        secretBackend.healthCheck();
        assertEquals("s3cret-value", okta.lastClientSecret);
        assertNull(okta.lastAssertion, "no JWT assertion in client_secret mode");

        assertThrows(IllegalArgumentException.class, () -> new OktaBackend(Json.parseObject("""
                {"type":"okta","orgUrl":"%s","clientId":"x"}""".formatted(okta.url())),
                SchemaRegistry.withDefaults()), "one of privateKeyPem/clientSecret is required");
    }

    @Test
    void createRoundTripsMappedAndExtensionAttributes() {
        JsonObject created = createUser("bjensen");
        String id = created.optString("id", null);
        assertNotNull(id);
        assertTrue(created.optBoolean("active", false));

        JsonObject profile = okta.users.get(id).get("profile").asObject();
        assertEquals("bjensen", profile.optString("login", null));
        assertEquals("bjensen@example.com", profile.optString("email", null));
        assertEquals("bjensen@home.example.org", profile.optString("secondEmail", null));
        assertEquals("Eng", profile.optString("department", null));
        assertEquals("CC-7", profile.optString("costCenter", null));
        assertEquals("ext-bjensen", profile.optString("externalId", null));

        JsonObject fetched = users.get(id);
        assertEquals("bjensen", fetched.optString("userName", null));
        assertEquals("ext-bjensen", fetched.optString("externalId", null));
        assertEquals("Jensen", fetched.get("name").asObject().optString("familyName", null));
        assertEquals(2, fetched.get("emails").asArray().size());
        assertEquals("CC-7", fetched.get(CUSTOM).asObject().optString("costCenter", null));
        assertEquals("Eng", fetched.get("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User")
                .asObject().optString("department", null));
    }

    @Test
    void searchPushesFilterToOktaSearchParam() {
        createUser("target");
        createUser("other");
        SearchResult r = users.search(new SearchRequest(
                FilterParser.parse("userName eq \"target\""), 1, 10));
        assertEquals(1, r.totalResults());
        assertEquals("target", r.resources().get(0).optString("userName", null));
        assertEquals("profile.login eq \"target\"", okta.lastSearch);

        // custom extension attribute pushes down through its configured mapping
        SearchResult byCc = users.search(new SearchRequest(
                FilterParser.parse(CUSTOM + ":costCenter eq \"CC-7\""), 1, 10));
        assertEquals(2, byCc.totalResults());
        assertEquals("profile.costCenter eq \"CC-7\"", okta.lastSearch);
    }

    @Test
    void unsupportedFiltersSignalFallback() {
        assertThrows(UnsupportedFilterException.class, () -> users.search(new SearchRequest(
                FilterParser.parse("userName co \"j\""), 1, 10)));
        assertThrows(UnsupportedFilterException.class, () -> users.search(new SearchRequest(
                FilterParser.parse("not (userName eq \"x\")"), 1, 10)));
    }

    @Test
    void nativePatchUpdatesProfileAndLifecycle() {
        String id = createUser("patchme").optString("id", null);
        JsonObject result = users.patch(id, ops("""
                [{"op":"replace","path":"title","value":"Chief"},
                 {"op":"replace","path":"active","value":false}]"""));
        assertFalse(result.optBoolean("active", true));
        assertEquals("Chief", result.optString("title", null));
        assertEquals("DEPROVISIONED", okta.users.get(id).optString("status", null));

        users.patch(id, ops("[{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]"));
        assertEquals("ACTIVE", okta.users.get(id).optString("status", null));
    }

    @Test
    void valuePathPatchFallsBackToGetApplyReplace() {
        String id = createUser("vpatch").optString("id", null);
        JsonObject result = users.patch(id, ops("""
                [{"op":"replace","path":"emails[type eq \\"work\\"].value","value":"new@example.com"}]"""));
        boolean found = false;
        for (var e : result.get("emails").asArray()) {
            if ("work".equals(e.asObject().optString("type", ""))) {
                assertEquals("new@example.com", e.asObject().optString("value", null));
                found = true;
            }
        }
        assertTrue(found);
        assertEquals("new@example.com",
                okta.users.get(id).get("profile").asObject().optString("email", null));
    }

    @Test
    void deleteDeactivatesButKeepsUser() {
        String id = createUser("deleteme").optString("id", null);
        users.delete(id);
        assertTrue(okta.users.containsKey(id), "default deleteBehavior keeps the Okta user");
        assertEquals("DEPROVISIONED", okta.users.get(id).optString("status", null));
    }

    @Test
    void duplicateLoginBecomesConflict() {
        createUser("dupe");
        BackendException e = assertThrows(BackendException.class, () -> createUser("dupe"));
        assertEquals(409, e.status());
    }

    @Test
    void inactiveCreateStaysStagedUntilActivated() {
        JsonObject created = users.create(Json.parseObject(
                "{\"userName\":\"staged\",\"active\":false}"));
        String id = created.optString("id", null);
        assertFalse(created.optBoolean("active", true));
        assertEquals("STAGED", okta.users.get(id).optString("status", null));
    }

    @Test
    void groupLifecycleAndMembershipOps() {
        String u1 = createUser("member1").optString("id", null);
        String u2 = createUser("member2").optString("id", null);

        JsonObject g = groups.create(Json.parseObject("""
                {"displayName":"Tour Guides","members":[{"value":"%s"}]}""".formatted(u1)));
        String gid = g.optString("id", null);
        assertEquals("Tour Guides", g.optString("displayName", null));
        assertEquals(1, g.get("members").asArray().size());

        JsonObject afterAdd = groups.patch(gid, ops("""
                [{"op":"add","path":"members","value":[{"value":"%s"}]}]""".formatted(u2)));
        assertEquals(2, afterAdd.get("members").asArray().size());

        JsonObject afterRemove = groups.patch(gid, ops("""
                [{"op":"remove","path":"members[value eq \\"%s\\"]"}]""".formatted(u1)));
        assertEquals(1, afterRemove.get("members").asArray().size());
        assertEquals(u2, afterRemove.get("members").asArray().get(0).asObject().optString("value", null));

        JsonObject replaced = groups.replace(gid, Json.parseObject("""
                {"displayName":"Renamed","members":[{"value":"%s"}]}""".formatted(u1)));
        assertEquals("Renamed", replaced.optString("displayName", null));
        assertEquals(u1, replaced.get("members").asArray().get(0).asObject().optString("value", null));

        SearchResult found = groups.search(new SearchRequest(
                FilterParser.parse("displayName eq \"Renamed\""), 1, 10));
        assertEquals(1, found.totalResults());
        assertEquals("profile.name eq \"Renamed\"", okta.lastSearch);

        groups.delete(gid);
        assertFalse(okta.groups.containsKey(gid));
    }

    @Test
    void paginationWalksCursors() {
        for (int i = 0; i < 5; i++) {
            users.create(Json.parseObject("{\"userName\":\"page" + i + "\"}"));
        }
        SearchResult r = users.search(new SearchRequest(null, 2, 2));
        assertEquals(5, r.totalResults());
        assertEquals(2, r.resources().size());
        assertEquals("page1", r.resources().get(0).optString("userName", null));
    }

    private static List<PatchOp> ops(String operationsJson) {
        return PatchOp.parseAll(Json.parseObject("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations": %s}""".formatted(operationsJson)));
    }
}
