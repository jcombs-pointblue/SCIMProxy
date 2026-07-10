package com.pointblue.scim.server.endpoints;

import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the attributes / excludedAttributes request parameters (RFC 7644 §3.9).
 * id and schemas are always returned.
 */
final class Projection {

    static final Projection NONE = new Projection(null, null, null);

    private final List<AttributePath> include; // null = no attributes param
    private final List<AttributePath> exclude;
    private final String baseUrn;

    Projection(String attributesParam, String excludedParam, String baseUrn) {
        this.include = parseList(attributesParam);
        this.exclude = parseList(excludedParam);
        this.baseUrn = baseUrn;
    }

    boolean isNoOp() { return include == null && exclude == null; }

    private static List<AttributePath> parseList(String param) {
        if (param == null || param.isBlank()) return null;
        List<AttributePath> paths = new ArrayList<>();
        for (String part : param.split(",")) {
            if (!part.isBlank()) paths.add(FilterParser.parsePath(part.trim()));
        }
        return paths.isEmpty() ? null : paths;
    }

    void apply(JsonObject resource) {
        if (include != null) applyInclude(resource);
        else if (exclude != null) {
            for (AttributePath p : exclude) removePath(resource, p);
        }
    }

    private void applyInclude(JsonObject resource) {
        for (String key : List.copyOf(resource.keys())) {
            if (key.equalsIgnoreCase("id") || key.equalsIgnoreCase("schemas")) continue;
            if (key.indexOf(':') >= 0) {
                boolean namespaceRequested = false;
                if (resource.get(key) instanceof JsonObject extObj) {
                    List<String> keep = new ArrayList<>();
                    for (AttributePath p : include) {
                        if (p.schemaUrn() != null && p.schemaUrn().equalsIgnoreCase(key)) {
                            namespaceRequested = true;
                            keep.add(p.attr());
                        }
                    }
                    if (namespaceRequested) {
                        for (String extKey : List.copyOf(extObj.keys())) {
                            boolean found = keep.stream().anyMatch(k -> k.equalsIgnoreCase(extKey));
                            if (!found) extObj.remove(extKey);
                        }
                        continue;
                    }
                }
                resource.remove(key);
            } else {
                boolean requested = include.stream().anyMatch(p ->
                        (p.schemaUrn() == null || p.schemaUrn().equalsIgnoreCase(baseUrn))
                                && p.attr().equalsIgnoreCase(key));
                if (!requested && !key.equalsIgnoreCase("meta")) resource.remove(key);
            }
        }
    }

    private void removePath(JsonObject resource, AttributePath p) {
        if (p.attr().equalsIgnoreCase("id") || p.attr().equalsIgnoreCase("schemas")) return;
        JsonObject container = resource;
        if (p.schemaUrn() != null && !p.schemaUrn().equalsIgnoreCase(baseUrn)) {
            if (!(resource.getIgnoreCase(p.schemaUrn()) instanceof JsonObject ext)) return;
            container = ext;
        }
        if (p.subAttr() == null) {
            container.removeIgnoreCase(p.attr());
            return;
        }
        JsonValue v = container.getIgnoreCase(p.attr());
        if (v instanceof JsonObject o) o.removeIgnoreCase(p.subAttr());
        else if (v instanceof JsonArray arr) {
            for (JsonValue e : arr) {
                if (e instanceof JsonObject o) o.removeIgnoreCase(p.subAttr());
            }
        }
    }
}
