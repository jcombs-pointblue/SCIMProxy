// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.file;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;

import java.util.Map;

/**
 * Where a file stores meta.created / meta.lastModified. By default the fields
 * are {@code meta_created} / {@code meta_lastModified}; mapping {@code meta.created}
 * or {@code meta.lastModified} in a table's {@code columns} points them at an
 * existing field instead (e.g. a source export's {@code whenCreated}). On read,
 * a field literally named {@code meta.created} / {@code meta.lastModified} is also
 * accepted, so an export whose header uses the SCIM names works unconfigured.
 */
final class FileMeta {

    static final String DEFAULT_CREATED = "meta_created";
    static final String DEFAULT_MODIFIED = "meta_lastModified";

    private final String createdField;
    private final String modifiedField;

    FileMeta(Map<String, String> columns) {
        this.createdField = mapped(columns, "meta.created", DEFAULT_CREATED);
        this.modifiedField = mapped(columns, "meta.lastModified", DEFAULT_MODIFIED);
    }

    /** True for a SCIM path FileMeta owns, so the generic mapper leaves it alone. */
    static boolean isMetaPath(String scimPath) {
        return scimPath.equalsIgnoreCase("meta.created") || scimPath.equalsIgnoreCase("meta.lastModified");
    }

    /** Builds the SCIM meta object for a record. */
    JsonObject toScim(JsonObject record, String resourceType) {
        JsonObject meta = new JsonObject();
        meta.put("resourceType", resourceType);
        String created = created(record);
        String modified = read(record, modifiedField, DEFAULT_MODIFIED, "meta.lastModified");
        if (created != null) meta.put("created", created);
        if (modified != null) meta.put("lastModified", modified);
        return meta;
    }

    /** The record's creation time, from whichever field holds it, or null. */
    String created(JsonObject record) {
        return read(record, createdField, DEFAULT_CREATED, "meta.created");
    }

    void putCreated(JsonObject record, String value) { record.put(createdField, value); }

    void putModified(JsonObject record, String value) { record.put(modifiedField, value); }

    private static String read(JsonObject record, String... fields) {
        for (String f : fields) {
            JsonValue v = record.getIgnoreCase(f);
            if (v != null && !v.isNull() && v.isString() && !v.asString().isBlank()) return v.asString();
        }
        return null;
    }

    private static String mapped(Map<String, String> columns, String scimPath, String fallback) {
        for (Map.Entry<String, String> e : columns.entrySet()) {
            if (e.getKey().equalsIgnoreCase(scimPath)) return e.getValue();
        }
        return fallback;
    }
}
