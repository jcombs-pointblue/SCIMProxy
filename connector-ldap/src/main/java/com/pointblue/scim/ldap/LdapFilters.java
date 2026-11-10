// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.ldap;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.FilterEvaluator;
import com.pointblue.scim.filter.Op;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.UnsupportedFilterException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SCIM filter AST -> RFC 4515 LDAP search filter. Unmapped attributes and
 * value-path filters that can't be expressed throw UnsupportedFilterException
 * so the core falls back to in-memory evaluation.
 *
 * A multi-valued attribute mapped per element (emails[type eq "work"].value ->
 * mail) is addressable the ways clients actually filter it: emails.value,
 * emails (compares the element's value), and value paths such as
 * emails[type eq "work" and value eq "x"]. Each mapped element contributes one
 * branch of an OR; sub-attributes fixed by the mapping's value filter (type)
 * are folded to constants.
 */
final class LdapFilters {

    static final String TRUE = "(objectClass=*)";
    static final String FALSE = "(!(objectClass=*))";

    /** Generalized-time syntax used when filtering a timestamp attribute. */
    enum TimeSyntax {
        NONE,
        /** 20251116014742Z — eDirectory, OpenLDAP, 389-ds. */
        WHOLE_SECONDS,
        /** 20251116014742.0Z — Active Directory (whenCreated / whenChanged). */
        FRACTION
    }

    /**
     * Where a SCIM path's value comes from on the LDAP side: an attribute, or a
     * constant fixed by a mapping's value filter (with the filter that tests
     * whether that element exists at all).
     */
    record Target(String ldapAttr, JsonValue constant, String presence, TimeSyntax time) {
        static Target attr(String ldapAttr) { return new Target(ldapAttr, null, null, TimeSyntax.NONE); }
        static Target time(String ldapAttr, TimeSyntax syntax) { return new Target(ldapAttr, null, null, syntax); }
        static Target constant(JsonValue value, String presence) {
            return new Target(null, value, presence, TimeSyntax.NONE);
        }
    }

    /** Supplies the LDAP side of SCIM paths. */
    interface Resolver {
        /** Exact mapping for a path without a value filter, or null. */
        Target exact(AttributePath path);

        /**
         * Mapped element shapes of the multi-valued attribute at path (schema +
         * attr; any sub-attribute is ignored). Each map is lowercase
         * sub-attribute -> target. Empty when the attribute has none.
         */
        default List<Map<String, Target>> elements(AttributePath path) { return List.of(); }
    }

    private static final DateTimeFormatter GENERALIZED =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private LdapFilters() {}

    static String toLdap(Filter filter, Resolver resolver) {
        return switch (filter) {
            case Filter.And and -> and(toLdap(and.left(), resolver), toLdap(and.right(), resolver));
            case Filter.Or or -> or(List.of(toLdap(or.left(), resolver), toLdap(or.right(), resolver)));
            case Filter.Not not -> not(toLdap(not.inner(), resolver));
            case Filter.ValuePath vp -> valuePath(vp, resolver);
            case Filter.Present p -> {
                List<String> parts = new ArrayList<>();
                for (Target t : targets(p.path(), resolver)) {
                    if (t.constant() == null) parts.add("(" + t.ldapAttr() + "=*)");
                    else parts.add(t.constant().isNull() ? FALSE : t.presence());
                }
                yield or(parts);
            }
            case Filter.Compare c -> {
                List<String> parts = new ArrayList<>();
                for (Target t : targets(c.path(), resolver)) {
                    if (t.constant() != null) {
                        parts.add(FilterEvaluator.compare(t.constant(), c.op(), c.literal(), false)
                                ? t.presence() : FALSE);
                    } else if (t.time() != TimeSyntax.NONE) {
                        parts.add(compareTime(t, c));
                    } else {
                        parts.add(compare(t.ldapAttr(), c, escape(literal(c.literal()))));
                    }
                }
                yield or(parts);
            }
        };
    }

    /** emails[type eq "work" and value eq "x"]: one branch per mapped element shape. */
    private static String valuePath(Filter.ValuePath vp, Resolver resolver) {
        List<Map<String, Target>> elements = resolver.elements(vp.path());
        if (elements.isEmpty()) {
            throw new UnsupportedFilterException(
                    "value-path filter on " + vp.path().display() + " has no LDAP mapping");
        }
        List<String> branches = new ArrayList<>();
        for (Map<String, Target> element : elements) {
            // the branch already requires the element to exist, so inside it a
            // matching constant (type eq "work") is simply true
            Resolver inner = path -> {
                if (path.schemaUrn() != null || path.subAttr() != null || path.valueFilter() != null) return null;
                Target t = element.get(path.attr().toLowerCase());
                return t != null && t.constant() != null ? Target.constant(t.constant(), TRUE) : t;
            };
            branches.add(and(presence(element), toLdap(vp.inner(), inner)));
        }
        return or(branches);
    }

    /** Resolves a filter path to the LDAP targets it reads (OR'd by the caller). */
    private static List<Target> targets(AttributePath path, Resolver resolver) {
        if (path.valueFilter() != null) {
            throw new UnsupportedFilterException("value-path filters cannot be translated to LDAP");
        }
        Target exact = resolver.exact(path);
        if (exact != null) return List.of(exact);
        // a multi-valued attribute: "emails" compares each element's value
        String sub = (path.subAttr() == null ? "value" : path.subAttr()).toLowerCase();
        List<Target> found = new ArrayList<>();
        for (Map<String, Target> element : resolver.elements(path)) {
            Target t = element.get(sub);
            if (t != null) found.add(t);
        }
        if (found.isEmpty()) {
            throw new UnsupportedFilterException("attribute " + path.display() + " has no LDAP mapping");
        }
        return found;
    }

    /** Filter matching entries where the element exists: any of its mapped attributes present. */
    static String presence(Map<String, Target> element) {
        List<String> parts = new ArrayList<>();
        for (Target t : element.values()) {
            if (t.constant() == null) parts.add("(" + t.ldapAttr() + "=*)");
        }
        return parts.isEmpty() ? FALSE : or(parts);
    }

    private static String compare(String a, Filter.Compare c, String v) {
        return switch (c.op()) {
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

    /**
     * meta.created ge "2025-11-16T01:47:42Z" -> (whenCreated>=20251116014742.0Z).
     * Directory timestamps are whole seconds, so a fractional literal is rounded
     * in the direction that keeps the comparison exact.
     */
    private static String compareTime(Target t, Filter.Compare c) {
        if (c.op() == Op.CO || c.op() == Op.SW || c.op() == Op.EW) {
            throw new UnsupportedFilterException(c.op() + " is not supported on " + c.path().display());
        }
        Instant when = c.literal().isString() ? FilterEvaluator.parseDateTime(c.literal().asString()) : null;
        if (when == null) {
            throw new BackendException(400, "invalidFilter",
                    c.path().display() + " must be compared to a dateTime, e.g. \"2025-11-16T01:47:42Z\"");
        }
        Instant floor = when.truncatedTo(ChronoUnit.SECONDS);
        boolean whole = floor.equals(when);
        Instant ceil = whole ? floor : floor.plusSeconds(1);
        String a = t.ldapAttr();
        return switch (c.op()) {
            case EQ -> whole ? compare(a, c, generalizedTime(floor, t.time())) : FALSE;
            case NE -> whole ? compare(a, c, generalizedTime(floor, t.time())) : "(" + a + "=*)";
            case GE -> "(" + a + ">=" + generalizedTime(ceil, t.time()) + ")";
            case GT -> compare(a, c, generalizedTime(floor, t.time()));
            case LE -> "(" + a + "<=" + generalizedTime(floor, t.time()) + ")";
            case LT -> compare(a, c, generalizedTime(ceil, t.time()));
            case CO, SW, EW -> throw new IllegalStateException("handled above");
        };
    }

    /** Instant -> LDAP generalized time in the directory's syntax. */
    static String generalizedTime(Instant when, TimeSyntax syntax) {
        String s = GENERALIZED.format(when);
        return syntax == TimeSyntax.FRACTION ? s + ".0Z" : s + "Z";
    }

    // ---- composition with TRUE/FALSE folding ----

    private static String and(String l, String r) {
        if (l.equals(FALSE) || r.equals(FALSE)) return FALSE;
        if (l.equals(TRUE) || l.equals(r) || impliesPresence(r, l)) return r;
        if (r.equals(TRUE) || impliesPresence(l, r)) return l;
        return "(&" + l + r + ")";
    }

    /** True when item is a simple positive assertion on the attribute that presence tests: (mail=x) implies (mail=*). */
    private static boolean impliesPresence(String item, String presence) {
        if (!presence.endsWith("=*)") || presence.indexOf('(', 1) >= 0 || item.indexOf('(', 1) >= 0) return false;
        String attr = presence.substring(1, presence.length() - 3);
        return item.startsWith("(" + attr + "=") || item.startsWith("(" + attr + ">=")
                || item.startsWith("(" + attr + "<=");
    }

    private static String or(List<String> parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (p.equals(TRUE)) return TRUE;
            if (!p.equals(FALSE) && !kept.contains(p)) kept.add(p);
        }
        if (kept.isEmpty()) return FALSE;
        if (kept.size() == 1) return kept.get(0);
        return "(|" + String.join("", kept) + ")";
    }

    private static String not(String inner) {
        if (inner.equals(TRUE)) return FALSE;
        if (inner.equals(FALSE)) return TRUE;
        return "(!" + inner + ")";
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
