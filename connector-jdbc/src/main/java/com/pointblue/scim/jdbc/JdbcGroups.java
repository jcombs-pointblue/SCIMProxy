package com.pointblue.scim.jdbc;

import com.pointblue.scim.filter.Filter;
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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** /Groups backed by a groups table plus a membership join table. */
final class JdbcGroups implements ResourceBackend {

    private final Db db;
    private final JdbcConfig.Table table;
    private final JdbcConfig.Membership membership;
    private final JdbcUsers users;
    private final PatchEngine patchEngine;
    private final int idType;
    private final String displayNameCol;

    JdbcGroups(Db db, JdbcConfig.Table table, JdbcUsers users, SchemaRegistry registry) {
        this.db = db;
        this.table = table;
        this.membership = table.membership;
        this.users = users;
        this.patchEngine = new PatchEngine(registry, "Group");
        this.idType = users.columns().sqlType(table.idColumn);
        if (table.displayNameColumn == null) {
            throw new IllegalArgumentException("groups config requires 'displayNameColumn'");
        }
        this.displayNameCol = table.displayNameColumn;
        if (membership == null) {
            throw new IllegalArgumentException("groups config requires a 'membership' section");
        }
    }

    private JsonObject toScim(String id, String displayName, JsonValue created, JsonValue modified,
                              JsonArray members) {
        JsonObject g = new JsonObject();
        g.put("schemas", new JsonArray().add(CoreSchemas.GROUP_URN));
        g.put("id", id);
        g.put("displayName", displayName);
        if (members != null) g.put("members", members);
        JsonObject meta = new JsonObject();
        meta.put("resourceType", "Group");
        if (created != null && !created.isNull()) meta.put("created", created.asString());
        if (modified != null && !modified.isNull()) meta.put("lastModified", modified.asString());
        g.put("meta", meta);
        return g;
    }

