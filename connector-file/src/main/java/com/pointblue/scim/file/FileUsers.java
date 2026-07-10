// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.file;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /Users backed by a CSV or JSON file. */
final class FileUsers implements ResourceBackend {

    private final RecordFile store;
    private final FileConfig.Table table;
    private final FileMapper mapper;
    private final String resourceType;

    FileUsers(RecordFile store, FileConfig.Table table, FileMapper mapper, String resourceType) {
        this.store = store;
        this.table = table;
        this.mapper = mapper;
        this.resourceType = resourceType;
    }

    FileMapper mapper() { return mapper; }
    FileConfig.Table table() { return table; }

    JsonObject toScim(JsonObject record) {
        JsonObject scim = mapper.toScim(record);
        JsonValue id = record.getIgnoreCase(table.idColumn);
        if (id != null && !id.isNull()) scim.put("id", id.asString());
        JsonObject meta = new JsonObject();
        meta.put("resourceType", resourceType);
        copyMeta(record, "created", meta);
        copyMeta(record, "lastModified", meta);
        scim.put("meta", meta);
        return scim;
    }

    private void copyMeta(JsonObject record, String key, JsonObject meta) {
        JsonValue v = record.getIgnoreCase("meta_" + key);
        if (v != null && !v.isNull()) meta.put(key, v.asString());
    }

    @Override
    public JsonObject create(JsonObject resource) {
        JsonObject record = mapper.toRecord(resource, false);
        String id = UUID.randomUUID().toString();
        record.put(table.idColumn, id);
        String now = Instant.now().toString();
        record.put("meta_created", now);
        record.put("meta_lastModified", now);
        // uniqueness: userName must be unique if it is mapped
        checkUnique(resource, null);
        store.add(record);
        return get(id);
    }

    @Override
    public JsonObject get(String id) {
        JsonObject record = store.find(table.idColumn, id);
        if (record == null) throw BackendException.notFound(resourceType, id);
        return toScim(record);
    }

    @Override
    public JsonObject replace(String id, JsonObject resource) {
        JsonObject existing = store.find(table.idColumn, id);
        if (existing == null) throw BackendException.notFound(resourceType, id);
        checkUnique(resource, id);
        JsonObject record = mapper.toRecord(resource, false);
        record.put(table.idColumn, id);
        JsonValue created = existing.getIgnoreCase("meta_created");
        record.put("meta_created", created == null ? Instant.now().toString() : created.asString());
        record.put("meta_lastModified", Instant.now().toString());
        store.replace(table.idColumn, id, record);
        return get(id);
    }

    @Override
    public void delete(String id) {
        if (!store.remove(table.idColumn, id)) throw BackendException.notFound(resourceType, id);
    }

    @Override
    public SearchResult search(SearchRequest request) {
        List<JsonObject> matched = new ArrayList<>();
        for (JsonObject record : store.all()) {
            JsonObject scim = toScim(record);
            if (request.filter() == null || mapper.evaluator().matches(request.filter(), scim)) {
                matched.add(scim);
            }
        }
        int from = Math.min(request.startIndex() - 1, matched.size());
        int to = Math.min(from + request.count(), matched.size());
        return new SearchResult(new ArrayList<>(matched.subList(from, to)), matched.size());
    }

    /** Enforce uniqueness of userName across the file (mirrors the SCIM schema). */
    private void checkUnique(JsonObject candidate, String selfId) {
        String userName = candidate.optString("userName", null);
        String field = table.columns.get("userName");
        if (userName == null || field == null) return;
        for (JsonObject record : store.all()) {
            JsonValue id = record.getIgnoreCase(table.idColumn);
            if (selfId != null && id != null && selfId.equals(id.asString())) continue;
            JsonValue existing = record.getIgnoreCase(field);
            if (existing instanceof JsonString s && s.value().equalsIgnoreCase(userName)) {
                throw BackendException.conflict("userName '" + userName + "' already exists");
            }
        }
    }
}
