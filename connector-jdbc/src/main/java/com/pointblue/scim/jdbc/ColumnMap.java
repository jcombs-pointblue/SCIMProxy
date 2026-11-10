// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.jdbc;

import com.pointblue.scim.filter.FilterEvaluator;
import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.schema.CoreSchemas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps configured SCIM paths to table columns, both directions. A column value
 * is a single scalar, so multi-valued SCIM attributes are addressed by a
 * value-filter path (e.g. {@code emails[type eq "work"].value}).
 */
final class ColumnMap {

    private record Entry(AttributePath path, String column) {}

    private final String baseUrn;
    private final Map<String, Entry> byKey = new LinkedHashMap<>();
    private final Map<String, Integer> columnTypes; // lowercase column -> sql type
    private final FilterEvaluator evaluator;

    ColumnMap(String baseUrn, Map<String, String> mappings, Map<String, Integer> columnTypes) {
        this.baseUrn = baseUrn;
        this.columnTypes = columnTypes;
        this.evaluator = new FilterEvaluator(baseUrn, FilterEvaluator.CaseExactness.DEFAULT);
        mappings.forEach((scimPath, column) -> {
            AttributePath p = FilterParser.parsePath(scimPath);
            byKey.put(normalize(p), new Entry(p, column));
        });
    }

    /** Column for a filter/patch path (no value filter beyond configured eq clauses); null if unmapped. */
    String column(AttributePath path) {
        Entry e = byKey.get(normalize(path));
        return e == null ? null : e.column();
    }

    /**
     * Columns a filter path reads. Beyond exact mappings, a multi-valued attribute
     * mapped per element resolves the way clients filter it: emails.value (and bare
     * emails, which compares each element's value) reads every column mapped as
     * emails[...].value. Empty when unmapped.
     */
    List<String> filterColumns(AttributePath path) {
        String exact = column(path);
        if (exact != null) return List.of(exact);
        if (path.valueFilter() != null) return List.of();
        String sub = path.subAttr() == null ? "value" : path.subAttr();
        List<String> cols = new java.util.ArrayList<>();
        for (Entry e : byKey.values()) {
            AttributePath p = e.path();
            if (p.valueFilter() != null && p.subAttr() != null && p.subAttr().equalsIgnoreCase(sub)
                    && p.attr().equalsIgnoreCase(path.attr()) && sameSchema(p, path)
                    && !cols.contains(e.column())) {
                cols.add(e.column());
            }
        }
        return cols;
    }

    private boolean sameSchema(AttributePath a, AttributePath b) {
        String ua = a.schemaUrn() == null ? baseUrn : a.schemaUrn();
        String ub = b.schemaUrn() == null ? baseUrn : b.schemaUrn();
        return ua.equalsIgnoreCase(ub);
    }

    int sqlType(String column) {
        return columnTypes.getOrDefault(column.toLowerCase(), java.sql.Types.VARCHAR);
    }

    /** SCIM resource -> column/value map (first value of each mapped path). */
    Map<String, JsonValue> toRow(JsonObject resource) {
        return toRow(resource, false);
    }

    /**
     * @param includeNulls when true, mapped-but-absent attributes are emitted as
     *                     JSON null so a full replace (PUT) clears their columns
     */
    Map<String, JsonValue> toRow(JsonObject resource, boolean includeNulls) {
        Map<String, JsonValue> row = new LinkedHashMap<>();
        for (Entry e : byKey.values()) {
            List<JsonValue> values = evaluator.resolve(resource, e.path());
            if (!values.isEmpty()) row.put(e.column(), values.get(0));
            else if (includeNulls) row.put(e.column(), com.pointblue.scim.json.JsonNull.NULL);
        }
        return row;
    }

    /** column -> JSON value (a DB row) projected onto SCIM shape. */
    JsonObject toScim(Map<String, JsonValue> row) {
        JsonObject scim = new JsonObject();
        for (Entry e : byKey.values()) {
            JsonValue v = null;
            for (Map.Entry<String, JsonValue> c : row.entrySet()) {
                if (c.getKey().equalsIgnoreCase(e.column())) { v = c.getValue(); break; }
            }
            if (v != null && !v.isNull()) setPath(scim, e.path(), v);
        }
        JsonArray schemas = new JsonArray().add(baseUrn);
        for (String key : List.copyOf(scim.keys())) {
            if (key.indexOf(':') >= 0) schemas.add(key);
        }
        scim.put("schemas", schemas);
        return scim;
    }

    private void setPath(JsonObject resource, AttributePath path, JsonValue value) {
        JsonObject container = resource;
        if (path.schemaUrn() != null && !path.schemaUrn().equalsIgnoreCase(baseUrn)) {
            if (resource.getIgnoreCase(path.schemaUrn()) instanceof JsonObject o) container = o;
            else { container = new JsonObject(); resource.put(path.schemaUrn(), container); }
        }
        if (path.valueFilter() == null) {
            if (path.subAttr() == null) {
                container.put(path.attr(), value.copy());
            } else {
                JsonObject complex = container.getIgnoreCase(path.attr()) instanceof JsonObject o
                        ? o : new JsonObject();
                complex.put(path.subAttr(), value.copy());
                container.put(container.findKey(path.attr()).orElse(path.attr()), complex);
            }
            return;
        }
        JsonArray arr = container.getIgnoreCase(path.attr()) instanceof JsonArray a ? a : new JsonArray();
        JsonObject element = new JsonObject();
        TreeMap<String, String> eq = new TreeMap<>();
        collectEq(path.valueFilter(), eq);
        eq.forEach(element::put);
        if (path.subAttr() != null) element.put(path.subAttr(), value.copy());
        arr.add(element);
        container.put(container.findKey(path.attr()).orElse(path.attr()), arr);
    }

    private static String normalize(AttributePath p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.schemaUrn() == null ? "" : p.schemaUrn().toLowerCase()).append('|');
        sb.append(p.attr().toLowerCase()).append('|');
        sb.append(p.subAttr() == null ? "" : p.subAttr().toLowerCase()).append('|');
        if (p.valueFilter() != null) {
            TreeMap<String, String> eq = new TreeMap<>();
            collectEq(p.valueFilter(), eq);
            sb.append(eq);
        }
        return sb.toString();
    }

    private static boolean collectEq(com.pointblue.scim.filter.Filter f, Map<String, String> into) {
        return switch (f) {
            case com.pointblue.scim.filter.Filter.And and -> collectEq(and.left(), into) && collectEq(and.right(), into);
            case com.pointblue.scim.filter.Filter.Compare c
                    when c.op() == com.pointblue.scim.filter.Op.EQ && c.path().subAttr() == null -> {
                into.put(c.path().attr().toLowerCase(),
                        c.literal().isString() ? c.literal().asString() : c.literal().toString());
                yield true;
            }
            default -> false;
        };
    }
}
