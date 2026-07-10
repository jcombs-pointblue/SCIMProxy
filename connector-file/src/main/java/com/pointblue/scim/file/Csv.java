package com.pointblue.scim.file;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 CSV reader/writer (comma-separated, double-quote quoting). */
final class Csv {

    private Csv() {}

    /** Parses CSV text into rows of fields. Handles quoted fields, embedded commas/newlines, "" escapes. */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = text.length();
        boolean sawAny = false;
        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { field.append('"'); i += 2; }
                    else { inQuotes = false; i++; }
                } else { field.append(c); i++; }
            } else {
                switch (c) {
                    case '"' -> { inQuotes = true; sawAny = true; i++; }
                    case ',' -> { row.add(field.toString()); field.setLength(0); sawAny = true; i++; }
                    case '\r' -> i++;
                    case '\n' -> {
                        row.add(field.toString()); field.setLength(0);
                        rows.add(row); row = new ArrayList<>();
                        sawAny = false; i++;
                    }
                    default -> { field.append(c); sawAny = true; i++; }
                }
            }
        }
        if (sawAny || !field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    static String write(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(quote(row.get(j)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String quote(String value) {
        String v = value == null ? "" : value;
        boolean needs = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        if (!needs) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
