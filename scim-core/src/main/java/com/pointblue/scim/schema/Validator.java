package com.pointblue.scim.schema;

import com.pointblue.scim.ScimException;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonBoolean;
import com.pointblue.scim.json.JsonNumber;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates and normalizes inbound resources against the registry, and strips
 * returned=never attributes on the way out. Connectors never do protocol
 * validation themselves.
 */
public final class Validator {

    private final SchemaRegistry registry;

    public Validator(SchemaRegistry registry) { this.registry = registry; }

    /**
     * Validates a resource for create/replace. Mutates the payload:
     * read-only attributes and namespaces are dropped, the schemas list is
     * normalized to the namespaces actually present.
     */
    public void validate(String resourceTypeName, JsonObject resource) {
        ResourceType rt = registry.resourceType(resourceTypeName);
        if (rt == null) throw new IllegalArgumentException("unknown resource type " + resourceTypeName);

        // common read-only attributes are server-assigned
        resource.removeIgnoreCase("meta");
        resource.removeIgnoreCase("id");

        List<String> present = new ArrayList<>();
        present.add(rt.baseSchema());

        for (String key : List.copyOf(resource.keys())) {
            if (key.equalsIgnoreCase("schemas") || key.equalsIgnoreCase("externalId")) continue;
            if (key.indexOf(':') >= 0) {
                if (!registry.isSchemaOf(rt, key)) {
                    throw ScimException.invalidValue("unknown schema extension namespace: " + key);
                }
                JsonValue extVal = resource.get(key);
                if (!(extVal instanceof JsonObject extObj)) {
                    throw ScimException.invalidValue("extension namespace " + key + " must be a JSON object");
                }
                Schema ext = registry.schema(key);
                validateAgainstSchema(ext, extObj, key);
                if (extObj.isEmpty()) resource.remove(key);
                else present.add(canonicalUrn(rt, key));
            }
        }
        Schema base = registry.schema(rt.baseSchema());
        validateAgainstSchema(base, resource, null);

        for (ResourceType.Extension e : rt.extensions()) {
            if (e.required() && resource.findKey(e.schema()).isEmpty()) {
                throw ScimException.invalidValue("required schema extension missing: " + e.schema());
            }
        }

        JsonArray schemas = new JsonArray();
        for (String urn : present) schemas.add(urn);
        resource.put("schemas", schemas);
    }

    private String canonicalUrn(ResourceType rt, String key) {
        for (ResourceType.Extension e : rt.extensions()) {
            if (e.schema().equalsIgnoreCase(key)) return e.schema();
        }
        return key;
    }

    private void validateAgainstSchema(Schema schema, JsonObject container, String namespace) {
        // unknown attributes and per-attribute checks
        for (String key : List.copyOf(container.keys())) {
            if (namespace == null) {
                if (key.equalsIgnoreCase("schemas") || key.equalsIgnoreCase("externalId")) continue;
                if (key.indexOf(':') >= 0) continue; // extension namespace, validated separately
            }
            Attribute attr = schema.attribute(key);
            if (attr == null) {
                throw ScimException.invalidValue("unknown attribute '" + key + "'"
                        + (namespace != null ? " in " + namespace : ""));
            }
            if (attr.mutability() == Attribute.Mutability.READ_ONLY) {
                container.remove(key);
                continue;
            }
            JsonValue value = container.get(key);
            if (value.isNull()) { container.remove(key); continue; }
            checkValue(attr, value, key);
        }
        // required attributes
        for (Attribute attr : schema.attributes()) {
            if (attr.required() && attr.mutability() != Attribute.Mutability.READ_ONLY) {
                JsonValue v = container.getIgnoreCase(attr.name());
                if (v == null || v.isNull() || (v instanceof JsonString s && s.value().isEmpty())) {
                    throw ScimException.invalidValue("missing required attribute '" + attr.name() + "'"
                            + (namespace != null ? " in " + namespace : ""));
                }
            }
        }
    }

    private void checkValue(Attribute attr, JsonValue value, String where) {
        if (attr.multiValued()) {
            if (!(value instanceof JsonArray arr)) {
                throw ScimException.invalidValue("'" + where + "' must be an array");
            }
            for (JsonValue e : arr) checkSingle(attr, e, where);
        } else {
            checkSingle(attr, value, where);
        }
    }

    private void checkSingle(Attribute attr, JsonValue value, String where) {
        if (value.isNull()) return;
        switch (attr.type()) {
            case STRING, BINARY -> requireString(value, where);
            case REFERENCE -> requireString(value, where);
            case DATETIME -> {
                String s = requireString(value, where);
                try {
                    OffsetDateTime.parse(s);
                } catch (DateTimeParseException e) {
                    throw ScimException.invalidValue("'" + where + "' is not a valid xsd:dateTime: " + s);
                }
            }
            case BOOLEAN -> {
                if (!(value instanceof JsonBoolean)) {
                    throw ScimException.invalidValue("'" + where + "' must be a boolean");
                }
            }
            case DECIMAL, INTEGER -> {
                if (!(value instanceof JsonNumber n)) {
                    throw ScimException.invalidValue("'" + where + "' must be a number");
                }
                if (attr.type() == Attribute.AttributeType.INTEGER && n.value().scale() > 0
                        && n.value().stripTrailingZeros().scale() > 0) {
                    throw ScimException.invalidValue("'" + where + "' must be an integer");
                }
            }
            case COMPLEX -> {
                if (!(value instanceof JsonObject obj)) {
                    throw ScimException.invalidValue("'" + where + "' must be an object");
                }
                for (String subKey : List.copyOf(obj.keys())) {
                    Attribute sub = attr.subAttribute(subKey);
                    if (sub == null) {
                        throw ScimException.invalidValue("unknown sub-attribute '" + where + "." + subKey + "'");
                    }
                    JsonValue subVal = obj.get(subKey);
                    if (subVal.isNull()) { obj.remove(subKey); continue; }
                    checkValue(sub, subVal, where + "." + subKey);
                }
            }
        }
    }

    private String requireString(JsonValue value, String where) {
        if (value instanceof JsonString s) return s.value();
        throw ScimException.invalidValue("'" + where + "' must be a string");
    }

    /** Removes returned=never attributes (e.g. password) before a resource leaves the server. */
    public void stripNeverReturned(String resourceTypeName, JsonObject resource) {
        ResourceType rt = registry.resourceType(resourceTypeName);
        if (rt == null) return;
        stripNever(registry.schema(rt.baseSchema()), resource);
        for (ResourceType.Extension e : rt.extensions()) {
            if (resource.getIgnoreCase(e.schema()) instanceof JsonObject extObj) {
                stripNever(registry.schema(e.schema()), extObj);
            }
        }
    }

    private void stripNever(Schema schema, JsonObject container) {
        if (schema == null) return;
        for (Attribute attr : schema.attributes()) {
            if (attr.returned() == Attribute.Returned.NEVER) {
                container.removeIgnoreCase(attr.name());
            }
        }
    }
}
