package com.pointblue.scim.filter;

import com.pointblue.scim.ScimException;
import com.pointblue.scim.model.AttributePath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FilterParserTest {

    @Test
    void parsesSimpleEquality() {
        Filter f = FilterParser.parse("userName eq \"bjensen\"");
        Filter.Compare c = assertInstanceOf(Filter.Compare.class, f);
        assertEquals("userName", c.path().attr());
        assertEquals(Op.EQ, c.op());
        assertEquals("bjensen", c.literal().asString());
    }

    @Test
    void parsesUrnPrefixedPath() {
        Filter f = FilterParser.parse(
                "urn:ietf:params:scim:schemas:core:2.0:User:userName sw \"J\"");
        Filter.Compare c = assertInstanceOf(Filter.Compare.class, f);
        assertEquals("urn:ietf:params:scim:schemas:core:2.0:User", c.path().schemaUrn());
        assertEquals("userName", c.path().attr());
        assertEquals(Op.SW, c.op());
    }

    @Test
    void parsesSubAttributeAndPresent() {
        Filter f = FilterParser.parse("name.familyName co \"O'Malley\"");
        Filter.Compare c = assertInstanceOf(Filter.Compare.class, f);
        assertEquals("name", c.path().attr());
        assertEquals("familyName", c.path().subAttr());

        assertInstanceOf(Filter.Present.class, FilterParser.parse("title pr"));
    }

    @Test
    void andBindsTighterThanOr() {
        Filter f = FilterParser.parse("title pr and userType eq \"Employee\" or userType eq \"Intern\"");
        Filter.Or or = assertInstanceOf(Filter.Or.class, f);
        assertInstanceOf(Filter.And.class, or.left());
    }

    @Test
    void parsesNotAndGrouping() {
        Filter f = FilterParser.parse(
                "userType ne \"Employee\" and not (emails co \"example.com\" or emails.value co \"example.org\")");
        Filter.And and = assertInstanceOf(Filter.And.class, f);
        Filter.Not not = assertInstanceOf(Filter.Not.class, and.right());
        assertInstanceOf(Filter.Or.class, not.inner());
    }

    @Test
    void parsesValuePathFilter() {
        Filter f = FilterParser.parse("emails[type eq \"work\" and value co \"@example.com\"]");
        Filter.ValuePath vp = assertInstanceOf(Filter.ValuePath.class, f);
        assertEquals("emails", vp.path().attr());
        assertInstanceOf(Filter.And.class, vp.inner());
    }

    @Test
    void parsesNonStringLiterals() {
        Filter.Compare active = assertInstanceOf(Filter.Compare.class, FilterParser.parse("active eq true"));
        assertTrue(active.literal().asBoolean());
        Filter.Compare num = assertInstanceOf(Filter.Compare.class, FilterParser.parse("weight gt 2.5"));
        assertEquals(0, num.literal().asNumber().compareTo(new java.math.BigDecimal("2.5")));
    }

    @Test
    void parsesPatchPaths() {
        AttributePath p1 = FilterParser.parsePath("members[value eq \"2819c223\"]");
        assertEquals("members", p1.attr());
        assertNotNull(p1.valueFilter());
        assertNull(p1.subAttr());

        AttributePath p2 = FilterParser.parsePath("addresses[type eq \"work\"].streetAddress");
        assertEquals("streetAddress", p2.subAttr());

        AttributePath p3 = FilterParser.parsePath(
                "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager.displayName");
        assertEquals("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User", p3.schemaUrn());
        assertEquals("manager", p3.attr());
        assertEquals("displayName", p3.subAttr());
    }

    @Test
    void rejectsInvalidFilters() {
        assertThrows(ScimException.class, () -> FilterParser.parse("userName"));
        assertThrows(ScimException.class, () -> FilterParser.parse("userName xx \"a\""));
        assertThrows(ScimException.class, () -> FilterParser.parse("userName eq \"a\" trailing"));
        assertThrows(ScimException.class, () -> FilterParser.parse("emails[type eq \"work\""));
        assertThrows(ScimException.class, () -> FilterParser.parse(""));
    }
}
