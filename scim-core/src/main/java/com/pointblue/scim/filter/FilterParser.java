// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.filter;

import com.pointblue.scim.ScimException;
import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;

/**
 * Hand-written recursive-descent parser for the RFC 7644 filter grammar and
 * for PATCH attribute paths. Precedence: not > and > or.
 */
public final class FilterParser {

    private final String src;
    private int pos;

    private FilterParser(String src) { this.src = src; }

    public static Filter parse(String filter) {
        if (filter == null || filter.isBlank()) throw ScimException.invalidFilter("empty filter");
        FilterParser p = new FilterParser(filter);
        Filter f = p.parseOr();
        p.skipWs();
        if (p.pos != filter.length()) throw p.err("trailing content in filter");
        return f;
    }

    /**
     * Parses a PATCH "path" value: attrPath [valFilter] [.subAttr].
     */
    public static AttributePath parsePath(String path) {
        if (path == null || path.isBlank()) throw ScimException.invalidPath("empty path");
        FilterParser p = new FilterParser(path);
        AttributePath ap;
        try {
            ap = p.parseAttrPath();
        } catch (ScimException e) {
            throw ScimException.invalidPath(e.getMessage());
        }
        p.skipWs();
        if (p.pos != path.length()) throw ScimException.invalidPath("trailing content in path: " + path);
        return ap;
    }

    // ---- filter grammar ----

    private Filter parseOr() {
        Filter left = parseAnd();
        while (true) {
            int mark = pos;
            skipWs();
            String word = peekWord();
            if (word != null && word.equalsIgnoreCase("or")) {
                consumeWord();
                left = new Filter.Or(left, parseAnd());
            } else {
                pos = mark;
                return left;
            }
        }
    }

    private Filter parseAnd() {
        Filter left = parseUnary();
        while (true) {
            int mark = pos;
            skipWs();
            String word = peekWord();
            if (word != null && word.equalsIgnoreCase("and")) {
                consumeWord();
                left = new Filter.And(left, parseUnary());
            } else {
                pos = mark;
                return left;
            }
        }
    }

    private Filter parseUnary() {
        skipWs();
        if (pos >= src.length()) throw err("unexpected end of filter");
        if (src.charAt(pos) == '(') {
            pos++;
            Filter f = parseOr();
            skipWs();
            expect(')');
            return f;
        }
        int mark = pos;
        String word = peekWord();
        if (word != null && word.equalsIgnoreCase("not")) {
            consumeWord();
            skipWs();
            expect('(');
            Filter f = parseOr();
            skipWs();
            expect(')');
            return new Filter.Not(f);
        }
        pos = mark;
        return parseAttrExp();
    }

    private Filter parseAttrExp() {
        AttributePath path = parseAttrPath();
        // valuePath with no operator after it: emails[type eq "work"]
        if (path.hasValueFilter() && !path.hasSubAttr()) {
            int mark = pos;
            skipWs();
            String next = peekWord();
            if (next == null || next.equalsIgnoreCase("and") || next.equalsIgnoreCase("or")) {
                pos = mark;
                AttributePath bare = new AttributePath(path.schemaUrn(), path.attr(), null, null);
                return new Filter.ValuePath(bare, path.valueFilter());
            }
            pos = mark;
        }
        skipWs();
        String opWord = consumeWord();
        if (opWord == null) throw err("expected operator after attribute path");
        if (opWord.equalsIgnoreCase("pr")) return new Filter.Present(path);
        Op op = Op.fromWord(opWord);
        if (op == null) throw err("unknown operator '" + opWord + "'");
        skipWs();
        JsonValue literal = parseLiteral();
        return new Filter.Compare(path, op, literal);
    }

    // attrPath = [URI ":"] ATTRNAME ["[" valFilter "]"] ["." subAttr]
    private AttributePath parseAttrPath() {
        skipWs();
        String word = consumeWord();
        if (word == null) throw err("expected attribute path");
        String urn = null;
        String attrPart = word;
        int lastColon = word.lastIndexOf(':');
        if (lastColon >= 0) {
            urn = word.substring(0, lastColon);
            attrPart = word.substring(lastColon + 1);
            if (urn.isEmpty() || attrPart.isEmpty()) throw err("malformed attribute path '" + word + "'");
        }
        String attr = attrPart;
        String subAttr = null;
        int dot = attrPart.indexOf('.');
        if (dot >= 0) {
            attr = attrPart.substring(0, dot);
            subAttr = attrPart.substring(dot + 1);
            if (attr.isEmpty() || subAttr.isEmpty() || subAttr.indexOf('.') >= 0) {
                throw err("malformed attribute path '" + word + "'");
            }
        }
        Filter valueFilter = null;
        if (pos < src.length() && src.charAt(pos) == '[') {
            if (subAttr != null) throw err("value filter must precede sub-attribute");
            pos++;
            valueFilter = parseOr();
            skipWs();
            expect(']');
            // optional .subAttr after the bracket
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                subAttr = consumeWord();
                if (subAttr == null || subAttr.indexOf('.') >= 0 || subAttr.indexOf(':') >= 0) {
                    throw err("malformed sub-attribute after value filter");
                }
            }
        }
        return new AttributePath(urn, attr, valueFilter, subAttr);
    }

    /** Comparison literal: JSON string, number, true, false, or null. */
    private JsonValue parseLiteral() {
        if (pos >= src.length()) throw err("expected comparison value");
        char c = src.charAt(pos);
        int start = pos;
        if (c == '"') {
            boolean escaped = false;
            pos++;
            while (pos < src.length()) {
                char d = src.charAt(pos);
                if (escaped) escaped = false;
                else if (d == '\\') escaped = true;
                else if (d == '"') { pos++; return jsonLiteral(src.substring(start, pos)); }
                pos++;
            }
            throw err("unterminated string literal");
        }
        String word = consumeWord();
        if (word == null) throw err("expected comparison value");
        return jsonLiteral(word);
    }

    private JsonValue jsonLiteral(String text) {
        try {
            return Json.parse(text);
        } catch (RuntimeException e) {
            throw err("invalid comparison value " + text);
        }
    }

    // ---- lexing helpers ----

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':' || c == '$' || c == '+';
    }

    private String peekWord() {
        int mark = pos;
        String w = consumeWord();
        pos = mark;
        return w;
    }

    private String consumeWord() {
        skipWs();
        int start = pos;
        while (pos < src.length() && wordChar(src.charAt(pos))) pos++;
        return pos > start ? src.substring(start, pos) : null;
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) throw err("expected '" + c + "'");
        pos++;
    }

    private void skipWs() {
        while (pos < src.length() && src.charAt(pos) == ' ') pos++;
    }

    private ScimException err(String message) {
        return ScimException.invalidFilter(message + " (offset " + pos + " in \"" + src + "\")");
    }
}
