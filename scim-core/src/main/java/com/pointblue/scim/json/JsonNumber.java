// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.json;

import java.math.BigDecimal;

public record JsonNumber(BigDecimal value) implements JsonValue {
    public JsonNumber {
        if (value == null) throw new JsonException("number value may not be null");
    }
    @Override public boolean equals(Object o) {
        return o instanceof JsonNumber n && value.compareTo(n.value) == 0;
    }
    @Override public int hashCode() { return value.stripTrailingZeros().hashCode(); }
    @Override public String toString() { return Json.write(this); }
}
