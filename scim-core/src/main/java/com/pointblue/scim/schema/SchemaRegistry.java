// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.schema;

import com.pointblue.scim.model.AttributePath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds all known schemas and resource-type bindings. Populated at startup
 * (built-ins plus configured extensions); read-only afterwards.
 */
public final class SchemaRegistry {

    private final Map<String, Schema> schemasByUrn = new LinkedHashMap<>();       // key: lowercase urn
    private final Map<String, ResourceType> resourceTypes = new LinkedHashMap<>(); // key: lowercase name

    public static SchemaRegistry withDefaults() {
        SchemaRegistry r = new SchemaRegistry();
        r.register(CoreSchemas.user());
        r.register(CoreSchemas.group());
        r.register(CoreSchemas.enterpriseUser());
        r.bind(new ResourceType("User", "/Users", CoreSchemas.USER_URN,
                List.of(new ResourceType.Extension(CoreSchemas.ENTERPRISE_USER_URN, false))));
        r.bind(new ResourceType("Group", "/Groups", CoreSchemas.GROUP_URN, List.of()));
        return r;
    }

    public void register(Schema schema) {
        schemasByUrn.put(schema.id().toLowerCase(), schema);
    }

    public void bind(ResourceType resourceType) {
        if (schema(resourceType.baseSchema()) == null) {
            throw new IllegalArgumentException("unknown base schema " + resourceType.baseSchema());
        }
        for (ResourceType.Extension e : resourceType.extensions()) {
            if (schema(e.schema()) == null) {
                throw new IllegalArgumentException("unknown extension schema " + e.schema());
            }
        }
        resourceTypes.put(resourceType.name().toLowerCase(), resourceType);
    }

    /** Adds an extension to an already-bound resource type (config-driven). */
    public void addExtension(String resourceTypeName, String schemaUrn, boolean required) {
        ResourceType rt = resourceType(resourceTypeName);
        if (rt == null) throw new IllegalArgumentException("unknown resource type " + resourceTypeName);
        if (schema(schemaUrn) == null) throw new IllegalArgumentException("unknown extension schema " + schemaUrn);
        List<ResourceType.Extension> exts = new ArrayList<>(rt.extensions());
        exts.add(new ResourceType.Extension(schemaUrn, required));
        resourceTypes.put(rt.name().toLowerCase(),
                new ResourceType(rt.name(), rt.endpoint(), rt.baseSchema(), List.copyOf(exts)));
    }

    public Schema schema(String urn) {
        return urn == null ? null : schemasByUrn.get(urn.toLowerCase());
    }

    public List<Schema> allSchemas() { return List.copyOf(schemasByUrn.values()); }

    public ResourceType resourceType(String name) {
        return name == null ? null : resourceTypes.get(name.toLowerCase());
    }

    public List<ResourceType> allResourceTypes() { return List.copyOf(resourceTypes.values()); }

    /** True if the urn is the base schema or a bound extension of the resource type. */
    public boolean isSchemaOf(ResourceType rt, String urn) {
        if (rt.baseSchema().equalsIgnoreCase(urn)) return true;
        for (ResourceType.Extension e : rt.extensions()) {
            if (e.schema().equalsIgnoreCase(urn)) return true;
        }
        return false;
    }

    /**
     * Resolves an attribute path to its definition for the given resource type.
     * Returns the sub-attribute definition when the path names one; null when unknown.
     */
    public Attribute resolve(String resourceTypeName, AttributePath path) {
        ResourceType rt = resourceType(resourceTypeName);
        if (rt == null) return null;
        Schema schema;
        if (path.schemaUrn() == null || rt.baseSchema().equalsIgnoreCase(path.schemaUrn())) {
            schema = schema(rt.baseSchema());
            // an unprefixed attribute may also live in an extension (uncommon but legal in filters)
            Attribute fromBase = schema == null ? null : schema.attribute(path.attr());
            if (fromBase == null && path.schemaUrn() == null) {
                for (ResourceType.Extension e : rt.extensions()) {
                    Schema ext = schema(e.schema());
                    Attribute a = ext == null ? null : ext.attribute(path.attr());
                    if (a != null) return descend(a, path);
                }
            }
            return fromBase == null ? null : descend(fromBase, path);
        }
        if (!isSchemaOf(rt, path.schemaUrn())) return null;
        schema = schema(path.schemaUrn());
        Attribute a = schema == null ? null : schema.attribute(path.attr());
        return a == null ? null : descend(a, path);
    }

    private Attribute descend(Attribute attr, AttributePath path) {
        if (path.subAttr() == null) return attr;
        return attr.subAttribute(path.subAttr());
    }
}
