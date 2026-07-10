// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.json;

/**
 * Facade for JSON parsing and writing. The rest of the codebase goes through
 * this class only, so the implementation could be swapped for a library
 * without touching callers.
 */
public final class Json {

    /** Guards against hostile payloads; request-size limits are enforced at the HTTP layer. */
    public static final int MAX_DEPTH = 64;

    private Json() {}

    public static JsonValue parse(String text) {
        return new JsonParser(text).parseDocument();
    }

    public static JsonObject parseObject(String text) {
        JsonValue v = parse(text);
        if (v instanceof JsonObject o) return o;
        throw new JsonException("expected a JSON object at top level, got " + v.typeName());
    }

    public static String write(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        JsonWriter.write(value, sb, -1, 0);
        return sb.toString();
    }

    public static String writePretty(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        JsonWriter.write(value, sb, 2, 0);
        return sb.toString();
    }
}
