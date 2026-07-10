package com.pointblue.scim.patch;

import com.pointblue.scim.ScimException;
import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.schema.CoreSchemas;

import java.util.ArrayList;
import java.util.List;

/** One operation of a PatchOp message. */
public record PatchOp(Type type, AttributePath path, JsonValue value) {

    public enum Type { ADD, REMOVE, REPLACE }

    /** Parses an urn:ietf:params:scim:api:messages:2.0:PatchOp request body. */
    public static List<PatchOp> parseAll(JsonObject body) {
        JsonValue schemas = body.getIgnoreCase("schemas");
        boolean declared = false;
        if (schemas instanceof JsonArray arr) {
            for (JsonValue v : arr) {
                if (v.isString() && v.asString().equalsIgnoreCase(CoreSchemas.PATCH_OP_URN)) declared = true;
            }
        }
        if (!declared) throw ScimException.invalidSyntax("body is not a PatchOp message");
        JsonValue opsVal = body.getIgnoreCase("Operations");
        if (!(opsVal instanceof JsonArray ops) || ops.isEmpty()) {
            throw ScimException.invalidSyntax("PatchOp requires a non-empty Operations array");
        }
        List<PatchOp> result = new ArrayList<>();
        for (JsonValue v : ops) {
            if (!(v instanceof JsonObject op)) throw ScimException.invalidSyntax("operation must be an object");
            String opName = op.optString("op", null);
            if (opName == null) throw ScimException.invalidSyntax("operation missing 'op'");
            Type type = switch (opName.toLowerCase()) {
                case "add" -> Type.ADD;
                case "remove" -> Type.REMOVE;
                case "replace" -> Type.REPLACE;
                default -> throw ScimException.invalidSyntax("unknown op '" + opName + "'");
            };
            String pathStr = op.optString("path", null);
            AttributePath path = pathStr == null ? null : FilterParser.parsePath(pathStr);
            JsonValue value = op.getIgnoreCase("value");
            if (type != Type.REMOVE && (value == null || value.isNull())) {
                throw ScimException.invalidValue("'" + opName + "' operation requires a value");
            }
            result.add(new PatchOp(type, path, value));
        }
        return result;
    }
}
