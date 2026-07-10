// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.jdbc;

import com.pointblue.scim.json.JsonBoolean;
import com.pointblue.scim.json.JsonNull;
import com.pointblue.scim.json.JsonNumber;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.spi.BackendException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Thin JDBC helper: connections, column-type discovery, and JSON<->SQL value
 * conversion. A connection is opened per operation; provisioning traffic is low
 * enough that a pool is not yet warranted (the driver's own is available if set
 * on the URL).
 */
final class Db {

    private final String url;
    private final String user;
    private final String password;

    Db(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    Connection open() {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw BackendException.unavailable("cannot connect to database: " + e.getMessage());
        }
    }

    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    <T> T withConnection(Work<T> work) {
        try (Connection c = open()) {
            return work.run(c);
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    static BackendException translate(SQLException e) {
        String state = e.getSQLState();
        if ("23505".equals(state)) return BackendException.conflict("unique constraint violated: " + e.getMessage());
        if (state != null && state.startsWith("23")) {
            return new BackendException(400, "invalidValue", "constraint violation: " + e.getMessage());
        }
        if (state != null && state.startsWith("08")) return BackendException.unavailable("database connection error");
        return new BackendException(502, null, "database error: " + e.getMessage());
    }

    /** column name (lowercased) -> java.sql.Types code, discovered from the catalog. */
    Map<String, Integer> columnTypes(String table) {
        return withConnection(c -> {
            Map<String, Integer> types = new LinkedHashMap<>();
            DatabaseMetaData md = c.getMetaData();
            // try as given, then upper/lower, to tolerate case-folding differences
            for (String t : new String[] {table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
                try (ResultSet rs = md.getColumns(c.getCatalog(), null, t, null)) {
                    while (rs.next()) {
                        types.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                                rs.getInt("DATA_TYPE"));
                    }
                }
                if (!types.isEmpty()) break;
            }
            if (types.isEmpty()) throw new BackendException(500, null, "table not found or has no columns: " + table);
            return types;
        });
    }

    static boolean isText(int sqlType) {
        return switch (sqlType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB -> true;
            default -> false;
        };
    }

    static boolean isNumeric(int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.DECIMAL,
                 Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> true;
            default -> false;
        };
    }

    static boolean isBoolean(int sqlType) {
        return sqlType == Types.BOOLEAN || sqlType == Types.BIT;
    }

    /** ResultSet column value -> JSON, guided by the column's SQL type. */
    static JsonValue read(ResultSet rs, String column, int sqlType) throws SQLException {
        if (isBoolean(sqlType)) {
            boolean b = rs.getBoolean(column);
            return rs.wasNull() ? JsonNull.NULL : JsonBoolean.of(b);
        }
        if (isNumeric(sqlType)) {
            BigDecimal n = rs.getBigDecimal(column);
            return n == null ? JsonNull.NULL : new JsonNumber(n);
        }
        if (isTemporal(sqlType)) {
            OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
            return odt == null ? JsonNull.NULL : new JsonString(odt.toInstant().toString());
        }
        Object o = rs.getObject(column);
        return o == null ? JsonNull.NULL : new JsonString(o.toString());
    }

    static boolean isTemporal(int sqlType) {
        return switch (sqlType) {
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE,
                 Types.TIMESTAMP_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    /** Binds a JSON value to a parameter, coercing to the column's SQL type. */
    static void bind(PreparedStatement ps, int idx, int sqlType, JsonValue v) throws SQLException {
        if (v == null || v.isNull()) { ps.setNull(idx, sqlType); return; }
        if (isBoolean(sqlType)) {
            ps.setBoolean(idx, v instanceof JsonBoolean b ? b.value() : Boolean.parseBoolean(v.asString()));
        } else if (isNumeric(sqlType)) {
            ps.setBigDecimal(idx, v instanceof JsonNumber n ? n.value() : new BigDecimal(v.asString()));
        } else {
            String s = v instanceof JsonString js ? js.value() : v.toString();
            // non-text columns (uuid reports as OTHER on Postgres, a binary type on H2)
            // take a java.util.UUID when the value is one; drivers coerce it natively.
            if (!isText(sqlType)) {
                try {
                    ps.setObject(idx, java.util.UUID.fromString(s));
                    return;
                } catch (IllegalArgumentException notUuid) {
                    // fall through to string binding for other non-text types
                }
            }
            ps.setObject(idx, s, sqlType);
        }
    }
}
