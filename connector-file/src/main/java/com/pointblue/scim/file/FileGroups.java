package com.pointblue.scim.file;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.FilterEvaluator;
import com.pointblue.scim.filter.Op;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.patch.PatchEngine;
import com.pointblue.scim.patch.PatchOp;
import com.pointblue.scim.schema.CoreSchemas;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** /Groups backed by a CSV or JSON file; membership stored inline as an id list. */
final class FileGroups implements ResourceBackend {

    private final RecordFile store;
    private final FileConfig.Table table;
    private final FileConfig.Format format;
    private final FileUsers users;
    private final PatchEngine patchEngine;
    private final FilterEvaluator evaluator =
            new FilterEvaluator(CoreSchemas.GROUP_URN, FilterEvaluator.CaseExactness.DEFAULT);

    FileGroups(RecordFile store, FileConfig.Table table, FileConfig.Format format,
               FileUsers users, SchemaRegistry registry) {
        this.store = store;
        this.table = table;
        this.format = format;
        this.users = users;
        this.patchEngine = new PatchEngine(registry, "Group");
        if (table.displayNameColumn == null) {
            throw new IllegalArgumentException("groups config requires 'displayNameColumn'");
        }
    }

    private JsonObject toScim(JsonObject record) {
        JsonObject g = new JsonObject();
        g.put("schemas", new JsonArray().add(CoreSchemas.GROUP_URN));
        JsonValue id = record.getIgnoreCase(table.idColumn);
        g.put("id", id == null ? null : id.asString());
        g.put("displayName", record.optString(table.displayNameColumn, null));
        JsonArray members = new JsonArray();
        for (String uid : memberIds(record)) {
            JsonObject m = new JsonObject();
            m.put("value", uid);
            String display = userNameOf(uid);
            if (display != null) m.put("display", display);
            members.add(m);
        }
        g.put("members", members);
        JsonObject meta = new JsonObject();
        meta.put("resourceType", "Group");
        JsonValue created = record.getIgnoreCase("meta_created");
        JsonValue modified = record.getIgnoreCase("meta_lastModified");
        if (created != null && !created.isNull()) meta.put("created", created.asString());
        if (modified != null && !modified.isNull()) meta.put("lastModified", modified.asString());
        g.put("meta", meta);
        return g;
    }

    /** userName of a member for display, or null if the referenced user is gone. */
    private String userNameOf(String uid) {
        try {
            return users.get(uid).optString("userName", null);
        } catch (BackendException notFound) {
            return null;
        }
    }

    // ---- member id storage (delimited string in CSV, array in JSON) ----

    private List<String> memberIds(JsonObject record) {
        JsonValue v = record.getIgnoreCase(table.membersColumn);
        List<String> ids = new ArrayList<>();
        if (v instanceof JsonArray arr) {
            for (JsonValue e : arr) ids.add(e.asString());
        } else if (v instanceof JsonString s && !s.value().isEmpty()) {
            for (String part : s.value().split(java.util.regex.Pattern.quote(table.memberDelimiter))) {
                if (!part.isBlank()) ids.add(part.trim());
            }
        }
        return ids;
    }

    private void putMembers(JsonObject record, List<String> ids) {
        if (format == FileConfig.Format.JSON) {
            JsonArray arr = new JsonArray();
            for (String id : ids) arr.add(id);
            record.put(table.membersColumn, arr);
        } else {
            record.put(table.membersColumn, String.join(table.memberDelimiter, ids));
        }
    }

    // ---- CRUD ----

    @Override
    public JsonObject create(JsonObject resource) {
        String name = resource.optString("displayName", null);
        if (name == null) throw new BackendException(400, "invalidValue", "displayName is required");
        JsonObject record = new JsonObject();
        String id = UUID.randomUUID().toString();
        record.put(table.idColumn, id);
        record.put(table.displayNameColumn, name);
        putMembers(record, memberIdsFrom(resource));
        String now = Instant.now().toString();
        record.put("meta_created", now);
        record.put("meta_lastModified", now);
        store.add(record);
        return get(id);
    }

