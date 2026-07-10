package com.pointblue.scim.filter;

/** RFC 7644 comparison operators. */
public enum Op {
    EQ, NE, CO, SW, EW, GT, GE, LT, LE;

    public static Op fromWord(String word) {
        for (Op op : values()) {
            if (op.name().equalsIgnoreCase(word)) return op;
        }
        return null;
    }
}
