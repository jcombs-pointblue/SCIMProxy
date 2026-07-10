package com.pointblue.scim.ldap;

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
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LdapConnectorTest {

    private static InMemoryDirectoryServer ds;
    private static SchemaRegistry registry;

    private static ResourceBackend edirUsers, edirGroups, adUsers, adGroups, olUsers;

    @BeforeAll
    static void start() throws Exception {
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig("o=test");
        cfg.setSchema(null); // schema-less: accepts eDir/AD-style attributes
        cfg.addAdditionalBindCredentials("cn=admin,o=test", "secret");
        ds = new InMemoryDirectoryServer(cfg);
        ds.add("dn: o=test", "objectClass: top", "objectClass: organization", "o: test");
        for (String ou : List.of("edir-users", "edir-groups", "ad-users", "ad-groups", "ol-users")) {
            ds.add("dn: ou=" + ou + ",o=test", "objectClass: organizationalUnit", "ou: " + ou);
        }
        ds.startListening();

        registry = SchemaRegistry.withDefaults();
        LdapBackend edir = backend("""
                {"flavor":"edirectory",
                 "users":{"baseDn":"ou=edir-users,o=test"},
                 "groups":{"baseDn":"ou=edir-groups,o=test"}}""");
        edirUsers = edir.resource("User");
        edirGroups = edir.resource("Group");
        // the in-memory server doesn't auto-stamp objectCategory the way real AD does,
        // so override the default user filter (which correctly excludes computer accounts)
        LdapBackend ad = backend("""
                {"flavor":"ad",
                 "users":{"baseDn":"ou=ad-users,o=test","filter":"(objectClass=user)"},
                 "groups":{"baseDn":"ou=ad-groups,o=test"}}""");
        adUsers = ad.resource("User");
        adGroups = ad.resource("Group");
        LdapBackend ol = backend("""
                {"flavor":"openldap",
                 "users":{"baseDn":"ou=ol-users,o=test"}}""");
        olUsers = ol.resource("User");
    }

    private static LdapBackend backend(String flavorJson) {
        JsonObject cfg = Json.parseObject(flavorJson);
        cfg.put("type", "ldap");
        cfg.put("url", "ldap://localhost:" + ds.getListenPort());
        cfg.put("bindDn", "cn=admin,o=test");
        cfg.put("bindPassword", "secret");
        cfg.put("guidAttr", "entryUUID"); // the in-memory server has no GUID/objectGUID
        cfg.put("guidBinary", false);
        LdapBackend b = new LdapBackend(cfg, registry);
        b.healthCheck();
        return b;
    }

    @AfterAll
    static void stop() {
        if (ds != null) ds.shutDown(true);
    }

    private static JsonObject user(String userName) {
        return Json.parseObject("""
                {"userName":"%s","active":true,
                 "name":{"givenName":"Jane","familyName":"Doe"},
                 "emails":[{"value":"%s@example.com","type":"work"}]}"""
                .formatted(userName, userName));
    }

    private static List<PatchOp> ops(String operationsJson) {
        return PatchOp.parseAll(Json.parseObject("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations": %s}""".formatted(operationsJson)));
    }

    private String attr(String dn, String name) throws Exception {
        var e = ds.getConnection().getEntry(dn, name);
        return e == null ? null : e.getAttributeValue(name);
    }

    // ---- eDirectory flavor ----

    @Test
    void edirCreateGetSearchRoundTrip() throws Exception {
        JsonObject created = edirUsers.create(user("edir.jane"));
        String id = created.optString("id", null);
        assertNotNull(id);
        assertTrue(created.optBoolean("active", false));

        String dn = "cn=edir.jane,ou=edir-users,o=test";
        assertEquals("FALSE", attr(dn, "loginDisabled"));
        assertEquals("edir.jane@example.com", attr(dn, "mail"));
        assertEquals("Doe", attr(dn, "sn"));

        JsonObject fetched = edirUsers.get(id);
        assertEquals("edir.jane", fetched.optString("userName", null));
        assertEquals("edir.jane@example.com",
                fetched.get("emails").asArray().get(0).asObject().optString("value", null));

        SearchResult r = edirUsers.search(new SearchRequest(
                FilterParser.parse("userName eq \"edir.jane\""), 1, 10));
        assertEquals(1, r.totalResults());
        assertEquals(id, r.resources().get(0).optString("id", null));
    }

    @Test
    void edirActivePatchTogglesLoginDisabled() throws Exception {
        String id = edirUsers.create(user("edir.toggle")).optString("id", null);
        String dn = "cn=edir.toggle,ou=edir-users,o=test";
        JsonObject off = edirUsers.patch(id, ops("[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]"));
        assertFalse(off.optBoolean("active", true));
        assertEquals("TRUE", attr(dn, "loginDisabled"));
        edirUsers.patch(id, ops("[{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]"));
        assertEquals("FALSE", attr(dn, "loginDisabled"));
    }

    @Test
    void edirGroupSearchExcludesRbsScopeObjects() throws Exception {
        // a real group created through the connector
        edirGroups.create(Json.parseObject("{\"displayName\":\"RealGroup\"}"));
        // iManager RBS scope objects: groupOfNames carrying rbsScope / rbsScope2, named like a tree
        ds.add("dn: cn=o=test,ou=edir-groups,o=test",
                "objectClass: groupOfNames", "objectClass: rbsScope",
                "cn: o=test", "member: cn=admin,o=test");
        ds.add("dn: cn=scope2,ou=edir-groups,o=test",
                "objectClass: groupOfNames", "objectClass: rbsScope2",
                "cn: scope2", "member: cn=admin,o=test");

        SearchResult r = edirGroups.search(new SearchRequest(null, 1, 50));
        var names = r.resources().stream().map(g -> g.optString("displayName", "")).toList();
        assertTrue(names.contains("RealGroup"), "real group is returned");
        assertFalse(names.contains("o=test"), "rbsScope object is excluded");
        assertFalse(names.contains("scope2"), "rbsScope2 object is excluded");
    }

    @Test
    void edirGroupMembershipMaintainsBothSides() throws Exception {
        String uid = edirUsers.create(user("edir.member")).optString("id", null);
        String userDn = "cn=edir.member,ou=edir-users,o=test";

        JsonObject g = edirGroups.create(Json.parseObject("""
                {"displayName":"Guides","members":[{"value":"%s"}]}""".formatted(uid)));
        String gid = g.optString("id", null);
        String groupDn = "cn=Guides,ou=edir-groups,o=test";

        // both halves of both pairs, per eDirectory convention
        assertEquals(userDn, attr(groupDn, "member"));
        assertEquals(userDn, attr(groupDn, "equivalentToMe"));
        assertEquals(groupDn, attr(userDn, "groupMembership"));
        assertEquals(groupDn, attr(userDn, "securityEquals"));
        assertEquals(uid, g.get("members").asArray().get(0).asObject().optString("value", null));

        edirGroups.patch(gid, ops("""
                [{"op":"remove","path":"members[value eq \\"%s\\"]"}]""".formatted(uid)));
        assertNull(attr(groupDn, "member"));
        assertNull(attr(groupDn, "equivalentToMe"));
        assertNull(attr(userDn, "groupMembership"));
        assertNull(attr(userDn, "securityEquals"));

        edirGroups.delete(gid);
        assertNull(ds.getConnection().getEntry(groupDn));
    }

    @Test
    void edirRenameKeepsIdStable() throws Exception {
        JsonObject created = edirUsers.create(user("edir.old"));
        String id = created.optString("id", null);
        created.put("userName", "edir.new");
        JsonObject renamed = edirUsers.replace(id, created);
        assertEquals("edir.new", renamed.optString("userName", null));
        assertEquals(id, renamed.optString("id", null), "entryUUID survives the rename");
        assertNull(ds.getConnection().getEntry("cn=edir.old,ou=edir-users,o=test"));
        assertNotNull(ds.getConnection().getEntry("cn=edir.new,ou=edir-users,o=test"));
    }

    // ---- Active Directory flavor ----

    @Test
    void adUsesSamAccountNameAndUserAccountControl() throws Exception {
        JsonObject created = adUsers.create(user("ad.jane"));
        String id = created.optString("id", null);
        String dn = "cn=ad.jane,ou=ad-users,o=test";
        assertEquals("ad.jane", attr(dn, "sAMAccountName"));
        assertEquals("512", attr(dn, "userAccountControl"));

        adUsers.patch(id, ops("[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]"));
        assertEquals("514", attr(dn, "userAccountControl"), "disable bit set, other bits preserved");
        assertFalse(adUsers.get(id).optBoolean("active", true));

        SearchResult r = adUsers.search(new SearchRequest(
                FilterParser.parse("userName sw \"ad.ja\""), 1, 10));
        assertEquals(1, r.totalResults());
    }

    @Test
    void adGroupWritesMemberOnlyOnGroupSide() throws Exception {
        String uid = adUsers.create(user("ad.member")).optString("id", null);
        String userDn = "cn=ad.member,ou=ad-users,o=test";
        JsonObject g = adGroups.create(Json.parseObject("""
                {"displayName":"ADGroup","members":[{"value":"%s"}]}""".formatted(uid)));
        String groupDn = "cn=ADGroup,ou=ad-groups,o=test";
        assertEquals(userDn, attr(groupDn, "member"));
        // AD maintains memberOf itself; the connector must not write user-side attributes
        assertNull(attr(userDn, "groupMembership"));
        assertNull(attr(userDn, "memberOf"));
        assertEquals(1, g.get("members").asArray().size());
    }

    @Test
    void adPasswordOverPlainLdapIsRejected() {
        JsonObject u = user("ad.pw");
        u.put("password", "Sup3rSecret!");
        BackendException e = assertThrows(BackendException.class, () -> adUsers.create(u));
        assertTrue(e.getMessage().contains("ldaps"));
    }

    // ---- OpenLDAP flavor ----

    @Test
    void openldapUsesUidRdnAndIgnoresActive() throws Exception {
        JsonObject u = user("ol.jane");
        u.put("active", false); // activeStrategy none: ignored with a warning
        JsonObject created = olUsers.create(u);
        String dn = "uid=ol.jane,ou=ol-users,o=test";
        assertNotNull(ds.getConnection().getEntry(dn));
        assertTrue(created.optBoolean("active", false), "active reads true under strategy none");

        olUsers.delete(created.optString("id", null));
        assertNull(ds.getConnection().getEntry(dn));
    }

    // ---- cross-cutting ----

    @Test
    void unsupportedFilterSignalsFallback() {
        assertThrows(UnsupportedFilterException.class, () -> edirUsers.search(new SearchRequest(
                FilterParser.parse("emails[type eq \"work\"]"), 1, 10)));
        assertThrows(UnsupportedFilterException.class, () -> edirUsers.search(new SearchRequest(
                FilterParser.parse("nosuchattr eq \"x\""), 1, 10)));
    }

    @Test
    void filterValuesAreEscaped() throws Exception {
        edirUsers.create(user("edir.paren"));
        // a hostile filter value must not break the LDAP filter
        SearchResult r = edirUsers.search(new SearchRequest(
                FilterParser.parse("userName eq \"*)(objectClass=*\""), 1, 10));
        assertEquals(0, r.totalResults());
    }

    @Test
    void paginationSlicesResults() throws Exception {
        for (int i = 0; i < 5; i++) olUsers.create(user("ol.page" + i));
        SearchResult r = olUsers.search(new SearchRequest(
                FilterParser.parse("userName sw \"ol.page\""), 2, 2));
        assertEquals(5, r.totalResults());
        assertEquals(2, r.resources().size());
        long count = ds.getConnection().search("ou=ol-users,o=test", SearchScope.SUB,
                "(uid=ol.page*)").getEntryCount();
        assertEquals(5, count);
    }
}
