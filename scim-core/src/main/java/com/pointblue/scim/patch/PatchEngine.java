package com.pointblue.scim.patch;

import com.pointblue.scim.ScimException;
import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.FilterEvaluator;
import com.pointblue.scim.filter.Op;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.schema.Attribute;
import com.pointblue.scim.schema.ResourceType;
import com.pointblue.scim.schema.SchemaRegistry;

import java.util.List;

/**
 * Applies RFC 7644 §3.5.2 PATCH operations to a resource's JSON tree, in place.
 * Callers re-validate the resource afterwards. Implemented once here so every
 * backend gets correct PATCH behavior via get-apply-replace, and connectors
 * with native partial-update APIs can still pattern-match the parsed ops.
 */
public final class PatchEngine {

    private final SchemaRegistry registry;
    private final String resourceTypeName;
    private final FilterEvaluator evaluator;

    public PatchEngine(SchemaRegistry registry, String resourceTypeName) {
        this.registry = registry;
        this.resourceTypeName = resourceTypeName;
        ResourceType rt = registry.resourceType(resourceTypeName);
        if (rt == null) throw new IllegalArgumentException("unknown resource type " + resourceTypeName);
        this.evaluator = new FilterEvaluator(rt.baseSchema(), path -> {
            Attribute a = registry.resolve(resourceTypeName, path);
            return a != null && a.caseExact();
        });
    }

    public void apply(JsonObject resource, List<PatchOp> ops) {
        for (PatchOp op : ops) applyOne(resource, op);
    }

    private void applyOne(JsonObject resource, PatchOp op) {
        switch (op.type()) {
            case ADD -> {
                if (op.path() == null) mergeNoPath(resource, op.value(), false);
                else addOrReplaceAtPath(resource, op.path(), op.value(), false);
            }
            case REPLACE -> {
                if (op.path() == null) mergeNoPath(resource, op.value(), true);
                else addOrReplaceAtPath(resource, op.path(), op.value(), true);
            }
            case REMOVE -> {
                if (op.path() == null) throw ScimException.noTarget("remove requires a path");
                removeAtPath(resource, op.path(), op.value());
            }
        }
    }

    // ---- no-path add/replace: value is an object of attributes to merge ----

    private void mergeNoPath(JsonObject resource, JsonValue value, boolean replace) {
        if (!(value instanceof JsonObject obj)) {
            throw ScimException.invalidValue("value for a path-less operation must be an object");
        }
        obj.forEach((key, v) -> {
            if (key.equalsIgnoreCase("schemas") || key.equalsIgnoreCase("id") || key.equalsIgnoreCase("meta")) return;
            if (key.indexOf(':') >= 0) {
                if (!(v instanceof JsonObject extVal)) {
                    throw ScimException.invalidValue("extension namespace " + key + " must be an object");
                }
                JsonValue existing = resource.getIgnoreCase(key);
                JsonObject target;
                if (existing instanceof JsonObject e) {
                    target = e;
                } else {
                    target = new JsonObject();
                    resource.put(key, target);
                }
                extVal.forEach((subKey, subVal) ->
                        mergeAttr(target, new AttributePath(key, subKey, null, null), subVal, replace));
            } else {
                mergeAttr(resource, AttributePath.of(key), v, replace);
            }
        });
    }

    private void mergeAttr(JsonObject container, AttributePath path, JsonValue value, boolean replace) {
        Attribute attr = resolveOrFail(path);
        if (attr.mutability() == Attribute.Mutability.READ_ONLY) return; // silently ignored on merge
        String key = container.findKey(path.attr()).orElse(attr.name());
        if (value.isNull()) { container.remove(key); return; }
        if (attr.multiValued() && !replace) {
            appendMulti(container, key, value);
        } else {
            container.put(key, value.copy());
        }
    }

    // ---- path-targeted add/replace ----