    @Override
    public JsonObject get(String id) {
        JsonObject record = store.find(table.idColumn, id);
        if (record == null) throw BackendException.notFound("Group", id);
        return toScim(record);
    }

    @Override
    public JsonObject replace(String id, JsonObject resource) {
        JsonObject existing = store.find(table.idColumn, id);
        if (existing == null) throw BackendException.notFound("Group", id);
        String name = resource.optString("displayName", existing.optString(table.displayNameColumn, null));
        existing.put(table.displayNameColumn, name);
        putMembers(existing, memberIdsFrom(resource));
        existing.put("meta_lastModified", Instant.now().toString());
        store.replace(table.idColumn, id, existing);
        return get(id);
    }

    @Override
    public void delete(String id) {
        if (!store.remove(table.idColumn, id)) throw BackendException.notFound("Group", id);
    }

    @Override
    public SearchResult search(SearchRequest request) {
        List<JsonObject> matched = new ArrayList<>();
        for (JsonObject record : store.all()) {
            JsonObject scim = toScim(record);
            if (request.filter() == null || evaluator.matches(request.filter(), scim)) matched.add(scim);
        }
        int from = Math.min(request.startIndex() - 1, matched.size());
        int to = Math.min(from + request.count(), matched.size());
        return new SearchResult(new ArrayList<>(matched.subList(from, to)), matched.size());
    }

    @Override
    public JsonObject patch(String id, List<PatchOp> ops) {
        JsonObject record = store.find(table.idColumn, id);
        if (record == null) throw BackendException.notFound("Group", id);
        Set<String> members = new LinkedHashSet<>(memberIds(record));
        String name = record.optString(table.displayNameColumn, null);
        boolean handled = true;
        for (PatchOp op : ops) {
            AttributePath p = op.path();
            if (p != null && p.schemaUrn() == null && p.attr().equalsIgnoreCase("displayName")
                    && p.subAttr() == null && op.type() != PatchOp.Type.REMOVE
                    && op.value() instanceof JsonString s) {
                name = s.value();
            } else if (p != null && p.schemaUrn() == null && p.attr().equalsIgnoreCase("members")
                    && p.subAttr() == null) {
                if (op.type() == PatchOp.Type.ADD && p.valueFilter() == null && op.value() != null) {
                    members.addAll(idsFrom(op.value()));
                } else if (op.type() == PatchOp.Type.REMOVE && p.valueFilter() == null
                        && op.value() instanceof JsonArray a) {
                    idsFrom(a).forEach(members::remove);
                } else if (op.type() == PatchOp.Type.REMOVE && p.valueFilter() != null) {
                    String uid = singleEq(p.valueFilter());
                    if (uid == null) { handled = false; break; }
                    members.remove(uid);
                } else { handled = false; break; }
            } else { handled = false; break; }
        }
        if (!handled) {
            JsonObject current = toScim(record);
            patchEngine.apply(current, ops);
            return replace(id, current);
        }
        record.put(table.displayNameColumn, name);
        putMembers(record, new ArrayList<>(members));
        record.put("meta_lastModified", Instant.now().toString());
        store.replace(table.idColumn, id, record);
        return get(id);
    }

    // ---- helpers ----

    private List<String> memberIdsFrom(JsonObject resource) {
        return resource.getIgnoreCase("members") instanceof JsonArray a ? idsFrom(a) : List.of();
    }

    private static List<String> idsFrom(JsonValue value) {
        JsonArray arr = value instanceof JsonArray a ? a : new JsonArray().add(value);
        List<String> ids = new ArrayList<>();
        for (JsonValue m : arr) {
            if (m instanceof JsonObject o) {
                String v = o.optString("value", null);
                if (v != null) { ids.add(v); continue; }
            }
            if (m instanceof JsonString s) ids.add(s.value());
            else throw new BackendException(400, "invalidValue", "group member entries need a 'value'");
        }
        return ids;
    }

    private static String singleEq(Filter f) {
        if (f instanceof Filter.Compare c && c.op() == Op.EQ
                && c.path().attr().equalsIgnoreCase("value") && c.literal() instanceof JsonString s) {
            return s.value();
        }
        return null;
    }
}
