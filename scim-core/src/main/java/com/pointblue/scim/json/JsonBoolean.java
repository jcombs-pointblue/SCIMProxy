// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.json;

public record JsonBoolean(boolean value) implements JsonValue {
    public static final JsonBoolean TRUE = new JsonBoolean(true);
    public static final JsonBoolean FALSE = new JsonBoolean(false);
    public static JsonBoolean of(boolean b) { return b ? TRUE : FALSE; }
    @Override public String toString() { return Json.write(this); }
}
