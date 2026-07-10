package com.pointblue.scim.json;

final class JsonWriter {

    private JsonWriter() {}

    /** indent &lt; 0 means compact output. */
    static void write(JsonValue value, StringBuilder sb, int indent, int level) {
        switch (value) {
            case JsonNull n -> sb.append("null");
            case JsonBoolean b -> sb.append(b.value());
            case JsonNumber n -> sb.append(n.value().toPlainString());
            case JsonString s -> writeString(s.value(), sb);
            case JsonArray a -> {
                if (a.isEmpty()) { sb.append("[]"); return; }
                sb.append('[');
                boolean first = true;
                for (JsonValue v : a) {
                    if (!first) sb.append(',');
                    first = false;
                    newline(sb, indent, level + 1);
                    write(v, sb, indent, level + 1);
                }
                newline(sb, indent, level);
                sb.append(']');
            }
            case JsonObject o -> {
                if (o.isEmpty()) { sb.append("{}"); return; }
                sb.append('{');
                boolean[] first = {true};
                o.forEach((k, v) -> {
                    if (!first[0]) sb.append(',');
                    first[0] = false;
                    newline(sb, indent, level + 1);
                    writeString(k, sb);
                    sb.append(':');
                    if (indent >= 0) sb.append(' ');
                    write(v, sb, indent, level + 1);
                });
                newline(sb, indent, level);
                sb.append('}');
            }
        }
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) return;
        sb.append('\n');
        sb.append(" ".repeat(indent * level));
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
