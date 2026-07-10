// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.okta.map;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.Op;
import com.pointblue.scim.json.JsonBoolean;
import com.pointblue.scim.json.JsonNumber;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.spi.UnsupportedFilterException;

import java.util.function.Function;

/**
 * Rewrites a SCIM filter AST into Okta's search expression syntax. Throws
 * UnsupportedFilterException for anything Okta search can't express (ne, co,
 * ew, not, valuePath, unmapped attributes) — the core then filters in memory.
 */
public final class FilterTranslator {

    private FilterTranslator() {}

    /**
     * @param pathMapper maps a SCIM attribute path to an Okta search path
     *                   (e.g. profile.login), or null when unmapped
     */
    public static String toOktaSearch(Filter filter, Function<AttributePath, String> pathMapper) {
        return switch (filter) {
            case Filter.And and -> "(" + toOktaSearch(and.left(), pathMapper) + " and "
                    + toOktaSearch(and.right(), pathMapper) + ")";
            case Filter.Or or -> "(" + toOktaSearch(or.left(), pathMapper) + " or "
                    + toOktaSearch(or.right(), pathMapper) + ")";
            case Filter.Not not -> throw unsupported("'not' is not supported by Okta search");
            case Filter.ValuePath vp -> throw unsupported("value-path filters cannot be pushed to Okta");
            case Filter.Present p -> oktaPath(p.path(), pathMapper) + " pr";
            case Filter.Compare c -> {
                String op = switch (c.op()) {
                    case EQ -> "eq";
                    case SW -> "sw";
                    case GT -> "gt";
                    case GE -> "ge";
                    case LT -> "lt";
                    case LE -> "le";
                    case NE, CO, EW -> null;
                };
                if (op == null) throw unsupported("operator " + c.op() + " is not supported by Okta search");
                yield oktaPath(c.path(), pathMapper) + " " + op + " " + literal(c.literal());
            }
        };
    }

    private static String oktaPath(AttributePath path, Function<AttributePath, String> pathMapper) {
        if (path.valueFilter() != null) throw unsupported("value-path filters cannot be pushed to Okta");
        // common attributes that live outside the profile
        if (path.subAttr() == null && path.attr().equalsIgnoreCase("id")) return "id";
        if (path.attr().equalsIgnoreCase("meta")) {
            if ("created".equalsIgnoreCase(path.subAttr())) return "created";
            if ("lastModified".equalsIgnoreCase(path.subAttr())) return "lastUpdated";
            throw unsupported("meta." + path.subAttr() + " is not searchable in Okta");
        }
        String mapped = pathMapper.apply(path);
        if (mapped == null) {
            throw unsupported("attribute " + path.display() + " has no Okta mapping");
        }
        return mapped;
    }

    private static String literal(JsonValue v) {
        return switch (v) {
            case JsonString s -> '"' + s.value().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            case JsonNumber n -> n.value().toPlainString();
            case JsonBoolean b -> String.valueOf(b.value());
            default -> throw unsupported("literal " + v.typeName() + " cannot be pushed to Okta");
        };
    }

    private static UnsupportedFilterException unsupported(String why) {
        return new UnsupportedFilterException(why);
    }
}
