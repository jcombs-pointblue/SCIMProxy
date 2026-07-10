// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.ldap;

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
import com.pointblue.scim.schema.CoreSchemas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bidirectional SCIM User <-> LDAP attribute mapping, driven entirely by the
 * flavor-defaulted mapping table in LdapConfig. All LDAP values are treated
 * as strings on the wire (booleans render as TRUE/FALSE).
 */
final class LdapMapper {

    private record Entry(AttributePath path, String ldapAttr) {}

    private final Map<String, Entry> byKey = new LinkedHashMap<>();
    private final FilterEvaluator evaluator =
            new FilterEvaluator(CoreSchemas.USER_URN, FilterEvaluator.CaseExactness.DEFAULT);

    LdapMapper(List<LdapConfig.Mapping> mappings) {
        for (LdapConfig.Mapping m : mappings) {
            AttributePath path = FilterParser.parsePath(m.scimPath());
            byKey.put(normalize(path), new Entry(path, m.ldapAttr()));
        }
    }

    /** LDAP attribute for a filter/patch path (no value filter); null if unmapped. */
    String ldapAttrFor(AttributePath path) {
        Entry e = byKey.get(normalize(path));
        return e == null ? null : e.ldapAttr();
    }

    /**
     * SCIM resource -> LDAP attribute values. Entries mapped to an absent SCIM
     * value appear with a null value (meaning: clear) when includeNulls is set.
     */
    Map<String, String> toLdap(JsonObject scimUser, boolean includeNulls) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : byKey.values()) {
            List<JsonValue> values = evaluator.resolve(scimUser, e.path());
            if (!values.isEmpty()) out.put(e.ldapAttr(), ldapString(values.get(0)));
            else if (includeNulls) out.put(e.ldapAttr(), null);
        }
        return out;
    }

    /** LDAP entry attributes (first value each) -> SCIM shape (no id/meta/active). */
    JsonObject toScim(Map<String, String> ldapAttrs) {
        JsonObject scim = new JsonObject();
        for (Entry e : byKey.values()) {
            String v = null;
            for (Map.Entry<String, String> a : ldapAttrs.entrySet()) {
                if (a.getKey().equalsIgnoreCase(e.ldapAttr())) { v = a.getValue(); break; }
            }
            if (v != null) setPath(scim, e.path(), new JsonString(v));
        }
        JsonArray schemas = new JsonArray().add(CoreSchemas.USER_URN);
        for (String key : List.copyOf(scim.keys())) {
            if (key.indexOf(':') >= 0) schemas.add(key);
        }
        scim.put("schemas", schemas);
        return scim;
    }

    static String ldapString(JsonValue v) {
        return switch (v) {
            case JsonString s -> s.value();
            case JsonNumber n -> n.value().toPlainString();
            case JsonBoolean b -> b.value() ? "TRUE" : "FALSE";
            default -> null;
        };
    }

    private void setPath(JsonObject resource, AttributePath path, JsonValue value) {
        JsonObject container = resource;
        if (path.schemaUrn() != null && !path.schemaUrn().equalsIgnoreCase(CoreSchemas.USER_URN)) {
            if (resource.getIgnoreCase(path.schemaUrn()) instanceof JsonObject o) container = o;
            else {
                container = new JsonObject();
                resource.put(path.schemaUrn(), container);
            }
        }
        if (path.valueFilter() == null) {
            if (path.subAttr() == null) {
                container.put(path.attr(), value);
            } else {
                JsonObject complex = container.getIgnoreCase(path.attr()) instanceof JsonObject o
                        ? o : new JsonObject();
                complex.put(path.subAttr(), value);
                container.put(container.findKey(path.attr()).orElse(path.attr()), complex);
            }
            return;
        }
        JsonArray arr = container.getIgnoreCase(path.attr()) instanceof JsonArray a ? a : new JsonArray();
        JsonObject element = null;
        for (JsonValue e : arr) {
            if (e instanceof JsonObject o && evaluator.matchesElement(path.valueFilter(), o)) {
                element = o;
                break;
            }
        }
        if (element == null) {
            element = new JsonObject();
            TreeMap<String, String> eq = new TreeMap<>();
            collectEq(path.valueFilter(), eq);
            eq.forEach(element::put);
            arr.add(element);
        }
        if (path.subAttr() != null) element.put(path.subAttr(), value);
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
                        c.literal().isString() ? c.literal().asString().toLowerCase() : c.literal().toString());
                yield true;
            }
            default -> false;
        };
    }
}