    private void addOrReplaceAtPath(JsonObject resource, AttributePath path, JsonValue value, boolean replace) {
        Attribute attr = resolveOrFail(path);
        checkMutable(attr, path, resource, replace);
        JsonObject container = containerForWrite(resource, path);
        String key = container.findKey(path.attr()).orElse(null);
        JsonValue current = key == null ? null : container.get(key);
        if (key == null) key = topLevelAttrName(path);

        if (path.valueFilter() == null && path.subAttr() == null) {
            if (value.isNull()) { container.remove(key); return; }
            if (attrIsMultiValued(path) && !replace) appendMulti(container, key, value);
            else container.put(key, value.copy());
            return;
        }

        if (path.valueFilter() == null && path.subAttr() != null) {
            // attr.subAttr — set on the complex value, or on every element when multi-valued
            if (attrIsMultiValued(path)) {
                if (!(current instanceof JsonArray arr) || arr.isEmpty()) {
                    if (replace) throw ScimException.noTarget("no values at " + path.display());
                    JsonObject element = new JsonObject();
                    setOrRemove(element, path.subAttr(), value);
                    JsonArray created = new JsonArray();
                    created.add(element);
                    container.put(key, created);
                    return;
                }
                for (JsonValue e : arr) {
                    if (e instanceof JsonObject o) setOrRemove(o, path.subAttr(), value);
                }
            } else {
                JsonObject complexVal;
                if (current instanceof JsonObject o) complexVal = o;
                else { complexVal = new JsonObject(); container.put(key, complexVal); }
                setOrRemove(complexVal, path.subAttr(), value);
            }
            return;
        }

        // valueFilter present: select elements
        JsonArray arr;
        if (current instanceof JsonArray a) {
            arr = a;
        } else {
            if (replace) throw ScimException.noTarget("no values match filter at " + path.display());
            arr = new JsonArray();
            container.put(key, arr);
        }
        int matched = 0;
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i) instanceof JsonObject element
                    && evaluator.matchesElement(path.valueFilter(), element)) {
                matched++;
                if (path.subAttr() == null) arr.set(i, value.copy());
                else setOrRemove(element, path.subAttr(), value);
            }
        }
        if (matched == 0) {
            if (replace) throw ScimException.noTarget("no values match filter at " + path.display());
            // add with a non-matching filter: create an element seeded from eq clauses (common client idiom)
            JsonObject seeded = seedFromFilter(path.valueFilter());
            if (seeded == null) throw ScimException.noTarget("no values match filter at " + path.display());
            if (path.subAttr() == null) {
                if (value instanceof JsonObject vo) vo.forEach((k, v) -> seeded.put(k, v.copy()));
                else throw ScimException.invalidValue("value for " + path.display() + " must be an object");
            } else {
                setOrRemove(seeded, path.subAttr(), value);
            }
            arr.add(seeded);
        }
    }

    // ---- remove ----

    private void removeAtPath(JsonObject resource, AttributePath path, JsonValue value) {
        Attribute attr = resolveOrFail(path);
        checkMutable(attr, path, resource, true);
        JsonObject container = evaluator.container(resource, path);
        String key = container == null ? null : container.findKey(path.attr()).orElse(null);
        if (key == null) throw ScimException.noTarget("no value at " + path.display());
        JsonValue current = container.get(key);

        if (path.valueFilter() == null && path.subAttr() == null) {
            // "remove with value" idiom for multi-valued attributes (members etc.)
            if (value != null && !value.isNull() && current instanceof JsonArray arr) {
                removeListedValues(arr, value);
                if (arr.isEmpty()) container.remove(key);
            } else {
                container.remove(key);
            }
            return;
        }

        if (path.valueFilter() == null && path.subAttr() != null) {
            if (current instanceof JsonArray arr) {
                for (JsonValue e : arr) {
                    if (e instanceof JsonObject o) o.removeIgnoreCase(path.subAttr());
                }
            } else if (current instanceof JsonObject o) {
                o.removeIgnoreCase(path.subAttr());
                if (o.isEmpty()) container.remove(key);
            }
            return;
        }

        if (!(current instanceof JsonArray arr)) throw ScimException.noTarget("no values at " + path.display());
        int matched = 0;
        for (int i = arr.size() - 1; i >= 0; i--) {
            if (arr.get(i) instanceof JsonObject element
                    && evaluator.matchesElement(path.valueFilter(), element)) {
                matched++;
                if (path.subAttr() == null) arr.removeAt(i);
                else element.removeIgnoreCase(path.subAttr());
            }
        }
        if (matched == 0) throw ScimException.noTarget("no values match filter at " + path.display());
        if (arr.isEmpty()) container.remove(key);
    }

    /** Removes array elements matching the remove-op value (single or list of {value:...} objects). */
    private void removeListedValues(JsonArray arr, JsonValue value) {
        JsonArray toRemove = value instanceof JsonArray a ? a : new JsonArray().add(value);
        for (JsonValue spec : toRemove) {
            for (int i = arr.size() - 1; i >= 0; i--) {
                JsonValue e = arr.get(i);
                boolean match;
                if (spec instanceof JsonObject specObj && e instanceof JsonObject eo) {
                    match = !specObj.isEmpty() && subsetMatches(specObj, eo);
                } else {
                    match = FilterEvaluator.compare(e, Op.EQ, spec, false);
                }
                if (match) arr.removeAt(i);
            }
        }
    }

    private boolean subsetMatches(JsonObject spec, JsonObject element) {
        boolean[] all = {true};
        spec.forEach((k, v) -> {
            if (k.equals("$ref")) return; // clients echo $ref inconsistently
            JsonValue actual = element.getIgnoreCase(k);
            if (actual == null || !FilterEvaluator.compare(actual, Op.EQ, v, false)) all[0] = false;
        });
        return all[0];
    }

    // ---- helpers ----

    private Attribute resolveOrFail(AttributePath path) {
        Attribute a = registry.resolve(resourceTypeName, path);
        if (a == null) throw ScimException.invalidPath("unknown attribute path: " + path.display());
        return a;
    }

    /** Multi-valued-ness of the *top-level* attribute in the path (resolve() returns the sub-attr def). */
    private boolean attrIsMultiValued(AttributePath path) {
        Attribute top = registry.resolve(resourceTypeName,
                new AttributePath(path.schemaUrn(), path.attr(), null, null));
        return top != null && top.multiValued();
    }

    private String topLevelAttrName(AttributePath path) {
        Attribute top = registry.resolve(resourceTypeName,
                new AttributePath(path.schemaUrn(), path.attr(), null, null));
        return top != null ? top.name() : path.attr();
    }

    private void checkMutable(Attribute attr, AttributePath path, JsonObject resource, boolean modifying) {
        if (attr.mutability() == Attribute.Mutability.READ_ONLY) {
            throw ScimException.mutability("attribute '" + path.display() + "' is readOnly");
        }
        if (attr.mutability() == Attribute.Mutability.IMMUTABLE && modifying
                && !evaluator.resolve(resource, path).isEmpty()) {
            throw ScimException.mutability("attribute '" + path.display() + "' is immutable");
        }
    }

    private JsonObject containerForWrite(JsonObject resource, AttributePath path) {
        JsonObject container = evaluator.container(resource, path);
        if (container != null) return container;
        // extension namespace not yet present: create it
        JsonObject ext = new JsonObject();
        resource.put(path.schemaUrn(), ext);
        return ext;
    }

    private void appendMulti(JsonObject container, String key, JsonValue value) {
        JsonValue current = container.get(key);
        JsonArray arr;
        if (current instanceof JsonArray a) arr = a;
        else {
            arr = new JsonArray();
            if (current != null && !current.isNull()) arr.add(current);
            container.put(key, arr);
        }
        if (value instanceof JsonArray additions) {
            for (JsonValue v : additions) arr.add(v.copy());
        } else {
            arr.add(value.copy());
        }
    }

    private void setOrRemove(JsonObject obj, String subAttr, JsonValue value) {
        String key = obj.findKey(subAttr).orElse(subAttr);
        if (value.isNull()) obj.remove(key);
        else obj.put(key, value.copy());
    }

    /** Builds an element from top-level `sub eq literal` conjunctions of a value filter, or null. */
    private JsonObject seedFromFilter(Filter filter) {
        JsonObject seed = new JsonObject();
        return collectEq(filter, seed) ? seed : null;
    }

    private boolean collectEq(Filter filter, JsonObject seed) {
        return switch (filter) {
            case Filter.And and -> collectEq(and.left(), seed) && collectEq(and.right(), seed);
            case Filter.Compare c when c.op() == Op.EQ
                    && c.path().schemaUrn() == null && c.path().subAttr() == null -> {
                seed.put(c.path().attr(), c.literal().copy());
                yield true;
            }
            default -> false;
        };
    }
}
