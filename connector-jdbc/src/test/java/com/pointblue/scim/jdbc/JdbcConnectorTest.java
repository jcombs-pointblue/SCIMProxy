// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.jdbc;

import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.patch.PatchOp;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;
import com.pointblue.scim.spi.UnsupportedFilterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the JDBC connector against an in-memory H2 database in PostgreSQL mode. */
class JdbcConnectorTest {

    private static final String URL =
            "jdbc:h2:mem:scimtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private JdbcBackend backend;
    private ResourceBackend users;
    private ResourceBackend groups;

    @BeforeEach
    void setup() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("""
                CREATE TABLE users (
                  id uuid DEFAULT random_uuid() PRIMARY KEY,
                  username varchar(64) UNIQUE NOT NULL,
                  given_name varchar(64), family_name varchar(64), email varchar(128),
                  title varchar(64), active boolean NOT NULL DEFAULT true,
                  created_at timestamp with time zone DEFAULT now(),
                  updated_at timestamp with time zone DEFAULT now())""");
            st.execute("""
                CREATE TABLE groups (
                  id uuid DEFAULT random_uuid() PRIMARY KEY,
                  display_name varchar(128) UNIQUE NOT NULL,
                  created_at timestamp with time zone DEFAULT now(),
                  updated_at timestamp with time zone DEFAULT now())""");
            st.execute("""
                CREATE TABLE group_members (
                  group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
                  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  PRIMARY KEY (group_id, user_id))""");
            st.execute("INSERT INTO users (username,given_name,family_name,email,active) VALUES "
                    + "('alice','Alice','Anderson','alice@example.com',true),"
                    + "('bob','Bob','Brown','bob@example.com',false)");
        }
        JsonObject cfg = Json.parseObject("""
            {"type":"jdbc","url":"%s","user":"sa","password":"",
             "users":{"table":"users","idColumn":"id",
               "createdColumn":"created_at","modifiedColumn":"updated_at",
               "columns":{"userName":"username","name.givenName":"given_name",
                 "name.familyName":"family_name","emails[type eq \\"work\\"].value":"email",
                 "title":"title","active":"active"}},
             "groups":{"table":"groups","idColumn":"id","displayNameColumn":"display_name",
               "createdColumn":"created_at","modifiedColumn":"updated_at",
               "membership":{"table":"group_members","groupColumn":"group_id","userColumn":"user_id"}}}
            """.formatted(URL));
        backend = new JdbcBackend(cfg, SchemaRegistry.withDefaults());
        backend.healthCheck();
        users = backend.resource("User");
        groups = backend.resource("Group");
    }

    private static List<PatchOp> ops(String opsJson) {
        return PatchOp.parseAll(Json.parseObject("""
            {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":%s}"""
                .formatted(opsJson)));
    }

    @Test
    void readsSeededRowsIntoScimShape() {
        SearchResult r = users.search(new SearchRequest(null, 1, 10));
        assertEquals(2, r.totalResults());
        JsonObject alice = r.resources().stream()
                .filter(u -> "alice".equals(u.optString("userName", ""))).findFirst().orElseThrow();
        assertEquals("Anderson", alice.get("name").asObject().optString("familyName", null));
        assertEquals("alice@example.com",
                alice.get("emails").asArray().get(0).asObject().optString("value", null));
        assertTrue(alice.optBoolean("active", false));
        assertNotNull(alice.optString("id", null));
        assertNotNull(alice.get("meta").asObject().optString("created", null));
    }

    @Test
    void createGetReplaceDelete() {
        JsonObject created = users.create(Json.parseObject("""
            {"userName":"carol","name":{"givenName":"Carol","familyName":"Clark"},
             "emails":[{"value":"carol@example.com","type":"work"}],"title":"Manager","active":true}"""));
        String id = created.optString("id", null);
        assertNotNull(id);
        assertEquals("carol", created.optString("userName", null));

        JsonObject got = users.get(id);
        assertEquals("Manager", got.optString("title", null));

        users.replace(id, Json.parseObject("""
            {"userName":"carol","name":{"givenName":"Caroline"},"active":false}"""));
        JsonObject after = users.get(id);
        assertEquals("Caroline", after.get("name").asObject().optString("givenName", null));
        assertFalse(after.optBoolean("active", true));
        assertNull(after.get("title"), "replace cleared the unset title column");

        users.delete(id);
        assertThrows(BackendException.class, () -> users.get(id));
    }

    @Test
    void uniqueConstraintBecomesConflict() {
        BackendException e = assertThrows(BackendException.class, () ->
                users.create(Json.parseObject("{\"userName\":\"alice\"}")));
        assertEquals(409, e.status());
    }

    @Test
    void filterPushdownToSql() {
        assertEquals(1, users.search(new SearchRequest(
                FilterParser.parse("userName eq \"ALICE\""), 1, 10)).totalResults(),
                "eq is case-insensitive per SCIM");
        assertEquals(1, users.search(new SearchRequest(
                FilterParser.parse("active eq false"), 1, 10)).totalResults());
        assertEquals(2, users.search(new SearchRequest(
                FilterParser.parse("userName co \"o\" or userName sw \"a\""), 1, 10)).totalResults());
        assertEquals(1, users.search(new SearchRequest(
                FilterParser.parse("name.familyName sw \"And\""), 1, 10)).totalResults());
    }

    @Test
    void emailFiltersPushDownToTheMappedColumn() {
        // emails is mapped as emails[type eq "work"].value; clients filter emails.value / emails
        assertEquals(1, users.search(new SearchRequest(
                FilterParser.parse("emails.value eq \"ALICE@example.com\""), 1, 10)).totalResults());
        assertEquals(1, users.search(new SearchRequest(
                FilterParser.parse("emails eq \"bob@example.com\""), 1, 10)).totalResults());
        assertEquals(2, users.search(new SearchRequest(
                FilterParser.parse("emails.value ew \"@example.com\""), 1, 10)).totalResults());
        assertEquals(2, users.search(new SearchRequest(
                FilterParser.parse("emails pr"), 1, 10)).totalResults());
    }

    @Test
    void filterInjectionIsParameterized() {
        // a hostile value must not break out of the parameter
        SearchResult r = users.search(new SearchRequest(
                FilterParser.parse("userName eq \"x'; DROP TABLE users;--\""), 1, 10));
        assertEquals(0, r.totalResults());
        assertEquals(2, users.search(new SearchRequest(null, 1, 10)).totalResults(), "table intact");
    }

    @Test
    void unmappedFilterAttributeSignalsFallback() {
        assertThrows(UnsupportedFilterException.class, () -> users.search(new SearchRequest(
                FilterParser.parse("nickName eq \"x\""), 1, 10)));
    }

    @Test
    void groupCrudAndMembership() {
        String alice = userId("alice");
        String bob = userId("bob");
        JsonObject g = groups.create(Json.parseObject("""
            {"displayName":"Engineering","members":[{"value":"%s"},{"value":"%s"}]}"""
                .formatted(alice, bob)));
        String gid = g.optString("id", null);
        assertEquals(2, g.get("members").asArray().size());

        // list includes members with display from the users table
        SearchResult list = groups.search(new SearchRequest(
                FilterParser.parse("displayName eq \"Engineering\""), 1, 10));
        JsonObject listed = list.resources().get(0);
        assertEquals(2, listed.get("members").asArray().size());
        assertTrue(listed.get("members").asArray().get(0).asObject().optString("display", "").length() > 0);

        groups.patch(gid, ops("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"%s\\\"]\"}]".formatted(bob)));
        assertEquals(1, groups.get(gid).get("members").asArray().size());

        groups.patch(gid, ops("[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"%s\"}]}]".formatted(bob)));
        assertEquals(2, groups.get(gid).get("members").asArray().size());

        JsonObject replaced = groups.replace(gid, Json.parseObject("""
            {"displayName":"Eng","members":[{"value":"%s"}]}""".formatted(alice)));
        assertEquals("Eng", replaced.optString("displayName", null));
        assertEquals(1, replaced.get("members").asArray().size());

        groups.delete(gid);
        assertThrows(BackendException.class, () -> groups.get(gid));
    }

    @Test
    void pagination() {
        for (int i = 0; i < 5; i++) users.create(Json.parseObject("{\"userName\":\"page" + i + "\"}"));
        SearchResult page = users.search(new SearchRequest(
                FilterParser.parse("userName sw \"page\""), 2, 2));
        assertEquals(5, page.totalResults());
        assertEquals(2, page.resources().size());
    }

    private String userId(String userName) {
        return users.search(new SearchRequest(FilterParser.parse("userName eq \"" + userName + "\""), 1, 1))
                .resources().get(0).optString("id", null);
    }
}
