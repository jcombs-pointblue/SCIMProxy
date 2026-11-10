// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.jdbc;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /Users backed by a single SQL table. */
final class JdbcUsers implements ResourceBackend {

    private final Db db;
    private final JdbcConfig.Table table;
    private final ColumnMap map;
    private final String resourceType;

    JdbcUsers(Db db, JdbcConfig.Table table, ColumnMap map, String resourceType) {
        this.db = db;
        this.table = table;
        this.map = map;
        this.resourceType = resourceType;
    }

    ColumnMap columns() { return map; }
    JdbcConfig.Table table() { return table; }

    JsonObject rowToScim(Map<String, JsonValue> row) {
        JsonObject scim = map.toScim(row);
        JsonValue id = row.get(table.idColumn.toLowerCase());
        if (id != null && !id.isNull()) scim.put("id", id.asString());
        JsonObject meta = new JsonObject();
        meta.put("resourceType", resourceType);
        if (table.createdColumn != null) putTime(meta, "created", row.get(table.createdColumn.toLowerCase()));
        if (table.modifiedColumn != null) putTime(meta, "lastModified", row.get(table.modifiedColumn.toLowerCase()));
        scim.put("meta", meta);
        return scim;
    }

    private static void putTime(JsonObject meta, String key, JsonValue v) {
        if (v != null && !v.isNull()) meta.put(key, v.asString());
    }

    /** All columns to select: id, timestamps, and every mapped column. */
    List<String> selectColumns() {
        List<String> cols = new ArrayList<>();
        cols.add(table.idColumn);
        if (table.createdColumn != null) cols.add(table.createdColumn);
        if (table.modifiedColumn != null) cols.add(table.modifiedColumn);
        for (String scimPath : table.columns.keySet()) cols.add(table.columns.get(scimPath));
        return cols.stream().distinct().toList();
    }

    Map<String, JsonValue> readRow(ResultSet rs, List<String> cols) throws SQLException {
        Map<String, JsonValue> row = new LinkedHashMap<>();
        for (String c : cols) row.put(c.toLowerCase(), Db.read(rs, c, map.sqlType(c)));
        return row;
    }

    @Override
    public JsonObject create(JsonObject resource) {
        Map<String, JsonValue> values = map.toRow(resource);
        JsonValue active = resource.getIgnoreCase("active");
        String activeCol = map.column(com.pointblue.scim.model.AttributePath.of("active"));
        if (active != null && activeCol != null && !values.containsKey(activeCol)) values.put(activeCol, active);
        if (values.isEmpty()) throw new BackendException(400, "invalidValue", "no mapped attributes to insert");

        List<String> cols = new ArrayList<>(values.keySet());
        String sql = "INSERT INTO " + table.name + " (" + String.join(", ", cols) + ") VALUES ("
                + placeholders(cols.size()) + ")";
        String id = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql, new String[] {table.idColumn})) {
                bindRow(ps, cols, values);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new BackendException(502, null, "insert returned no generated id");
                    return keys.getObject(1).toString();
                }
            }
        });
        return get(id);
    }

    @Override
    public JsonObject get(String id) {
        List<String> cols = selectColumns();
        String sql = "SELECT " + String.join(", ", cols) + " FROM " + table.name
                + " WHERE " + table.idColumn + " = ?";
        JsonObject result = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Db.bind(ps, 1, map.sqlType(table.idColumn), com.pointblue.scim.json.JsonValue.of(id));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rowToScim(readRow(rs, cols)) : null;
                }
            }
        });
        if (result == null) throw BackendException.notFound(resourceType, id);
        return result;
    }

    @Override
    public JsonObject replace(String id, JsonObject resource) {
        // PUT is a full replace: mapped attributes absent from the payload are cleared
        Map<String, JsonValue> values = map.toRow(resource, true);
        JsonValue active = resource.getIgnoreCase("active");
        String activeCol = map.column(com.pointblue.scim.model.AttributePath.of("active"));
        if (activeCol != null) values.put(activeCol, active == null ? com.pointblue.scim.json.JsonNull.NULL : active);
        values.remove(table.idColumn);
        if (values.isEmpty()) return get(id);

        List<String> cols = new ArrayList<>(values.keySet());
        StringBuilder set = new StringBuilder();
        for (String col : cols) { if (!set.isEmpty()) set.append(", "); set.append(col).append(" = ?"); }
        if (table.modifiedColumn != null) set.append(", ").append(table.modifiedColumn).append(" = now()");
        String sql = "UPDATE " + table.name + " SET " + set + " WHERE " + table.idColumn + " = ?";
        int updated = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = bindRow(ps, cols, values);
                Db.bind(ps, i, map.sqlType(table.idColumn), com.pointblue.scim.json.JsonValue.of(id));
                return ps.executeUpdate();
            }
        });
        if (updated == 0) throw BackendException.notFound(resourceType, id);
        return get(id);
    }

    @Override
    public void delete(String id) {
        String sql = "DELETE FROM " + table.name + " WHERE " + table.idColumn + " = ?";
        int deleted = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Db.bind(ps, 1, map.sqlType(table.idColumn), com.pointblue.scim.json.JsonValue.of(id));
                return ps.executeUpdate();
            }
        });
        if (deleted == 0) throw BackendException.notFound(resourceType, id);
    }

    @Override
    public SearchResult search(SearchRequest request) {
        List<String> cols = selectColumns();
        SqlFilter.Sql where = request.filter() == null ? null
                : SqlFilter.translate(request.filter(), map::filterColumns, map::sqlType);
        String whereClause = where == null ? "" : " WHERE " + where.where();
        int offset = request.startIndex() - 1;
        int limit = request.count();

        return db.withConnection(c -> {
            long total;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM " + table.name + whereClause)) {
                bindParams(ps, 1, where);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); total = rs.getLong(1); }
            }
            List<JsonObject> resources = new ArrayList<>();
            String sql = "SELECT " + String.join(", ", cols) + " FROM " + table.name + whereClause
                    + orderBy() + " LIMIT ? OFFSET ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = bindParams(ps, 1, where);
                ps.setInt(i++, Math.max(limit, 0));
                ps.setInt(i, Math.max(offset, 0));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) resources.add(rowToScim(readRow(rs, cols)));
                }
            }
            return new SearchResult(resources, total);
        });
    }

    private String orderBy() {
        // stable ordering so pagination is deterministic
        return " ORDER BY " + table.idColumn;
    }

    // ---- binding helpers ----

    private int bindRow(PreparedStatement ps, List<String> cols, Map<String, JsonValue> values) throws SQLException {
        int i = 1;
        for (String col : cols) Db.bind(ps, i++, map.sqlType(col), values.get(col));
        return i;
    }

    private int bindParams(PreparedStatement ps, int start, SqlFilter.Sql where) throws SQLException {
        if (where == null) return start;
        int i = start;
        for (JsonValue v : where.params()) {
            // filter params: text params bind as strings; typing handled by lower()/LIKE in SQL
            if (v instanceof com.pointblue.scim.json.JsonBoolean b) ps.setBoolean(i, b.value());
            else if (v instanceof com.pointblue.scim.json.JsonNumber n) ps.setBigDecimal(i, n.value());
            else ps.setString(i, v.asString());
            i++;
        }
        return i;
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
