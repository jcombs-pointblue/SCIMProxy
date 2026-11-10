// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.filter;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.CoreSchemas;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FilterEvaluatorTest {

    private final FilterEvaluator eval =
            new FilterEvaluator(CoreSchemas.USER_URN, FilterEvaluator.CaseExactness.DEFAULT);

    private final JsonObject user = Json.parseObject("""
            {
              "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User",
                          "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"],
              "userName": "BJensen",
              "active": true,
              "name": {"familyName": "Jensen", "givenName": "Barbara"},
              "emails": [
                {"value": "bjensen@example.com", "type": "work"},
                {"value": "babs@home.example.org", "type": "home"}
              ],
              "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User": {
                "department": "Tooling"
              }
            }""");

    private boolean matches(String filter) {
        return eval.matches(FilterParser.parse(filter), user);
    }

    @Test
    void equalityIsCaseInsensitiveByDefault() {
        assertTrue(matches("userName eq \"bjensen\""));
        assertFalse(matches("userName eq \"other\""));
    }

    @Test
    void subAttributeAndBooleanAndPresent() {
        assertTrue(matches("name.familyName eq \"Jensen\""));
        assertTrue(matches("active eq true"));
        assertFalse(matches("title pr"));
        assertTrue(matches("userName pr"));
    }

    @Test
    void multiValuedAnyMatchAndValuePath() {
        assertTrue(matches("emails.value co \"example.com\""));
        assertTrue(matches("emails[type eq \"work\" and value sw \"bjensen\"]"));
        assertFalse(matches("emails[type eq \"work\" and value sw \"babs\"]"));
        // complex element compared to a primitive uses its "value" sub-attribute
        assertTrue(matches("emails eq \"bjensen@example.com\""));
    }

    @Test
    void extensionAttributesResolveByUrn() {
        assertTrue(matches(
                "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department eq \"tooling\""));
        // unprefixed extension attribute also resolves through the base-urn container only
        assertFalse(matches(
                "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department eq \"other\""));
    }

    @Test
    void logicalOperators() {
        assertTrue(matches("userName eq \"bjensen\" and active eq true"));
        assertTrue(matches("userName eq \"nobody\" or active eq true"));
        assertTrue(matches("not (userName eq \"nobody\")"));
        assertFalse(matches("userName eq \"bjensen\" and active eq false"));
    }

    @Test
    void ordering() {
        assertTrue(matches("userName gt \"aaa\""));
        assertTrue(matches("userName le \"zzz\""));
        assertFalse(matches("meta.created pr"));
    }

    @Test
    void dateTimesCompareChronologically() {
        JsonObject u = Json.parseObject("""
                {"userName":"t","meta":{"created":"2025-11-16T01:47:42.500Z",
                                        "lastModified":"2025-11-16T01:47:42Z"}}""");
        // lexically "...42.500Z" < "...42Z"; chronologically it is later
        assertTrue(eval.matches(FilterParser.parse("meta.created gt \"2025-11-16T01:47:42Z\""), u));
        assertFalse(eval.matches(FilterParser.parse("meta.created le \"2025-11-16T01:47:42Z\""), u));
        // same instant, different spelling
        assertTrue(eval.matches(FilterParser.parse("meta.lastModified eq \"2025-11-16T01:47:42.000Z\""), u));
        assertTrue(eval.matches(FilterParser.parse("meta.lastModified eq \"2025-11-15T20:47:42-05:00\""), u));
        // bare date is midnight UTC
        assertTrue(eval.matches(FilterParser.parse("meta.created ge \"2025-11-16\""), u));
        assertFalse(eval.matches(FilterParser.parse("meta.created ge \"2025-11-17\""), u));
        // non-date strings still compare lexically
        assertTrue(eval.matches(FilterParser.parse("userName lt \"u\""), u));
    }

    @Test
    void parseDateTimeAcceptsXsdForms() {
        assertNotNull(FilterEvaluator.parseDateTime("2025-11-16T01:47:42Z"));
        assertNotNull(FilterEvaluator.parseDateTime("2025-11-16T01:47:42.123+02:00"));
        assertNotNull(FilterEvaluator.parseDateTime("2025-11-16T01:47:42"));
        assertNotNull(FilterEvaluator.parseDateTime("2025-11-16"));
        assertNull(FilterEvaluator.parseDateTime("bjensen"));
        assertNull(FilterEvaluator.parseDateTime("2025-13-99"));
    }

    @Test
    void caseExactnessIsHonoredWhenSupplied() {
        FilterEvaluator exact = new FilterEvaluator(CoreSchemas.USER_URN, path -> true);
        assertFalse(exact.matches(FilterParser.parse("userName eq \"bjensen\""), user));
        assertTrue(exact.matches(FilterParser.parse("userName eq \"BJensen\""), user));
    }
}
