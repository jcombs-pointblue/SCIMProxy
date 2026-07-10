// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.ldap;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.Capabilities;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.ScimBackend;
import com.pointblue.scim.spi.ScimBackendProvider;

/**
 * LDAP directory backend. One connector covers eDirectory, Active Directory,
 * and OpenLDAP via flavor presets; see LdapConfig.
 */
public final class LdapBackend implements ScimBackend {

    private final LdapConfig config;
    private final LdapStore store;
    private final LdapUsers users;
    private final LdapGroups groups;

    public LdapBackend(JsonObject backendConfig, SchemaRegistry registry) {
        this.config = new LdapConfig(backendConfig);
        this.store = new LdapStore(new LdapConnections(config), config);
        LdapMapper mapper = new LdapMapper(config.userMappings);
        this.users = new LdapUsers(store, mapper, config, registry);
        this.groups = new LdapGroups(store, users, config, registry);
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, false, config.pageSize, 10_000);
    }

    @Override
    public ResourceBackend resource(String resourceTypeName) {
        return switch (resourceTypeName) {
            case "User" -> users;
            case "Group" -> config.groupBaseDn == null ? null : groups;
            default -> null;
        };
    }

    @Override
    public void healthCheck() {
        store.lookup(config.userBaseDn, new String[] {"objectClass"});
    }

    public static final class Provider implements ScimBackendProvider {
        @Override public String name() { return "ldap"; }
        @Override public ScimBackend create(JsonObject backendConfig, SchemaRegistry registry) {
            return new LdapBackend(backendConfig, registry);
        }
    }
}
