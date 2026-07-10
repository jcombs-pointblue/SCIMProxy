// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.file;

import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.patch.PatchOp;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileConnectorTest {

    @TempDir
    Path dir;

    private FileBackend backend(String format) {
        JsonObject cfg = Json.parseObject("""
            {"type":"file","format":"%s",
             "users":{"file":"%s/users.%s","idColumn":"id",
               "columns":{"userName":"username","name.givenName":"given_name",
                 "name.familyName":"family_name","emails[type eq \\"work\\"].value":"email",
                 "title":"title","active":"active"}},
             "groups":{"file":"%s/groups.%s","idColumn":"id","displayNameColumn":"display_name",
               "membersColumn":"members","memberDelimiter":";"}}
            """.formatted(format, dir, format, dir, format));
        return new FileBackend(cfg, SchemaRegistry.withDefaults());
    }

    private static JsonObject user(String un, String given, String family) {
        return Json.parseObject("""
            {"userName":"%s","name":{"givenName":"%s","familyName":"%s"},
             "emails":[{"value":"%s@example.com","type":"work"}],"active":true}"""
                .formatted(un, given, family, un));
    }

    private static List<PatchOp> ops(String opsJson) {
        return PatchOp.parseAll(Json.parseObject("""
            {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":%s}""".formatted(opsJson)));
    }

    // ---- CSV parser ----

    @Test
    void csvParserHandlesQuotingCommasNewlines() {
        String csv = "a,b,c\n\"x,y\",\"line1\nline2\",\"he said \"\"hi\"\"\"\nplain,,z\n";
        var rows = Csv.parse(csv);
        assertEquals(3, rows.size());
        assertEquals(List.of("x,y", "line1\nline2", "he said \"hi\""), rows.get(1));
        assertEquals(List.of("plain", "", "z"), rows.get(2));
        // round-trip
        assertEquals(rows, Csv.parse(Csv.write(rows)));
    }

    // ---- both formats ----

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"csv", "json"})
    void crudAndPersistence(String format) throws Exception {
        FileBackend b = backend(format);
        ResourceBackend users = b.resource("User");

        JsonObject created = users.create(user("alice", "Alice", "Anderson"));
        String id = created.optString("id", null);
        assertNotNull(id);
        assertTrue(created.optBoolean("active", false), "active coerced to boolean");
        assertEquals("Anderson", created.get("name").asObject().optString("familyName", null));

        // the file exists on disk and a fresh backend reads it back
        assertTrue(Files.exists(dir.resolve("users." + format)));
        ResourceBackend reopened = backend(format).resource("User");
        JsonObject got = reopened.get(id);
        assertEquals("alice", got.optString("userName", null));
        assertEquals("alice@example.com",
                got.get("emails").asArray().get(0).asObject().optString("value", null));

        users.replace(id, Json.parseObject("{\"userName\":\"alice\",\"name\":{\"givenName\":\"Alicia\"}}"));
        JsonObject after = backend(format).resource("User").get(id);
        assertEquals("Alicia", after.get("name").asObject().optString("givenName", null));
        assertNull(after.get("title"), "replace cleared unset title");

        users.delete(id);
        assertThrows(BackendException.class, () -> users.get(id));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"csv", "json"})
    void filterAndPaginationAndUniqueness(String format) {
        ResourceBackend users = backend(format).resource("User");
        users.create(user("alice", "Alice", "Anderson"));
        users.create(user("bob", "Bob", "Brown"));
        users.create(user("carol", "Carol", "Clark"));

        assertEquals(1, users.search(new SearchRequest(
                FilterParser.parse("userName eq \"ALICE\""), 1, 10)).totalResults(), "case-insensitive eq");
        assertEquals(2, users.search(new SearchRequest(
                FilterParser.parse("name.familyName sw \"B\" or name.familyName sw \"C\""), 1, 10)).totalResults());

        SearchResult page = users.search(new SearchRequest(FilterParser.parse("userName pr"), 2, 1));
        assertEquals(3, page.totalResults());
        assertEquals(1, page.resources().size());

        assertEquals(409, assertThrows(BackendException.class,
                () -> users.create(user("Alice", "A", "A"))).status());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"csv", "json"})
    void groupMembershipInlineList(String format) throws Exception {
        FileBackend b = backend(format);
        ResourceBackend users = b.resource("User");
        ResourceBackend groups = b.resource("Group");
        String alice = users.create(user("alice", "Alice", "A")).optString("id", null);
        String bob = users.create(user("bob", "Bob", "B")).optString("id", null);

        JsonObject g = groups.create(Json.parseObject("""
            {"displayName":"Engineering","members":[{"value":"%s"},{"value":"%s"}]}"""
                .formatted(alice, bob)));
        String gid = g.optString("id", null);
        assertEquals(2, g.get("members").asArray().size());
        // display resolved from the users file
        assertTrue(g.get("members").asArray().get(0).asObject().optString("display", "").length() > 0);

        groups.patch(gid, ops("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"%s\\\"]\"}]".formatted(bob)));
        assertEquals(1, backend(format).resource("Group").get(gid).get("members").asArray().size());

        groups.patch(gid, ops("[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"%s\"}]}]".formatted(bob)));
        assertEquals(2, groups.get(gid).get("members").asArray().size());

        // membership persisted in the file in the format-appropriate shape
        String raw = Files.readString(dir.resolve("groups." + format));
        if (format.equals("json")) assertTrue(raw.contains("[") && raw.contains(alice));
        else assertTrue(raw.contains(alice + ";" + bob) || raw.contains(bob + ";" + alice));

        assertEquals(1, backend(format).resource("Group").search(new SearchRequest(
                FilterParser.parse("displayName eq \"Engineering\""), 1, 10)).totalResults());

        groups.delete(gid);
        assertThrows(BackendException.class, () -> groups.get(gid));
    }

    @Test
    void readsPreExistingCsvExport() throws Exception {
        Files.writeString(dir.resolve("users.csv"),
                "id,username,given_name,family_name,email,active\n"
              + "u1,jsmith,John,Smith,jsmith@example.com,true\n"
              + "u2,mjones,Mary,Jones,mjones@example.com,false\n");
        ResourceBackend users = backend("csv").resource("User");
        SearchResult all = users.search(new SearchRequest(null, 1, 10));
        assertEquals(2, all.totalResults());
        JsonObject u1 = users.get("u1");
        assertEquals("jsmith", u1.optString("userName", null));
        assertTrue(u1.optBoolean("active", false));
        assertFalse(users.get("u2").optBoolean("active", true));
    }
}
