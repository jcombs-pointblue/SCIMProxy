// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.jdbc;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.spi.UnsupportedFilterException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Translates a SCIM filter AST into a parameterized SQL WHERE fragment. Values
 * are always bound as parameters (never interpolated), so untrusted filter
 * input cannot inject SQL. String equality/contains use case-insensitive
 * matching to honor SCIM's default semantics.
 */
final class SqlFilter {

    /** A SQL fragment plus the values to bind into its {@code ?} placeholders. */
    record Sql(String where, List<JsonValue> params) {}

    private final Function<AttributePath, List<String>> columnsFor;
    private final Function<String, Integer> sqlTypeFor;
    private final StringBuilder sb = new StringBuilder();
    private final List<JsonValue> params = new ArrayList<>();

    private SqlFilter(Function<AttributePath, List<String>> columnsFor, Function<String, Integer> sqlTypeFor) {
        this.columnsFor = columnsFor;
        this.sqlTypeFor = sqlTypeFor;
    }

    /**
     * @param columnsFor columns a path reads; several (emails.value over a work and
     *                   a home column) are OR'd, matching SCIM's any-value semantics
     */
    static Sql translate(Filter filter, Function<AttributePath, List<String>> columnsFor,
                         Function<String, Integer> sqlTypeFor) {
        SqlFilter t = new SqlFilter(columnsFor, sqlTypeFor);
        t.emit(filter);
        return new Sql(t.sb.toString(), t.params);
    }

    private void emit(Filter filter) {
        switch (filter) {
            case Filter.And and -> { sb.append('('); emit(and.left()); sb.append(" AND "); emit(and.right()); sb.append(')'); }
            case Filter.Or or -> { sb.append('('); emit(or.left()); sb.append(" OR "); emit(or.right()); sb.append(')'); }
            case Filter.Not not -> { sb.append("(NOT "); emit(not.inner()); sb.append(')'); }
            case Filter.ValuePath vp -> throw new UnsupportedFilterException(
                    "value-path filters are not translatable to SQL");
            case Filter.Present p -> anyOf(cols(p.path()), column -> sb.append(column).append(" IS NOT NULL"));
            case Filter.Compare c -> anyOf(cols(c.path()), column -> emitCompare(c, column));
        }
    }

    /** Emits one fragment per column, OR'd when there is more than one. */
    private void anyOf(List<String> columns, java.util.function.Consumer<String> emitOne) {
        if (columns.size() == 1) { emitOne.accept(columns.get(0)); return; }
        sb.append('(');
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(" OR ");
            emitOne.accept(columns.get(i));
        }
        sb.append(')');
    }

    private void emitCompare(Filter.Compare c, String column) {
        boolean text = Db.isText(sqlTypeFor.apply(column));
        switch (c.op()) {
            case EQ -> { if (text) { sb.append("lower(").append(column).append(") = lower(?)"); } else { sb.append(column).append(" = ?"); } param(c.literal()); }
            case NE -> { if (text) { sb.append("lower(").append(column).append(") <> lower(?)"); } else { sb.append(column).append(" <> ?"); } param(c.literal()); }
            case CO -> like(column, "%", c.literal(), "%");
            case SW -> like(column, "", c.literal(), "%");
            case EW -> like(column, "%", c.literal(), "");
            case GT -> { sb.append(column).append(" > ?"); param(c.literal()); }
            case GE -> { sb.append(column).append(" >= ?"); param(c.literal()); }
            case LT -> { sb.append(column).append(" < ?"); param(c.literal()); }
            case LE -> { sb.append(column).append(" <= ?"); param(c.literal()); }
        }
    }

    /** Case-insensitive LIKE with the value escaped and wrapped in the given affixes. */
    private void like(String column, String pre, JsonValue literal, String post) {
        if (!literal.isString()) throw new UnsupportedFilterException("co/sw/ew require a string value");
        String escaped = literal.asString()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        sb.append("lower(").append(column).append(") LIKE lower(?) ESCAPE '\\'");
        params.add(com.pointblue.scim.json.JsonValue.of(pre + escaped + post));
    }

    private List<String> cols(AttributePath path) {
        if (path.valueFilter() != null) {
            throw new UnsupportedFilterException("value-path filters are not translatable to SQL");
        }
        List<String> columns = columnsFor.apply(path);
        if (columns == null || columns.isEmpty()) {
            throw new UnsupportedFilterException("attribute " + path.display() + " has no column mapping");
        }
        return columns;
    }

    private void param(JsonValue v) { params.add(v); }
}
