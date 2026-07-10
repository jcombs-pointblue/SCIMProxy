// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.schema;

import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;

import java.util.ArrayList;
import java.util.List;

/** An RFC 7643 schema definition (core or extension). */
public record Schema(String id, String name, String description, List<Attribute> attributes) {

    public Attribute attribute(String attrName) {
        for (Attribute a : attributes) {
            if (a.name().equalsIgnoreCase(attrName)) return a;
        }
        return null;
    }

    /** Representation served at /Schemas/{id}. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add("urn:ietf:params:scim:schemas:core:2.0:Schema");
        o.put("schemas", schemas);
        o.put("id", id);
        o.put("name", name);
        o.put("description", description);
        JsonArray attrs = new JsonArray();
        for (Attribute a : attributes) attrs.add(a.toJson());
        o.put("attributes", attrs);
        JsonObject meta = new JsonObject();
        meta.put("resourceType", "Schema");
        o.put("meta", meta);
        return o;
    }

    /** Parses a schema definition file (same shape as the /Schemas representation). */
    public static Schema fromJson(JsonObject o) {
        String id = o.optString("id", null);
        if (id == null) throw new IllegalArgumentException("schema definition missing 'id'");
        String name = o.optString("name", id.substring(id.lastIndexOf(':') + 1));
        List<Attribute> attrs = new ArrayList<>();
        if (o.get("attributes") instanceof JsonArray arr) {
            for (JsonValue v : arr) attrs.add(Attribute.fromJson(v.asObject()));
        }
        return new Schema(id, name, o.optString("description", ""), List.copyOf(attrs));
    }
}