    private JsonArray fetchMembers(String groupId) {
        String userTable = users.table().name;
        String userId = users.table().idColumn;
        String userName = users.columns().column(AttributePath.of("userName"));
        String displaySelect = userName != null ? ", u." + userName : "";
        String sql = "SELECT u." + userId + displaySelect
                + " FROM " + membership.table() + " m JOIN " + userTable + " u"
                + " ON m." + membership.userColumn() + " = u." + userId
                + " WHERE m." + membership.groupColumn() + " = ?";
        return db.withConnection(c -> {
            JsonArray members = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Db.bind(ps, 1, idType, JsonValue.of(groupId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject m = new JsonObject();
                        m.put("value", rs.getObject(1).toString());
                        if (userName != null && rs.getObject(2) != null) m.put("display", rs.getObject(2).toString());
                        members.add(m);
                    }
                }
            }
            return members;
        });
    }

    // ---- CRUD ----

    @Override
    public JsonObject create(JsonObject resource) {
        String name = resource.optString("displayName", null);
        if (name == null) throw new BackendException(400, "invalidValue", "displayName is required");
        List<String> memberIds = memberIds(resource);
        String id = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + table.name + " (" + displayNameCol + ") VALUES (?)",
                    new String[] {table.idColumn})) {
                ps.setString(1, name);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new BackendException(502, null, "insert returned no generated id");
                    return keys.getObject(1).toString();
                }
            }
        });
        for (String uid : memberIds) addMember(id, uid);
        return get(id);
    }

    @Override
    public JsonObject get(String id) {
        JsonObject g = fetchGroup(id);
        if (g == null) throw BackendException.notFound("Group", id);
        return g;
    }

    private JsonObject fetchGroup(String id) {
        String created = table.createdColumn, modified = table.modifiedColumn;
        String cols = displayNameCol
                + (created != null ? ", " + created : "")
                + (modified != null ? ", " + modified : "");
        String sql = "SELECT " + cols + " FROM " + table.name + " WHERE " + table.idColumn + " = ?";
        JsonObject row = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Db.bind(ps, 1, idType, JsonValue.of(id));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    JsonObject o = new JsonObject();
                    o.put("displayName", rs.getString(1));
                    int idx = 2;
                    o.put("created", created != null ? Db.read(rs, created, users.columns().sqlType(created)) : com.pointblue.scim.json.JsonNull.NULL);
                    o.put("modified", modified != null ? Db.read(rs, modified, users.columns().sqlType(modified)) : com.pointblue.scim.json.JsonNull.NULL);
                    return o;
                }
            }
        });
        if (row == null) return null;
        return toScim(id, row.optString("displayName", null),
                row.get("created"), row.get("modified"), fetchMembers(id));
    }

    @Override
    public JsonObject replace(String id, JsonObject resource) {
        String name = resource.optString("displayName", null);
        if (name != null) {
            String setModified = table.modifiedColumn != null ? ", " + table.modifiedColumn + " = now()" : "";
            int updated = db.withConnection(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + table.name + " SET " + displayNameCol + " = ?" + setModified
                                + " WHERE " + table.idColumn + " = ?")) {
                    ps.setString(1, name);
                    Db.bind(ps, 2, idType, JsonValue.of(id));
                    return ps.executeUpdate();
                }
            });
            if (updated == 0) throw BackendException.notFound("Group", id);
        }
        // reconcile membership
        Set<String> desired = new LinkedHashSet<>(memberIds(resource));
        Set<String> current = new LinkedHashSet<>();
        for (JsonValue m : fetchMembers(id)) current.add(m.asObject().optString("value", ""));
        for (String uid : desired) if (!current.contains(uid)) addMember(id, uid);
        for (String uid : current) if (!desired.contains(uid)) removeMember(id, uid);
        return get(id);
    }

    @Override
    public void delete(String id) {
        int deleted = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM " + table.name + " WHERE " + table.idColumn + " = ?")) {
                Db.bind(ps, 1, idType, JsonValue.of(id));
                return ps.executeUpdate();
            }
        });
        if (deleted == 0) throw BackendException.notFound("Group", id);
    }

    @Override
    public SearchResult search(SearchRequest request) {
        String created = table.createdColumn, modified = table.modifiedColumn;
        String cols = table.idColumn + ", " + displayNameCol
                + (created != null ? ", " + created : "")
                + (modified != null ? ", " + modified : "");
        String where = "";
        String nameParam = null;
        if (request.filter() instanceof Filter.Compare c && c.op() == Op.EQ
                && c.path().attr().equalsIgnoreCase("displayName") && c.literal() instanceof JsonString s) {
            where = " WHERE lower(" + displayNameCol + ") = lower(?)";
            nameParam = s.value();
        } else if (request.filter() != null) {
            throw new com.pointblue.scim.spi.UnsupportedFilterException("only displayName eq is pushed down for groups");
        }
        String whereF = where;
        String np = nameParam;
        int offset = request.startIndex() - 1, limit = request.count();
        return db.withConnection(conn -> {
            long total;
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM " + table.name + whereF)) {
                if (np != null) ps.setString(1, np);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); total = rs.getLong(1); }
            }
            List<JsonObject> groups = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + cols + " FROM " + table.name + whereF
                            + " ORDER BY " + table.idColumn + " LIMIT ? OFFSET ?")) {
                int i = 1;
                if (np != null) ps.setString(i++, np);
                ps.setInt(i++, Math.max(limit, 0));
                ps.setInt(i, Math.max(offset, 0));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getObject(table.idColumn).toString();
                        // members are materialized per group (RFC 7644 default); fine for lists here
                        groups.add(toScim(id, rs.getString(displayNameCol),
                                created != null ? Db.read(rs, created, users.columns().sqlType(created)) : null,
                                modified != null ? Db.read(rs, modified, users.columns().sqlType(modified)) : null,
                                fetchMembers(id)));
                    }
                }
            }
            return new SearchResult(groups, total);
        });
    }

    @Override
    public JsonObject patch(String id, List<PatchOp> ops) {
        List<Runnable> actions = new ArrayList<>();
        for (PatchOp op : ops) {
            Runnable a = nativeAction(id, op);
            if (a == null) {
                JsonObject current = get(id);
                patchEngine.apply(current, ops);
                return replace(id, current);
            }
            actions.add(a);
        }
        actions.forEach(Runnable::run);
        return get(id);
    }

    private Runnable nativeAction(String id, PatchOp op) {
        AttributePath p = op.path();
        if (p == null || p.schemaUrn() != null) return null;
        if (p.attr().equalsIgnoreCase("displayName")) return null; // via replace
        if (!p.attr().equalsIgnoreCase("members") || p.subAttr() != null) return null;
        if (op.type() == PatchOp.Type.ADD && p.valueFilter() == null && op.value() != null) {
            List<String> ids = idsFrom(op.value());
            return () -> ids.forEach(uid -> addMember(id, uid));
        }
        if (op.type() == PatchOp.Type.REMOVE && p.valueFilter() == null && op.value() instanceof JsonArray a) {
            List<String> ids = idsFrom(a);
            return () -> ids.forEach(uid -> removeMember(id, uid));
        }
        if (op.type() == PatchOp.Type.REMOVE && p.valueFilter() != null) {
            String uid = singleEq(p.valueFilter());
            if (uid == null) return null;
            return () -> removeMember(id, uid);
        }
        return null;
    }

    // ---- membership ----

    private void addMember(String groupId, String userId) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + membership.table() + " (" + membership.groupColumn() + ", "
                            + membership.userColumn() + ") VALUES (?, ?)")) {
                Db.bind(ps, 1, idType, JsonValue.of(groupId));
                Db.bind(ps, 2, users.columns().sqlType(users.table().idColumn), JsonValue.of(userId));
                ps.executeUpdate();
            } catch (SQLException e) {
                if (!"23505".equals(e.getSQLState())) throw e; // ignore duplicate membership
            }
            return null;
        });
    }

    private void removeMember(String groupId, String userId) {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM " + membership.table() + " WHERE " + membership.groupColumn()
                            + " = ? AND " + membership.userColumn() + " = ?")) {
                Db.bind(ps, 1, idType, JsonValue.of(groupId));
                Db.bind(ps, 2, users.columns().sqlType(users.table().idColumn), JsonValue.of(userId));
                ps.executeUpdate();
            }
            return null;
        });
    }

    private List<String> memberIds(JsonObject resource) {
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
