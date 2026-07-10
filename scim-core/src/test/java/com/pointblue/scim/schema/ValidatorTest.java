// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.schema;

import com.pointblue.scim.ScimException;
import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {

    private static final String ENT = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";

    private final SchemaRegistry registry = SchemaRegistry.withDefaults();
    private final Validator validator = new Validator(registry);

    @Test
    void acceptsValidUserAndNormalizesSchemas() {
        JsonObject u = Json.parseObject("""
                {"userName":"jdoe","active":true,
                 "emails":[{"value":"j@x.com","type":"work"}],
                 "%s":{"department":"Eng"}}""".formatted(ENT));
        validator.validate("User", u);
        var schemas = u.get("schemas").asArray();
        assertEquals(2, schemas.size());
        assertEquals(CoreSchemas.USER_URN, schemas.get(0).asString());
        assertEquals(ENT, schemas.get(1).asString());
    }

    @Test
    void rejectsMissingRequiredAttribute() {
        JsonObject u = Json.parseObject("{\"displayName\":\"No Name\"}");
        ScimException e = assertThrows(ScimException.class, () -> validator.validate("User", u));
        assertEquals("invalidValue", e.scimType());
        assertTrue(e.getMessage().contains("userName"));
    }

    @Test
    void rejectsUnknownAttributeAndNamespace() {
        assertThrows(ScimException.class, () ->
                validator.validate("User", Json.parseObject("{\"userName\":\"x\",\"frobnicate\":1}")));
        assertThrows(ScimException.class, () ->
                validator.validate("User", Json.parseObject(
                        "{\"userName\":\"x\",\"urn:example:unknown:1.0:Thing\":{}}")));
    }

    @Test
    void rejectsTypeMismatches() {
        assertThrows(ScimException.class, () ->
                validator.validate("User", Json.parseObject("{\"userName\":\"x\",\"active\":\"yes\"}")));
        assertThrows(ScimException.class, () ->
                validator.validate("User", Json.parseObject("{\"userName\":\"x\",\"emails\":{}}")));
        assertThrows(ScimException.class, () ->
                validator.validate("User", Json.parseObject(
                        "{\"userName\":\"x\",\"name\":{\"noSuchSub\":\"y\"}}")));
    }

    @Test
    void stripsReadOnlyAndServerAssignedAttributes() {
        JsonObject u = Json.parseObject("""
                {"userName":"x","id":"client-picked","meta":{"resourceType":"User"},
                 "groups":[{"value":"g1"}]}""");
        validator.validate("User", u);
        assertNull(u.get("id"));
        assertNull(u.get("meta"));
        assertNull(u.get("groups"));
    }

    @Test
    void stripNeverReturnedRemovesPassword() {
        JsonObject u = Json.parseObject("{\"userName\":\"x\",\"password\":\"s3cret\"}");
        validator.validate("User", u);
        assertNotNull(u.get("password")); // writeOnly is accepted inbound
        validator.stripNeverReturned("User", u);
        assertNull(u.get("password"));
    }

    @Test
    void configDrivenExtensionValidates() {
        Schema custom = Schema.fromJson(Json.parseObject("""
                {"id":"urn:pointblue:scim:schemas:extension:custom:2.0:User",
                 "name":"PointBlueUser",
                 "attributes":[
                   {"name":"costCenter","type":"string","multiValued":false},
                   {"name":"badges","type":"string","multiValued":true}
                 ]}"""));
        registry.register(custom);
        registry.addExtension("User", custom.id(), false);

        JsonObject u = Json.parseObject("""
                {"userName":"x",
                 "urn:pointblue:scim:schemas:extension:custom:2.0:User":
                   {"costCenter":"CC-9","badges":["red","blue"]}}""");
        validator.validate("User", u);
        JsonValue ext = u.get("urn:pointblue:scim:schemas:extension:custom:2.0:User");
        assertEquals("CC-9", ext.asObject().get("costCenter").asString());

        assertThrows(ScimException.class, () -> validator.validate("User", Json.parseObject("""
                {"userName":"x",
                 "urn:pointblue:scim:schemas:extension:custom:2.0:User":{"nope":true}}""")));
    }

    @Test
    void groupRequiresDisplayName() {
        assertThrows(ScimException.class, () ->
                validator.validate("Group", Json.parseObject("{\"members\":[]}")));
        JsonObject g = Json.parseObject("{\"displayName\":\"Team\"}");
        validator.validate("Group", g);
        assertEquals(CoreSchemas.GROUP_URN, g.get("schemas").asArray().get(0).asString());
    }
}
