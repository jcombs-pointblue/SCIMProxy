package com.pointblue.scim.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Mutable JSON array. */
public final class JsonArray implements JsonValue, Iterable<JsonValue> {

    private final List<JsonValue> elements = new ArrayList<>();

    public JsonArray add(JsonValue v) {
        elements.add(v == null ? JsonNull.NULL : v);
        return this;
    }

    public JsonArray add(String s) { return add(JsonValue.of(s)); }

    public JsonValue get(int i) { return elements.get(i); }
    public void set(int i, JsonValue v) { elements.set(i, v == null ? JsonNull.NULL : v); }
    public JsonValue removeAt(int i) { return elements.remove(i); }
    public int size() { return elements.size(); }
    public boolean isEmpty() { return elements.isEmpty(); }

    @Override public Iterator<JsonValue> iterator() { return elements.iterator(); }

    public List<JsonValue> list() { return elements; }

    @Override public String toString() { return Json.write(this); }

    @Override public boolean equals(Object o) {
        return o instanceof JsonArray other && elements.equals(other.elements);
    }

    @Override public int hashCode() { return elements.hashCode(); }
}
