// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.json;

public record JsonString(String value) implements JsonValue {
    public JsonString {
        if (value == null) throw new JsonException("string value may not be null");
    }
    @Override public String toString() { return Json.write(this); }
}
