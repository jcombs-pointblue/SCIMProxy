// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.json;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void roundTripsNestedStructure() {
        String text = """
                {"a":[1,2.5,-3e2],"b":{"c":"x","d":true,"e":null},"f":"q\\"uote\\\\slash\\n"}""";
        JsonValue v = Json.parse(text);
        assertEquals(v, Json.parse(Json.write(v)));
        JsonObject o = v.asObject();
        assertEquals(new BigDecimal("-3e2"), o.get("a").asArray().get(2).asNumber());
        assertEquals("q\"uote\\slash\n", o.get("f").asString());
        assertTrue(o.get("b").asObject().get("e").isNull());
    }

    @Test
    void escapesControlCharactersOnOutput() {
        String s = "tab" + (char) 9 + "ctl" + (char) 1;
        JsonObject o = new JsonObject().put("s", s);
        assertEquals("{" + (char) 34 + "s" + (char) 34 + ":" + (char) 34
                + "tab" + (char) 92 + "tctl" + (char) 92 + "u0001" + (char) 34 + "}", Json.write(o));
    }

    @Test
    void parsesUnicodeEscapes() {
        assertEquals("héllo☃", Json.parse("\"h\\u00e9llo\\u2603\"").asString());
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,]"));
        assertThrows(JsonException.class, () -> Json.parse("{} extra"));
        assertThrows(JsonException.class, () -> Json.parse("01"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
    }

    @Test
    void rejectsExcessiveNesting() {
        String deep = "[".repeat(100) + "]".repeat(100);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    @Test
    void objectKeysAreCaseInsensitiveOnLookupButPreserved() {
        JsonObject o = Json.parseObject("{\"userName\":\"jdoe\"}");
        assertEquals("jdoe", o.getIgnoreCase("username").asString());
        assertEquals("userName", o.findKey("USERNAME").orElseThrow());
    }
}
