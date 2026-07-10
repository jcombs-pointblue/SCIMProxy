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

    private final Function<AttributePath, String> columnFor;
    private final Function<String, Integer> sqlTypeFor;
    private final StringBuilder sb = new StringBuilder();
    private final List<JsonValue> params = new ArrayList<>();

    private SqlFilter(Function<AttributePath, String> columnFor, Function<String, Integer> sqlTypeFor) {
        this.columnFor = columnFor;
        this.sqlTypeFor = sqlTypeFor;
    }

    static Sql translate(Filter filter, Function<AttributePath, String> columnFor,
                         Function<String, Integer> sqlTypeFor) {
        SqlFilter t = new SqlFilter(columnFor, sqlTypeFor);
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
            case Filter.Present p -> sb.append(col(p.path())).append(" IS NOT NULL");
            case Filter.Compare c -> emitCompare(c);
        }
    }

    private void emitCompare(Filter.Compare c) {
        String column = col(c.path());
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

    private String col(AttributePath path) {
        if (path.valueFilter() != null) {
            throw new UnsupportedFilterException("value-path filters are not translatable to SQL");
        }
        String column = columnFor.apply(path);
        if (column == null) throw new UnsupportedFilterException("attribute " + path.display() + " has no column mapping");
        return column;
    }

    private void param(JsonValue v) { params.add(v); }
}
