package com.pointblue.scim.json;

import java.math.BigDecimal;

/**
 * Minimal JSON tree model. Objects and arrays are mutable (the PATCH engine
 * edits trees in place); scalars are immutable.
 */
public sealed interface JsonValue
        permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {

    default boolean isNull()   { return this instanceof JsonNull; }
    default boolean isObject() { return this instanceof JsonObject; }
    default boolean isArray()  { return this instanceof JsonArray; }
    default boolean isString() { return this instanceof JsonString; }

    default JsonObject asObject() {
        if (this instanceof JsonObject o) return o;
        throw new JsonException("expected object, got " + typeName());
    }

    default JsonArray asArray() {
        if (this instanceof JsonArray a) return a;
        throw new JsonException("expected array, got " + typeName());
    }

    default String asString() {
        if (this instanceof JsonString s) return s.value();
        throw new JsonException("expected string, got " + typeName());
    }

    default BigDecimal asNumber() {
        if (this instanceof JsonNumber n) return n.value();
        throw new JsonException("expected number, got " + typeName());
    }

    default boolean asBoolean() {
        if (this instanceof JsonBoolean b) return b.value();
        throw new JsonException("expected boolean, got " + typeName());
    }

    default String typeName() {
        return switch (this) {
            case JsonObject o  -> "object";
            case JsonArray a   -> "array";
            case JsonString s  -> "string";
            case JsonNumber n  -> "number";
            case JsonBoolean b -> "boolean";
            case JsonNull n    -> "null";
        };
    }

    /** Deep copy; scalars return themselves. */
    default JsonValue copy() {
        return switch (this) {
            case JsonObject o -> {
                JsonObject c = new JsonObject();
                o.forEach((k, v) -> c.put(k, v.copy()));
                yield c;
            }
            case JsonArray a -> {
                JsonArray c = new JsonArray();
                for (JsonValue v : a) c.add(v.copy());
                yield c;
            }
            default -> this;
        };
    }

    static JsonValue of(String s)     { return s == null ? JsonNull.NULL : new JsonString(s); }
    static JsonValue of(boolean b)    { return JsonBoolean.of(b); }
    static JsonValue of(long n)       { return new JsonNumber(BigDecimal.valueOf(n)); }
    static JsonValue of(BigDecimal n) { return n == null ? JsonNull.NULL : new JsonNumber(n); }
}
