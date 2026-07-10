package com.pointblue.scim.patch;

import com.pointblue.scim.ScimException;
import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.SchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatchEngineTest {

    private static final String ENT = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";

    private final SchemaRegistry registry = SchemaRegistry.withDefaults();
    private final PatchEngine userEngine = new PatchEngine(registry, "User");
    private final PatchEngine groupEngine = new PatchEngine(registry, "Group");

    private JsonObject user() {
        return Json.parseObject("""
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                  "id": "u1",
                  "userName": "bjensen",
                  "active": true,
                  "name": {"givenName": "Barbara", "familyName": "Jensen"},
                  "emails": [
                    {"value": "bjensen@example.com", "type": "work"},
                    {"value": "babs@home.example.org", "type": "home"}
                  ]
                }""");
    }

    private JsonObject group() {
        return Json.parseObject("""
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"],
                  "id": "g1",
                  "displayName": "Tour Guides",
                  "members": [
                    {"value": "u1", "display": "Babs"},
                    {"value": "u2", "display": "Mandy"}
                  ]
                }""");
    }

    private List<PatchOp> ops(String operationsJson) {
        JsonObject body = Json.parseObject("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations": %s}""".formatted(operationsJson));
        return PatchOp.parseAll(body);
    }

    @Test
    void addWithoutPathMergesAttributes() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"add","value":{"nickName":"Babs","displayName":"Babs J"}}]"""));
        assertEquals("Babs", u.get("nickName").asString());
        assertEquals("Babs J", u.get("displayName").asString());
    }

    @Test
    void addAppendsToMultiValued() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"add","path":"emails","value":{"value":"third@x.com","type":"other"}}]"""));
        assertEquals(3, u.get("emails").asArray().size());
    }

    @Test
    void replaceWithValueFilterTargetsMatchingElement() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"replace","path":"emails[type eq \\"work\\"].value","value":"new@example.com"}]"""));
        JsonArray emails = u.get("emails").asArray();
        assertEquals("new@example.com", emails.get(0).asObject().get("value").asString());
        assertEquals("babs@home.example.org", emails.get(1).asObject().get("value").asString());
    }

    @Test
    void replaceSimpleAttributeAndComplexSubAttr() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"replace","path":"active","value":false},
                 {"op":"replace","path":"name.givenName","value":"Barb"}]"""));
        assertFalse(u.get("active").asBoolean());
        assertEquals("Barb", u.get("name").asObject().get("givenName").asString());
    }

    @Test
    void removeWithValueFilterDeletesElement() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"remove","path":"emails[type eq \\"home\\"]"}]"""));
        JsonArray emails = u.get("emails").asArray();
        assertEquals(1, emails.size());
        assertEquals("work", emails.get(0).asObject().get("type").asString());
    }

    @Test
    void removeWholeAttributeAndSubAttribute() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"remove","path":"name.familyName"},
                 {"op":"remove","path":"emails"}]"""));
        assertNull(u.get("name").asObject().get("familyName"));
        assertNull(u.get("emails"));
    }

    @Test
    void groupMemberAddAndRemoveByValue() {
        JsonObject g = group();
        groupEngine.apply(g, ops("""
                [{"op":"add","path":"members","value":[{"value":"u3","display":"New"}]}]"""));
        assertEquals(3, g.get("members").asArray().size());

        // Okta/Azure idiom: remove with op value listing the members
        groupEngine.apply(g, ops("""
                [{"op":"remove","path":"members","value":[{"value":"u1"}]}]"""));
        JsonArray members = g.get("members").asArray();
        assertEquals(2, members.size());
        for (var m : members) assertNotEquals("u1", m.asObject().get("value").asString());

        groupEngine.apply(g, ops("""
                [{"op":"remove","path":"members[value eq \\"u2\\"]"}]"""));
        assertEquals(1, g.get("members").asArray().size());
    }

    @Test
    void addWithNonMatchingFilterSeedsElementFromEqClauses() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"add","path":"phoneNumbers[type eq \\"mobile\\"].value","value":"555-1234"}]"""));
        JsonArray phones = u.get("phoneNumbers").asArray();
        assertEquals(1, phones.size());
        assertEquals("mobile", phones.get(0).asObject().get("type").asString());
        assertEquals("555-1234", phones.get(0).asObject().get("value").asString());
    }

    @Test
    void extensionAttributesViaUrnPath() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"add","path":"%s:department","value":"Tooling"},
                 {"op":"add","path":"%s:manager.value","value":"u9"}]""".formatted(ENT, ENT)));
        JsonObject ext = u.get(ENT).asObject();
        assertEquals("Tooling", ext.get("department").asString());
        assertEquals("u9", ext.get("manager").asObject().get("value").asString());
    }

    @Test
    void noPathMergeWithExtensionNamespace() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"add","value":{"%s":{"costCenter":"CC-1"}}}]""".formatted(ENT)));
        assertEquals("CC-1", u.get(ENT).asObject().get("costCenter").asString());
    }

    @Test
    void errorCases() {
        assertThrows(ScimException.class, () -> userEngine.apply(user(), ops("""
                [{"op":"remove"}]""")), "remove without path");
        assertThrows(ScimException.class, () -> userEngine.apply(user(), ops("""
                [{"op":"replace","path":"emails[type eq \\"none\\"].value","value":"x"}]""")),
                "replace with non-matching filter");
        assertThrows(ScimException.class, () -> userEngine.apply(user(), ops("""
                [{"op":"replace","path":"nosuchattr","value":"x"}]""")), "unknown attribute");
        assertThrows(ScimException.class, () -> userEngine.apply(user(), ops("""
                [{"op":"replace","path":"groups","value":[]}]""")), "readOnly attribute");
    }

    @Test
    void opNamesAreCaseInsensitiveAndValueRequired() {
        JsonObject u = user();
        userEngine.apply(u, ops("""
                [{"op":"Replace","path":"active","value":false}]"""));
        assertFalse(u.get("active").asBoolean());
        assertThrows(ScimException.class, () -> ops("""
                [{"op":"add","path":"nickName"}]"""));
    }
}
