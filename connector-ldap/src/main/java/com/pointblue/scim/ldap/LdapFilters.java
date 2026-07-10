// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.ldap;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.spi.UnsupportedFilterException;

import java.util.function.Function;

/**
 * SCIM filter AST -> RFC 4515 LDAP search filter. Value-path filters and
 * unmapped attributes throw UnsupportedFilterException so the core falls back
 * to in-memory evaluation.
 */
final class LdapFilters {

    private LdapFilters() {}

    static String toLdap(Filter filter, Function<AttributePath, String> attrMapper) {
        return switch (filter) {
            case Filter.And and -> "(&" + toLdap(and.left(), attrMapper) + toLdap(and.right(), attrMapper) + ")";
            case Filter.Or or -> "(|" + toLdap(or.left(), attrMapper) + toLdap(or.right(), attrMapper) + ")";
            case Filter.Not not -> "(!" + toLdap(not.inner(), attrMapper) + ")";
            case Filter.ValuePath vp -> throw new UnsupportedFilterException(
                    "value-path filters cannot be translated to LDAP");
            case Filter.Present p -> "(" + attr(p.path(), attrMapper) + "=*)";
            case Filter.Compare c -> {
                String a = attr(c.path(), attrMapper);
                String v = escape(literal(c.literal()));
                yield switch (c.op()) {
                    case EQ -> "(" + a + "=" + v + ")";
                    case NE -> "(!(" + a + "=" + v + "))";
                    case CO -> "(" + a + "=*" + v + "*)";
                    case SW -> "(" + a + "=" + v + "*)";
                    case EW -> "(" + a + "=*" + v + ")";
                    case GE -> "(" + a + ">=" + v + ")";
                    case LE -> "(" + a + "<=" + v + ")";
                    case GT -> "(&(" + a + ">=" + v + ")(!(" + a + "=" + v + ")))";
                    case LT -> "(&(" + a + "<=" + v + ")(!(" + a + "=" + v + ")))";
                };
            }
        };
    }

    private static String attr(AttributePath path, Function<AttributePath, String> attrMapper) {
        if (path.valueFilter() != null) {
            throw new UnsupportedFilterException("value-path filters cannot be translated to LDAP");
        }
        String mapped = attrMapper.apply(path);
        if (mapped == null) {
            throw new UnsupportedFilterException("attribute " + path.display() + " has no LDAP mapping");
        }
        return mapped;
    }

    private static String literal(JsonValue v) {
        String s = LdapMapper.ldapString(v);
        if (s == null) throw new UnsupportedFilterException("literal " + v.typeName() + " not usable in LDAP");
        return s;
    }

    /** RFC 4515 escaping for filter values. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\\' -> sb.append("\\5c");
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Escapes raw bytes (binary GUIDs) for use in a filter. */
    static String escapeBytes(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("\\%02x", b));
        return sb.toString();
    }
}
