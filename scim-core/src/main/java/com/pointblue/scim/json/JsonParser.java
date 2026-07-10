// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.json;

import java.math.BigDecimal;

/** Strict RFC 8259 recursive-descent parser with a depth limit. */
final class JsonParser {

    private final String src;
    private int pos;

    JsonParser(String src) {
        if (src == null) throw new JsonException("input is null");
        this.src = src;
    }

    JsonValue parseDocument() {
        JsonValue v = parseValue(0);
        skipWs();
        if (pos != src.length()) throw err("trailing content");
        return v;
    }

    private JsonValue parseValue(int depth) {
        if (depth > Json.MAX_DEPTH) throw err("nesting too deep");
        skipWs();
        if (pos >= src.length()) throw err("unexpected end of input");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObject(depth);
            case '[' -> parseArray(depth);
            case '"' -> new JsonString(parseString());
            case 't' -> { expect("true");  yield JsonBoolean.TRUE; }
            case 'f' -> { expect("false"); yield JsonBoolean.FALSE; }
            case 'n' -> { expect("null");  yield JsonNull.NULL; }
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                throw err("unexpected character '" + c + "'");
            }
        };
    }

    private JsonObject parseObject(int depth) {
        pos++; // '{'
        JsonObject obj = new JsonObject();
        skipWs();
        if (peek() == '}') { pos++; return obj; }
        while (true) {
            skipWs();
            if (peek() != '"') throw err("expected member name");
            String name = parseString();
            skipWs();
            if (peek() != ':') throw err("expected ':'");
            pos++;
            obj.put(name, parseValue(depth + 1));
            skipWs();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return obj; }
            throw err("expected ',' or '}'");
        }
    }

    private JsonArray parseArray(int depth) {
        pos++; // '['
        JsonArray arr = new JsonArray();
        skipWs();
        if (peek() == ']') { pos++; return arr; }
        while (true) {
            arr.add(parseValue(depth + 1));
            skipWs();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return arr; }
            throw err("expected ',' or ']'");
        }
    }

    private String parseString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw err("unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw err("unterminated escape");
                char e = src.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) throw err("bad unicode escape");
                        sb.append((char) Integer.parseInt(src, pos, pos + 4, 16));
                        pos += 4;
                    }
                    default -> throw err("bad escape '\\" + e + "'");
                }
            } else if (c < 0x20) {
                throw err("unescaped control character in string");
            } else {
                sb.append(c);
            }
        }
    }

    private JsonNumber parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        if (pos >= src.length() || !digit(src.charAt(pos))) throw err("malformed number");
        if (src.charAt(pos) == '0') pos++;
        else while (pos < src.length() && digit(src.charAt(pos))) pos++;
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            if (pos >= src.length() || !digit(src.charAt(pos))) throw err("malformed number");
            while (pos < src.length() && digit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            if (pos >= src.length() || !digit(src.charAt(pos))) throw err("malformed number");
            while (pos < src.length() && digit(src.charAt(pos))) pos++;
        }
        return new JsonNumber(new BigDecimal(src.substring(start, pos)));
    }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) throw err("invalid literal");
        pos += literal.length();
    }

    private char peek() {
        if (pos >= src.length()) throw err("unexpected end of input");
        return src.charAt(pos);
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private JsonException err(String message) {
        return new JsonException(message + " at offset " + pos);
    }
}
