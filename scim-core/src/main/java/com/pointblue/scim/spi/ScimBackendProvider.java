package com.pointblue.scim.spi;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.SchemaRegistry;

/**
 * ServiceLoader entry point. A connector jar exposes one provider; the server
 * selects it by {@code backend.type} in configuration.
 */
public interface ScimBackendProvider {

    /** Configuration name, e.g. "okta", "memory". */
    String name();

    ScimBackend create(JsonObject backendConfig, SchemaRegistry registry);
}
