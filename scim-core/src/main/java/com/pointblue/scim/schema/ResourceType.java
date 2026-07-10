package com.pointblue.scim.schema;

import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;

import java.util.List;

/**
 * A resource type binding: base schema plus extensions.
 *
 * @param name       e.g. "User"
 * @param endpoint   e.g. "/Users"
 * @param baseSchema base schema URN
 * @param extensions bound schema extensions
 */
public record ResourceType(String name, String endpoint, String baseSchema, List<Extension> extensions) {

    public record Extension(String schema, boolean required) {}

    public JsonObject toJson(String baseUrl) {
        JsonObject o = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add("urn:ietf:params:scim:schemas:core:2.0:ResourceType");
        o.put("schemas", schemas);
        o.put("id", name);
        o.put("name", name);
        o.put("endpoint", endpoint);
        o.put("schema", baseSchema);
        if (!extensions.isEmpty()) {
            JsonArray exts = new JsonArray();
            for (Extension e : extensions) {
                JsonObject ext = new JsonObject();
                ext.put("schema", e.schema());
                ext.put("required", e.required());
                exts.add(ext);
            }
            o.put("schemaExtensions", exts);
        }
        JsonObject meta = new JsonObject();
        meta.put("resourceType", "ResourceType");
        meta.put("location", baseUrl + "/ResourceTypes/" + name);
        o.put("meta", meta);
        return o;
    }
}
