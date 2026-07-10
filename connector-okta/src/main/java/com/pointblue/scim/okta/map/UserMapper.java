package com.pointblue.scim.okta.map;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.FilterEvaluator;
import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.filter.Op;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonNull;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.okta.OktaConfig;
import com.pointblue.scim.schema.CoreSchemas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bidirectional SCIM User <-> Okta profile mapping. Ships defaults for the
 * core and enterprise schemas; config entries extend or override them (last
 * wins), including extension attributes and externalId.
 */
public final class UserMapper {

    private static final String ENT = CoreSchemas.ENTERPRISE_USER_URN;

    private static final String[][] DEFAULTS = {
            {"userName", "login"},
            {"externalId", null}, // placeholder: unmapped unless configured
            {"name.givenName", "firstName"},
            {"name.familyName", "lastName"},
            {"name.middleName", "middleName"},
            {"name.honorificPrefix", "honorificPrefix"},
            {"name.honorificSuffix", "honorificSuffix"},
            {"displayName", "displayName"},
            {"nickName", "nickName"},
            {"profileUrl", "profileUrl"},
            {"title", "title"},
            {"userType", "userType"},
            {"preferredLanguage", "preferredLanguage"},
            {"locale", "locale"},
            {"timezone", "timezone"},
            {"emails[type eq \"work\"].value", "email"},
            {"emails[type eq \"home\"].value", "secondEmail"},
            {"phoneNumbers[type eq \"mobile\"].value", "mobilePhone"},
            {"phoneNumbers[type eq \"work\"].value", "primaryPhone"},
            {"addresses[type eq \"work\"].streetAddress", "streetAddress"},
            {"addresses[type eq \"work\"].locality", "city"},
            {"addresses[type eq \"work\"].region", "state"},
            {"addresses[type eq \"work\"].postalCode", "zipCode"},
            {"addresses[type eq \"work\"].country", "countryCode"},
            {ENT + ":employeeNumber", "employeeNumber"},
            {ENT + ":costCenter", "costCenter"},
            {ENT + ":organization", "organization"},
            {ENT + ":division", "division"},
            {ENT + ":department", "department"},
            {ENT + ":manager.value", "managerId"},
            {ENT + ":manager.displayName", "manager"},
    };

    private record Entry(AttributePath path, String oktaAttr) {}

    private final Map<String, Entry> byKey = new LinkedHashMap<>(); // normalized scim path -> entry
    private final FilterEvaluator evaluator =
            new FilterEvaluator(CoreSchemas.USER_URN, FilterEvaluator.CaseExactness.DEFAULT);

    public UserMapper(List<OktaConfig.Mapping> configured) {
        for (String[] d : DEFAULTS) {
            if (d[1] != null) add(d[0], d[1]);
        }
        for (OktaConfig.Mapping m : configured) add(m.scimPath(), m.oktaAttr());
    }

    private void add(String scimPath, String oktaAttr) {
        AttributePath path = FilterParser.parsePath(scimPath);
        byKey.put(normalize(path), new Entry(path, oktaAttr));
    }

    /** Stable case-insensitive key for a path incl. its eq-clause value filter. */
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

    /** Okta profile attribute for a filter/patch path without a value filter; null if unmapped. */
    public String oktaAttrFor(AttributePath path) {
        Entry e = byKey.get(normalize(path));
        return e == null ? null : e.oktaAttr();
    }

    /** Okta search path (profile.x) for a SCIM path; null if unmapped. */
    public String oktaAttrForSearch(AttributePath path) {
        String attr = oktaAttrFor(path);
        return attr == null ? null : "profile." + attr;
    }

    /** SCIM resource -> Okta profile. includeNulls clears mapped-but-absent attributes. */
    public JsonObject toProfile(JsonObject scimUser, boolean includeNulls) {
        JsonObject profile = new JsonObject();
        for (Entry e : byKey.values()) {
            List<JsonValue> values = evaluator.resolve(scimUser, e.path());
            if (!values.isEmpty()) profile.put(e.oktaAttr(), values.get(0).copy());
            else if (includeNulls) profile.put(e.oktaAttr(), JsonNull.NULL);
        }
        return profile;
    }

    /** Okta user object -> SCIM User (id, meta, active, mapped attributes). */
    public JsonObject toScim(JsonObject oktaUser) {
        JsonObject scim = new JsonObject();
        scim.put("id", oktaUser.optString("id", null));
        scim.put("active", OktaStatus.isActive(oktaUser.optString("status", "ACTIVE")));
        JsonValue profileVal = oktaUser.getIgnoreCase("profile");
        JsonObject profile = profileVal instanceof JsonObject p ? p : new JsonObject();
        for (Entry e : byKey.values()) {
            JsonValue v = profile.getIgnoreCase(e.oktaAttr());
            if (v != null && !v.isNull()) setPath(scim, e.path(), v.copy());
        }
        JsonArray schemas = new JsonArray().add(CoreSchemas.USER_URN);
        for (String key : List.copyOf(scim.keys())) {
            if (key.indexOf(':') >= 0) schemas.add(key);
        }
        scim.put("schemas", schemas);
        JsonObject meta = new JsonObject();
        meta.put("resourceType", "User");
        String created = oktaUser.optString("created", null);
        String updated = oktaUser.optString("lastUpdated", null);
        if (created != null) meta.put("created", created);
        if (updated != null) meta.put("lastModified", updated);
        scim.put("meta", meta);
        return scim;
    }

    /** Writes a value at a SCIM path, seeding multi-valued elements from the path's eq clauses. */
    private void setPath(JsonObject resource, AttributePath path, JsonValue value) {
        JsonObject container = resource;
        if (path.schemaUrn() != null && !path.schemaUrn().equalsIgnoreCase(CoreSchemas.USER_URN)) {
            JsonValue ext = resource.getIgnoreCase(path.schemaUrn());
            if (ext instanceof JsonObject o) container = o;
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
}
