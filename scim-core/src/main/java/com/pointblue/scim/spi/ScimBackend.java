package com.pointblue.scim.spi;

/** A connected backend instance (one target system). */
public interface ScimBackend extends AutoCloseable {

    Capabilities capabilities();

    /** Handler for a resource type name ("User", "Group"), or null if unsupported. */
    ResourceBackend resource(String resourceTypeName);

    /** Cheap connectivity probe; used at startup and by /healthz. */
    void healthCheck();

    @Override
    default void close() {}
}
