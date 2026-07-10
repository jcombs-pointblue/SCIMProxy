// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.file;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed backend.{} block for the file connector. Each resource type is one
 * file (CSV or JSON) with a SCIM-path -> field mapping.
 */
public final class FileConfig {

    public enum Format { CSV, JSON }

    public final Format format;
    public final Table users;
    public final Table groups; // null when not configured

    public static final class Table {
        public final String file;
        public final String idColumn;
        public final String displayNameColumn;  // groups
        public final String membersColumn;       // groups
        public final String memberDelimiter;     // groups (csv)
        public final Map<String, String> columns = new LinkedHashMap<>(); // scimPath -> field

        Table(JsonObject cfg) {
            this.file = require(cfg, "file");
            this.idColumn = cfg.optString("idColumn", "id");
            this.displayNameColumn = cfg.optString("displayNameColumn", null);
            this.membersColumn = cfg.optString("membersColumn", "members");
            this.memberDelimiter = cfg.optString("memberDelimiter", ";");
            if (cfg.getIgnoreCase("columns") instanceof JsonObject cols) {
                cols.forEach((scimPath, v) -> columns.put(scimPath, v.asString()));
            }
        }
    }

    public FileConfig(JsonObject cfg) {
        this.format = switch (cfg.optString("format", "csv").toLowerCase()) {
            case "csv" -> Format.CSV;
            case "json" -> Format.JSON;
            default -> throw new IllegalArgumentException("file backend format must be csv | json");
        };
        JsonValue usersCfg = cfg.getIgnoreCase("users");
        if (!(usersCfg instanceof JsonObject uo)) {
            throw new IllegalArgumentException("file backend requires a 'users' section");
        }
        this.users = new Table(uo);
        this.groups = cfg.getIgnoreCase("groups") instanceof JsonObject go ? new Table(go) : null;
    }

    private static String require(JsonObject o, String key) {
        String v = o.optString(key, null);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("file config requires '" + key + "'");
        return v;
    }
}
