// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.file;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.CoreSchemas;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.Capabilities;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.ScimBackend;
import com.pointblue.scim.spi.ScimBackendProvider;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File backend: SCIM Users (and optionally Groups) stored in CSV or JSON files.
 * Useful as a read source for an existing export, or a sink that materializes
 * provisioning as a file drop for downstream systems. Whole-file, in-memory,
 * with atomic writes — sized for provisioning volumes, not high concurrency.
 */
public final class FileBackend implements ScimBackend {

    private final FileConfig config;
    private final FileUsers users;
    private final FileGroups groups;

    public FileBackend(JsonObject backendConfig, SchemaRegistry registry) {
        this.config = new FileConfig(backendConfig);
        RecordFile userFile = new RecordFile(Path.of(config.users.file), config.format);
        FileMapper userMapper = new FileMapper(CoreSchemas.USER_URN, "User", config.users.columns, registry);
        this.users = new FileUsers(userFile, config.users, userMapper, "User");
        if (config.groups != null) {
            RecordFile groupFile = new RecordFile(Path.of(config.groups.file), config.format);
            this.groups = new FileGroups(groupFile, config.groups, config.format, users, registry);
        } else {
            this.groups = null;
        }
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, true, false, 1000, 100_000);
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
        Path dir = Path.of(config.users.file).toAbsolutePath().getParent();
        if (dir != null && !Files.isDirectory(dir)) {
            throw new BackendException(503, null, "users file directory does not exist: " + dir);
        }
    }

    public static final class Provider implements ScimBackendProvider {
        @Override public String name() { return "file"; }
        @Override public ScimBackend create(JsonObject backendConfig, SchemaRegistry registry) {
            return new FileBackend(backendConfig, registry);
        }
    }
}
