package com.pointblue.scim.jdbc;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.CoreSchemas;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.Capabilities;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.ScimBackend;
import com.pointblue.scim.spi.ScimBackendProvider;

/**
 * Generic JDBC/SQL backend: maps SCIM Users (and optionally Groups) onto
 * database tables. PATCH is emulated by the core via get-apply-replace.
 */
public final class JdbcBackend implements ScimBackend {

    private final Db db;
    private final JdbcUsers users;
    private final JdbcGroups groups;

    public JdbcBackend(JsonObject backendConfig, SchemaRegistry registry) {
        JdbcConfig config = new JdbcConfig(backendConfig);
        this.db = new Db(config.url, config.user, config.password);
        ColumnMap userMap = new ColumnMap(CoreSchemas.USER_URN, config.users.columns,
                db.columnTypes(config.users.name));
        this.users = new JdbcUsers(db, config.users, userMap, "User");
        this.groups = config.groups == null ? null
                : new JdbcGroups(db, config.groups, users, registry);
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, true, false, 500, 50_000);
    }

    @Override
    public ResourceBackend resource(String resourceTypeName) {
        return switch (resourceTypeName) {
            case "User" -> users;
            case "Group" -> groups;
            default -> null;
        };
    }

    @Override
    public void healthCheck() {
        db.withConnection(c -> {
            try (var st = c.createStatement()) {
                st.execute("SELECT 1");
            }
            return null;
        });
    }

    public static final class Provider implements ScimBackendProvider {
        @Override public String name() { return "jdbc"; }
        @Override public ScimBackend create(JsonObject backendConfig, SchemaRegistry registry) {
            return new JdbcBackend(backendConfig, registry);
        }
    }
}
