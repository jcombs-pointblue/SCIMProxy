// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.json;

public final class JsonNull implements JsonValue {
    public static final JsonNull NULL = new JsonNull();
    private JsonNull() {}
    @Override public String toString() { return "null"; }
}
