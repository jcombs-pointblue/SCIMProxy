package com.pointblue.scim.file;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.FilterEvaluator;
import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.filter.Op;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonBoolean;
import com.pointblue.scim.json.JsonNumber;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.schema.Attribute;
import com.pointblue.scim.schema.SchemaRegistry;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps a flat file record (field -> string/scalar) to and from SCIM shape.
 * File values are stringly typed, so booleans/numbers are coerced using the
 * SCIM attribute type from the registry.
 */
final class FileMapper {

    private record Entry(AttributePath path, String field) {}

    private final String baseUrn;
    private final String resourceType;
    private final SchemaRegistry registry;
    private final Map<String, Entry> byKey = new LinkedHashMap<>();
    private final FilterEvaluator evaluator;

    FileMapper(String baseUrn, String resourceType, Map<String, String> mappings, SchemaRegistry registry) {
        this.baseUrn = baseUrn;
        this.resourceType = resourceType;
        this.registry = registry;
        this.evaluator = new FilterEvaluator(baseUrn, path -> {
            Attribute a = registry.resolve(resourceType, path);
            return a != null && a.caseExact();
        });
        mappings.forEach((scimPath, field) -> {
            AttributePath p = FilterParser.parsePath(scimPath);
            byKey.put(normalize(p), new Entry(p, field));
        });
    }

    FilterEvaluator evaluator() { return evaluator; }

    /** SCIM resource -> flat record (string/scalar per field). includeNulls clears absent mapped fields. */
    JsonObject toRecord(JsonObject resource, boolean includeNulls) {
        JsonObject rec = new JsonObject();
        for (Entry e : byKey.values()) {
            List<JsonValue> values = evaluator.resolve(resource, e.path());
            if (!values.isEmpty()) rec.put(e.field(), scalarString(values.get(0)));
            else if (includeNulls) rec.remove(e.field()); // absent -> not written
        }
        return rec;
    }

    /** Flat record -> SCIM resource, coercing scalar strings to the attribute's type. */
    JsonObject toScim(JsonObject record) {
        JsonObject scim = new JsonObject();
        for (Entry e : byKey.values()) {
            JsonValue raw = record.getIgnoreCase(e.field());
            if (raw == null || raw.isNull()) continue;
            setPath(scim, e.path(), coerce(e.path(), raw));
        }
        JsonArray schemas = new JsonArray().add(baseUrn);
        for (String key : List.copyOf(scim.keys())) {
            if (key.indexOf(':') >= 0) schemas.add(key);
        }
        scim.put("schemas", schemas);
        return scim;
    }

    private JsonValue coerce(AttributePath path, JsonValue raw) {
        Attribute a = registry.resolve(resourceType, path);
        if (a == null || !(raw instanceof JsonString s)) return raw;
        return switch (a.type()) {
            case BOOLEAN -> JsonBoolean.of(Boolean.parseBoolean(s.value().trim()));
            case DECIMAL, INTEGER -> {
                try { yield new JsonNumber(new BigDecimal(s.value().trim())); }
                catch (NumberFormatException nfe) { yield raw; }
            }
            default -> raw;
        };
    }

    private static JsonValue scalarString(JsonValue v) {
        return switch (v) {
            case JsonString s -> s;
            case JsonBoolean b -> new JsonString(b.value() ? "true" : "false");
            case JsonNumber n -> new JsonString(n.value().toPlainString());
            default -> new JsonString(v.toString());
        };
    }

    private void setPath(JsonObject resource, AttributePath path, JsonValue value) {
        JsonObject container = resource;
        if (path.schemaUrn() != null && !path.schemaUrn().equalsIgnoreCase(baseUrn)) {
            if (resource.getIgnoreCase(path.schemaUrn()) instanceof JsonObject o) container = o;
            else { container = new JsonObject(); resource.put(path.schemaUrn(), container); }
        }
        if (path.valueFilter() == null) {
            if (path.subAttr() == null) {
                container.put(path.attr(), value.copy());
            } else {
                JsonObject complex = container.getIgnoreCase(path.attr()) instanceof JsonObject o
                        ? o : new JsonObject();
                complex.put(path.subAttr(), value.copy());
                container.put(container.findKey(path.attr()).orElse(path.attr()), complex);
            }
            return;
        }
        JsonArray arr = container.getIgnoreCase(path.attr()) instanceof JsonArray a ? a : new JsonArray();
        JsonObject element = new JsonObject();
        TreeMap<String, String> eq = new TreeMap<>();
        collectEq(path.valueFilter(), eq);
        eq.forEach(element::put);
        if (path.subAttr() != null) element.put(path.subAttr(), value.copy());
        arr.add(element);
        container.put(container.findKey(path.attr()).orElse(path.attr()), arr);
    }

    private static String normalize(AttributePath p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.schemaUrn() == null ? "" : p.schemaUrn().toLowerCase()).append('|');
        sb.append(p.attr().toLowerCase()).append('|');
        sb.append(p.subAttr() == null ? "" : p.subAttr().toLowerCase()).append('|');
        if (p.valueFilter() != null) {
            TreeMap<String, String> eq = new TreeMap<>();
            collectEq(p.valueFilter(), eq);
            sb.append(eq);
        }
        return sb.toString();
    }

    private static boolean collectEq(Filter f, Map<String, String> into) {
        return switch (f) {
            case Filter.And and -> collectEq(and.left(), into) && collectEq(and.right(), into);
            case Filter.Compare c when c.op() == Op.EQ && c.path().subAttr() == null -> {
                into.put(c.path().attr().toLowerCase(),
                        c.literal().isString() ? c.literal().asString() : c.literal().toString());
                yield true;
            }
            default -> false;
        };
    }
}
