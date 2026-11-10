// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.filter;

import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonBoolean;
import com.pointblue.scim.json.JsonNumber;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a filter AST against a resource's JSON representation. Used by
 * in-memory search fallback, valuePath matching in PATCH, and multi-valued
 * element selection.
 */
public final class FilterEvaluator {

    /** Supplies caseExact for a path; SCIM defaults to case-insensitive string matching. */
    public interface CaseExactness {
        boolean isCaseExact(AttributePath path);
        CaseExactness DEFAULT = path -> false;
    }

    private final String baseUrn;
    private final CaseExactness caseExactness;

    public FilterEvaluator(String baseUrn, CaseExactness caseExactness) {
        this.baseUrn = baseUrn;
        this.caseExactness = caseExactness == null ? CaseExactness.DEFAULT : caseExactness;
    }

    public boolean matches(Filter filter, JsonObject resource) {
        return switch (filter) {
            case Filter.And and -> matches(and.left(), resource) && matches(and.right(), resource);
            case Filter.Or or -> matches(or.left(), resource) || matches(or.right(), resource);
            case Filter.Not not -> !matches(not.inner(), resource);
            case Filter.Present p -> anyPresent(resolve(resource, p.path()));
            case Filter.Compare c -> {
                boolean caseExact = caseExactness.isCaseExact(c.path());
                for (JsonValue v : resolve(resource, c.path())) {
                    if (compare(v, c.op(), c.literal(), caseExact)) yield true;
                }
                yield false;
            }
            case Filter.ValuePath vp -> {
                for (JsonValue element : resolve(resource, vp.path())) {
                    if (element instanceof JsonObject o && matchesElement(vp.inner(), o)) yield true;
                }
                yield false;
            }
        };
    }

    /** Evaluates a valueFilter against one element of a multi-valued attribute. */
    public boolean matchesElement(Filter filter, JsonObject element) {
        return matches(filter, element);
    }

    /**
     * Collects the value(s) addressed by a path within a resource. Multi-valued
     * attributes are flattened to their elements; a value filter narrows elements;
     * a sub-attribute projects into complex values. Missing values yield an empty list.
     */
    public List<JsonValue> resolve(JsonObject resource, AttributePath path) {
        JsonObject container = container(resource, path);
        if (container == null) return List.of();
        JsonValue v = container.getIgnoreCase(path.attr());
        if (v == null || v.isNull()) return List.of();

        List<JsonValue> values = new ArrayList<>();
        if (v instanceof JsonArray arr) {
            for (JsonValue e : arr) values.add(e);
        } else {
            values.add(v);
        }

        if (path.valueFilter() != null) {
            values.removeIf(e -> !(e instanceof JsonObject o) || !matchesElement(path.valueFilter(), o));
        }
        if (path.subAttr() != null) {
            List<JsonValue> projected = new ArrayList<>();
            for (JsonValue e : values) {
                if (e instanceof JsonObject o) {
                    JsonValue sub = o.getIgnoreCase(path.subAttr());
                    if (sub != null && !sub.isNull()) projected.add(sub);
                }
            }
            return projected;
        }
        return values;
    }

    /** The object holding the attribute: the resource itself or an extension namespace object. */
    public JsonObject container(JsonObject resource, AttributePath path) {
        if (path.schemaUrn() == null || path.schemaUrn().equalsIgnoreCase(baseUrn)) return resource;
        JsonValue ext = resource.getIgnoreCase(path.schemaUrn());
        return (ext instanceof JsonObject o) ? o : null;
    }

    private static boolean anyPresent(List<JsonValue> values) {
        for (JsonValue v : values) {
            if (v.isNull()) continue;
            if (v instanceof JsonString s && s.value().isEmpty()) continue;
            if (v instanceof JsonArray a && a.isEmpty()) continue;
            if (v instanceof JsonObject o && o.isEmpty()) continue;
            return true;
        }
        return false;
    }

    /** Compares a single value against a literal. Type mismatches do not match (except ne). */
    public static boolean compare(JsonValue value, Op op, JsonValue literal, boolean caseExact) {
        // complex element compared to a primitive: SCIM convention is to compare its "value"
        if (value instanceof JsonObject o && !(literal instanceof JsonObject)) {
            JsonValue inner = o.getIgnoreCase("value");
            if (inner == null) return op == Op.NE;
            value = inner;
        }
        if (op == Op.NE) return !compare(value, Op.EQ, literal, caseExact);
        if (literal.isNull()) return op == Op.EQ && value.isNull();

        if (value instanceof JsonString s && literal instanceof JsonString l) {
            if (op == Op.EQ || op == Op.GT || op == Op.GE || op == Op.LT || op == Op.LE) {
                // xsd:dateTime values compare chronologically, not lexically:
                // "...42.5Z" is after "...42Z", and "+00:00" equals "Z"
                Instant ta = parseDateTime(s.value());
                Instant tb = ta == null ? null : parseDateTime(l.value());
                if (tb != null) {
                    int cmp = ta.compareTo(tb);
                    return switch (op) {
                        case EQ -> cmp == 0;
                        case GT -> cmp > 0;
                        case GE -> cmp >= 0;
                        case LT -> cmp < 0;
                        default -> cmp <= 0;
                    };
                }
            }
            String a = s.value(), b = l.value();
            if (!caseExact) { a = a.toLowerCase(); b = b.toLowerCase(); }
            return switch (op) {
                case EQ -> a.equals(b);
                case CO -> a.contains(b);
                case SW -> a.startsWith(b);
                case EW -> a.endsWith(b);
                case GT -> a.compareTo(b) > 0;
                case GE -> a.compareTo(b) >= 0;
                case LT -> a.compareTo(b) < 0;
                case LE -> a.compareTo(b) <= 0;
                default -> false;
            };
        }
        if (value instanceof JsonNumber n && literal instanceof JsonNumber l) {
            int cmp = n.value().compareTo(l.value());
            return switch (op) {
                case EQ -> cmp == 0;
                case GT -> cmp > 0;
                case GE -> cmp >= 0;
                case LT -> cmp < 0;
                case LE -> cmp <= 0;
                default -> false;
            };
        }
        if (value instanceof JsonBoolean b && literal instanceof JsonBoolean l) {
            return op == Op.EQ && b.value() == l.value();
        }
        return false;
    }

    /**
     * Parses an RFC 7643 dateTime (xsd:dateTime) to an instant, or null when the
     * string is not one. Accepts an offset or "Z"; a value with no offset is read
     * as UTC, and a bare date (2025-11-16) as midnight UTC.
     */
    public static Instant parseDateTime(String s) {
        if (s == null || s.length() < 10 || !looksLikeDate(s)) return null;
        try {
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /** Cheap shape check (yyyy-mm-dd prefix) so ordinary strings skip the parser. */
    private static boolean looksLikeDate(String s) {
        for (int i = 0; i < 10; i++) {
            char c = s.charAt(i);
            boolean ok = (i == 4 || i == 7) ? c == '-' : (c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return s.length() == 10 || s.charAt(10) == 'T' || s.charAt(10) == 't';
    }
}
