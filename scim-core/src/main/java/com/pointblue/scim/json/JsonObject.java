// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.json;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/** Insertion-ordered JSON object. */
public final class JsonObject implements JsonValue {

    private final Map<String, JsonValue> members = new LinkedHashMap<>();

    public JsonValue get(String name) { return members.get(name); }

    /** Case-insensitive lookup returning the actual key, per SCIM attribute-name semantics. */
    public Optional<String> findKey(String name) {
        if (members.containsKey(name)) return Optional.of(name);
        for (String k : members.keySet()) {
            if (k.equalsIgnoreCase(name)) return Optional.of(k);
        }
        return Optional.empty();
    }

    public JsonValue getIgnoreCase(String name) {
        return findKey(name).map(members::get).orElse(null);
    }

    public JsonObject put(String name, JsonValue value) {
        members.put(name, value == null ? JsonNull.NULL : value);
        return this;
    }

    public JsonObject put(String name, String value)     { return put(name, JsonValue.of(value)); }
    public JsonObject put(String name, boolean value)    { return put(name, JsonValue.of(value)); }
    public JsonObject put(String name, long value)       { return put(name, JsonValue.of(value)); }
    public JsonObject put(String name, BigDecimal value) { return put(name, JsonValue.of(value)); }

    public JsonValue remove(String name) { return members.remove(name); }

    public JsonValue removeIgnoreCase(String name) {
        return findKey(name).map(members::remove).orElse(null);
    }

    public boolean has(String name) { return members.containsKey(name); }
    public int size() { return members.size(); }
    public boolean isEmpty() { return members.isEmpty(); }
    public Set<String> keys() { return members.keySet(); }
    public void forEach(BiConsumer<String, JsonValue> action) { members.forEach(action); }

    public String optString(String name, String fallback) {
        JsonValue v = getIgnoreCase(name);
        return (v instanceof JsonString s) ? s.value() : fallback;
    }

    public boolean optBoolean(String name, boolean fallback) {
        JsonValue v = getIgnoreCase(name);
        return (v instanceof JsonBoolean b) ? b.value() : fallback;
    }

    @Override public String toString() { return Json.write(this); }

    @Override public boolean equals(Object o) {
        return o instanceof JsonObject other && members.equals(other.members);
    }

    @Override public int hashCode() { return members.hashCode(); }
}
