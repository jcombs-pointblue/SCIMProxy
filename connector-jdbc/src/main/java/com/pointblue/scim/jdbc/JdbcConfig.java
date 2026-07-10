// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.jdbc;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed backend.{} block for the JDBC connector. A deployment describes its
 * user/group tables and the SCIM-path -> column mapping; the connector needs no
 * schema knowledge beyond that.
 */
public final class JdbcConfig {

    public final String url;
    public final String user;
    public final String password;

    public final Table users;
    public final Table groups;        // null when groups are not configured

    /** Membership join table linking groups to users. */
    public record Membership(String table, String groupColumn, String userColumn) {}

    public static final class Table {
        public final String name;
        public final String idColumn;
        public final String createdColumn;   // nullable
        public final String modifiedColumn;  // nullable
        public final String displayNameColumn; // groups only, nullable for users
        public final Map<String, String> columns = new LinkedHashMap<>(); // scimPath -> column
        public final Membership membership;  // groups only, nullable

        Table(JsonObject cfg, boolean isGroup) {
            this.name = require(cfg, "table");
            this.idColumn = cfg.optString("idColumn", "id");
            this.createdColumn = cfg.optString("createdColumn", null);
            this.modifiedColumn = cfg.optString("modifiedColumn", null);
            this.displayNameColumn = cfg.optString("displayNameColumn", null);
            if (cfg.getIgnoreCase("columns") instanceof JsonObject cols) {
                cols.forEach((scimPath, v) -> columns.put(scimPath, v.asString()));
            }
            if (isGroup && cfg.getIgnoreCase("membership") instanceof JsonObject m) {
                this.membership = new Membership(require(m, "table"),
                        require(m, "groupColumn"), require(m, "userColumn"));
            } else {
                this.membership = null;
            }
        }
    }

    public JdbcConfig(JsonObject cfg) {
        this.url = require(cfg, "url");
        this.user = cfg.optString("user", null);
        this.password = cfg.optString("password", null);
        JsonValue usersCfg = cfg.getIgnoreCase("users");
        if (!(usersCfg instanceof JsonObject uo)) {
            throw new IllegalArgumentException("jdbc backend requires a 'users' section");
        }
        this.users = new Table(uo, false);
        this.groups = cfg.getIgnoreCase("groups") instanceof JsonObject go ? new Table(go, true) : null;
    }

    private static String require(JsonObject o, String key) {
        String v = o.optString(key, null);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("jdbc config requires '" + key + "'");
        return v;
    }
}
