package com.pointblue.scim.schema;

import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;

import java.util.ArrayList;
import java.util.List;

/** RFC 7643 §7 attribute definition. */
public record Attribute(
        String name,
        AttributeType type,
        boolean multiValued,
        String description,
        boolean required,
        boolean caseExact,
        Mutability mutability,
        Returned returned,
        Uniqueness uniqueness,
        List<String> canonicalValues,
        List<String> referenceTypes,
        List<Attribute> subAttributes) {

    public enum AttributeType { STRING, BOOLEAN, DECIMAL, INTEGER, DATETIME, BINARY, REFERENCE, COMPLEX }
    public enum Mutability { READ_ONLY, READ_WRITE, IMMUTABLE, WRITE_ONLY }
    public enum Returned { ALWAYS, NEVER, DEFAULT, REQUEST }
    public enum Uniqueness { NONE, SERVER, GLOBAL }

    public Attribute subAttribute(String subName) {
        if (subAttributes == null) return null;
        for (Attribute a : subAttributes) {
            if (a.name().equalsIgnoreCase(subName)) return a;
        }
        return null;
    }

    public static Builder builder(String name, AttributeType type) { return new Builder(name, type); }
    public static Builder string(String name)   { return new Builder(name, AttributeType.STRING); }
    public static Builder bool(String name)     { return new Builder(name, AttributeType.BOOLEAN); }
    public static Builder complex(String name)  { return new Builder(name, AttributeType.COMPLEX); }
    public static Builder reference(String name, String... refTypes) {
        Builder b = new Builder(name, AttributeType.REFERENCE);
        b.referenceTypes = List.of(refTypes);
        return b;
    }

    public static final class Builder {
        private final String name;
        private final AttributeType type;
        private boolean multiValued;
        private String description = "";
        private boolean required;
        private boolean caseExact;
        private Mutability mutability = Mutability.READ_WRITE;
        private Returned returned = Returned.DEFAULT;
        private Uniqueness uniqueness = Uniqueness.NONE;
        private List<String> canonicalValues = List.of();
        private List<String> referenceTypes = List.of();
        private List<Attribute> subAttributes = List.of();

        private Builder(String name, AttributeType type) { this.name = name; this.type = type; }

        public Builder multiValued()                 { this.multiValued = true; return this; }
        public Builder description(String d)         { this.description = d; return this; }
        public Builder required()                    { this.required = true; return this; }
        public Builder caseExact()                   { this.caseExact = true; return this; }
        public Builder mutability(Mutability m)      { this.mutability = m; return this; }
        public Builder readOnly()                    { return mutability(Mutability.READ_ONLY); }
        public Builder immutable()                   { return mutability(Mutability.IMMUTABLE); }
        public Builder writeOnly()                   { this.returned = Returned.NEVER; return mutability(Mutability.WRITE_ONLY); }
        public Builder returned(Returned r)          { this.returned = r; return this; }
        public Builder uniqueness(Uniqueness u)      { this.uniqueness = u; return this; }
        public Builder canonical(String... values)   { this.canonicalValues = List.of(values); return this; }
        public Builder sub(Attribute... subs)        { this.subAttributes = List.of(subs); return this; }
        public Builder sub(Builder... subs) {
            List<Attribute> list = new ArrayList<>();
            for (Builder b : subs) list.add(b.build());
            this.subAttributes = List.copyOf(list);
            return this;
        }

        public Attribute build() {
            return new Attribute(name, type, multiValued, description, required, caseExact,
                    mutability, returned, uniqueness, canonicalValues, referenceTypes, subAttributes);
        }
    }

    // ---- RFC 7643 JSON representation (for /Schemas and extension files) ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.put("name", name);
        o.put("type", type.name().toLowerCase());
        if (type == AttributeType.COMPLEX && !subAttributes.isEmpty()) {
            JsonArray subs = new JsonArray();
            for (Attribute a : subAttributes) subs.add(a.toJson());
            o.put("subAttributes", subs);
        }
        o.put("multiValued", multiValued);
        o.put("description", description);
        o.put("required", required);
        if (!canonicalValues.isEmpty()) {
            JsonArray cv = new JsonArray();
            for (String v : canonicalValues) cv.add(v);
            o.put("canonicalValues", cv);
        }
        if (type == AttributeType.STRING || type == AttributeType.REFERENCE || type == AttributeType.BINARY) {
            o.put("caseExact", caseExact);
        }
        o.put("mutability", enumWord(mutability.name()));
        o.put("returned", returned.name().toLowerCase());
        o.put("uniqueness", uniqueness.name().toLowerCase());
        if (!referenceTypes.isEmpty()) {
            JsonArray rt = new JsonArray();
            for (String v : referenceTypes) rt.add(v);
            o.put("referenceTypes", rt);
        }
        return o;
    }

    public static Attribute fromJson(JsonObject o) {
        String name = o.optString("name", null);
        if (name == null) throw new IllegalArgumentException("attribute definition missing 'name'");
        AttributeType type = AttributeType.valueOf(o.optString("type", "string").toUpperCase());
        Builder b = new Builder(name, type);
        if (o.optBoolean("multiValued", false)) b.multiValued();
        b.description(o.optString("description", ""));
        if (o.optBoolean("required", false)) b.required();
        if (o.optBoolean("caseExact", false)) b.caseExact();
        b.mutability(Mutability.valueOf(camelToEnum(o.optString("mutability", "readWrite"))));
        b.returned(Returned.valueOf(o.optString("returned", "default").toUpperCase()));
        b.uniqueness(Uniqueness.valueOf(o.optString("uniqueness", "none").toUpperCase()));
        b.canonicalValues = stringList(o.get("canonicalValues"));
        b.referenceTypes = stringList(o.get("referenceTypes"));
        if (o.get("subAttributes") instanceof JsonArray subs) {
            List<Attribute> list = new ArrayList<>();
            for (JsonValue v : subs) list.add(fromJson(v.asObject()));
            b.subAttributes = List.copyOf(list);
        }
        return b.build();
    }

    private static List<String> stringList(JsonValue v) {
        if (!(v instanceof JsonArray a)) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonValue e : a) list.add(e.asString());
        return List.copyOf(list);
    }

    /** READ_ONLY -> readOnly */
    private static String enumWord(String enumName) {
        String[] parts = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    /** readOnly -> READ_ONLY */
    private static String camelToEnum(String word) {
        StringBuilder sb = new StringBuilder();
        for (char c : word.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('_');
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }
}
