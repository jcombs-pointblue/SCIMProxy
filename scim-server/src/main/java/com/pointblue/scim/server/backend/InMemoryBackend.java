// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.backend;

import com.pointblue.scim.filter.FilterEvaluator;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.schema.Attribute;
import com.pointblue.scim.schema.ResourceType;
import com.pointblue.scim.schema.Schema;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.Capabilities;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.ScimBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reference backend: keeps resources in memory. Used for demos and as the
 * target of the generic SPI contract tests.
 */
public final class InMemoryBackend implements ScimBackend {

    private final SchemaRegistry registry;
    private final Map<String, Map<String, JsonObject>> store = new LinkedHashMap<>();

    public InMemoryBackend(SchemaRegistry registry) {
        this.registry = registry;
    }

    @Override public Capabilities capabilities() { return Capabilities.inMemoryDefaults(); }

    @Override public void healthCheck() {}

    @Override
    public ResourceBackend resource(String resourceTypeName) {
        ResourceType rt = registry.resourceType(resourceTypeName);
        if (rt == null) return null;
        synchronized (store) {
            store.computeIfAbsent(rt.name(), k -> new LinkedHashMap<>());
        }
        return new TypeStore(rt);
    }

    private final class TypeStore implements ResourceBackend {

        private final ResourceType rt;
        private final FilterEvaluator evaluator;

        TypeStore(ResourceType rt) {
            this.rt = rt;
            this.evaluator = new FilterEvaluator(rt.baseSchema(), path -> {
                Attribute a = registry.resolve(rt.name(), path);
                return a != null && a.caseExact();
            });
        }

        private Map<String, JsonObject> resources() { return store.get(rt.name()); }

        @Override
        public JsonObject create(JsonObject resource) {
            synchronized (store) {
                checkUnique(resource, null);
                String id = UUID.randomUUID().toString();
                JsonObject stored = (JsonObject) resource.copy();
                stored.put("id", id);
                String now = Instant.now().toString();
                JsonObject meta = new JsonObject();
                meta.put("resourceType", rt.name());
                meta.put("created", now);
                meta.put("lastModified", now);
                stored.put("meta", meta);
                resources().put(id, stored);
                return (JsonObject) stored.copy();
            }
        }

        @Override
        public JsonObject get(String id) {
            synchronized (store) {
                JsonObject r = resources().get(id);
                if (r == null) throw BackendException.notFound(rt.name(), id);
                return (JsonObject) r.copy();
            }
        }

        @Override
        public JsonObject replace(String id, JsonObject resource) {
            synchronized (store) {
                JsonObject existing = resources().get(id);
                if (existing == null) throw BackendException.notFound(rt.name(), id);
                checkUnique(resource, id);
                JsonObject stored = (JsonObject) resource.copy();
                stored.put("id", id);
                JsonObject oldMeta = existing.getIgnoreCase("meta").asObject();
                JsonObject meta = new JsonObject();
                meta.put("resourceType", rt.name());
                meta.put("created", oldMeta.optString("created", Instant.now().toString()));
                meta.put("lastModified", Instant.now().toString());
                stored.put("meta", meta);
                resources().put(id, stored);
                return (JsonObject) stored.copy();
            }
        }

        @Override
        public void delete(String id) {
            synchronized (store) {
                if (resources().remove(id) == null) throw BackendException.notFound(rt.name(), id);
            }
        }

        @Override
        public SearchResult search(SearchRequest request) {
            synchronized (store) {
                List<JsonObject> matched = new ArrayList<>();
                for (JsonObject r : resources().values()) {
                    if (request.filter() == null || evaluator.matches(request.filter(), r)) {
                        matched.add(r);
                    }
                }
                int from = Math.min(request.startIndex() - 1, matched.size());
                int to = Math.min(from + request.count(), matched.size());
                List<JsonObject> page = new ArrayList<>();
                for (JsonObject r : matched.subList(from, to)) page.add((JsonObject) r.copy());
                return new SearchResult(page, matched.size());
            }
        }

        /** Enforces uniqueness declarations of the base schema (e.g. userName). */
        private void checkUnique(JsonObject candidate, String selfId) {
            Schema base = registry.schema(rt.baseSchema());
            if (base == null) return;
            for (Attribute attr : base.attributes()) {
                if (attr.uniqueness() == Attribute.Uniqueness.NONE) continue;
                JsonValue val = candidate.getIgnoreCase(attr.name());
                if (!(val instanceof JsonString s)) continue;
                for (Map.Entry<String, JsonObject> e : resources().entrySet()) {
                    if (e.getKey().equals(selfId)) continue;
                    JsonValue other = e.getValue().getIgnoreCase(attr.name());
                    if (other instanceof JsonString os) {
                        boolean same = attr.caseExact()
                                ? os.value().equals(s.value())
                                : os.value().equalsIgnoreCase(s.value());
                        if (same) throw BackendException.conflict(
                                attr.name() + " '" + s.value() + "' already exists");
                    }
                }
            }
        }
    }
}
