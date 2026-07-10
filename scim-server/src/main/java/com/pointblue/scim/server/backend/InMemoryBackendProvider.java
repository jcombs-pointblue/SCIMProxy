// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.backend;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.ScimBackend;
import com.pointblue.scim.spi.ScimBackendProvider;

public final class InMemoryBackendProvider implements ScimBackendProvider {

    @Override public String name() { return "memory"; }

    @Override
    public ScimBackend create(JsonObject backendConfig, SchemaRegistry registry) {
        return new InMemoryBackend(registry);
    }
}
