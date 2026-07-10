package com.pointblue.scim.file;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonNull;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.spi.BackendException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One backing file of flat records. Loaded into memory; mutations are persisted
 * atomically (temp file + rename). All access is serialized on this instance.
 * A record is a flat {@link JsonObject} (field -> scalar or, for JSON, an array
 * for group members).
 */
final class RecordFile {

    private final Path path;
    private final FileConfig.Format format;
    private final List<String> columnOrder = new ArrayList<>(); // CSV header order
    private final List<JsonObject> records = new ArrayList<>();

    RecordFile(Path path, FileConfig.Format format) {
        this.path = path;
        this.format = format;
        load();
    }

    synchronized List<JsonObject> all() {
        List<JsonObject> copy = new ArrayList<>(records.size());
        for (JsonObject r : records) copy.add((JsonObject) r.copy());
        return copy;
    }

    synchronized JsonObject find(String idColumn, String id) {
        for (JsonObject r : records) {
            JsonValue v = r.getIgnoreCase(idColumn);
            if (v != null && !v.isNull() && v.asString().equals(id)) return (JsonObject) r.copy();
        }
        return null;
    }

    synchronized void add(JsonObject record) {
        records.add((JsonObject) record.copy());
        rememberColumns(record);
        persist();
    }

    synchronized boolean replace(String idColumn, String id, JsonObject record) {
        for (int i = 0; i < records.size(); i++) {
            JsonValue v = records.get(i).getIgnoreCase(idColumn);
            if (v != null && !v.isNull() && v.asString().equals(id)) {
                records.set(i, (JsonObject) record.copy());
                rememberColumns(record);
                persist();
                return true;
            }
        }
        return false;
    }

    synchronized boolean remove(String idColumn, String id) {
        for (int i = 0; i < records.size(); i++) {
            JsonValue v = records.get(i).getIgnoreCase(idColumn);
            if (v != null && !v.isNull() && v.asString().equals(id)) {
                records.remove(i);
                persist();
                return true;
            }
        }
        return false;
    }

    // ---- load / persist ----

    private void load() {
        if (!Files.exists(path)) return; // an empty store until first write
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (format == FileConfig.Format.JSON) loadJson(text);
            else loadCsv(text);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private void loadJson(String text) {
        if (text.isBlank()) return;
        JsonValue root = Json.parse(text);
        JsonArray arr = root instanceof JsonArray a ? a
                : root instanceof JsonObject o && o.getIgnoreCase("Resources") instanceof JsonArray ra ? ra
                : null;
        if (arr == null) throw new BackendException(500, null, path + " must contain a JSON array of records");
        for (JsonValue v : arr) {
            if (v instanceof JsonObject o) { records.add(o); rememberColumns(o); }
        }
    }

    private void loadCsv(String text) {
        List<List<String>> rows = Csv.parse(text);
        if (rows.isEmpty()) return;
        List<String> header = rows.get(0);
        columnOrder.addAll(header);
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() == 1 && row.get(0).isEmpty()) continue; // trailing blank line
            JsonObject rec = new JsonObject();
            for (int c = 0; c < header.size(); c++) {
                String val = c < row.size() ? row.get(c) : "";
                if (!val.isEmpty()) rec.put(header.get(c), val);
            }
            records.add(rec);
        }
    }

    private void rememberColumns(JsonObject record) {
        for (String k : record.keys()) {
            if (!columnOrder.contains(k)) columnOrder.add(k);
        }
    }

    private void persist() {
        String text = format == FileConfig.Format.JSON ? writeJson() : writeCsv();
        try {
            Path dir = path.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".scim-", ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BackendException(500, null, "cannot write " + path + ": " + e.getMessage());
        }
    }

    private String writeJson() {
        JsonArray arr = new JsonArray();
        for (JsonObject r : records) arr.add(r);
        return Json.writePretty(arr) + "\n";
    }

    private String writeCsv() {
        Set<String> cols = new LinkedHashSet<>(columnOrder);
        for (JsonObject r : records) cols.addAll(r.keys());
        List<String> header = new ArrayList<>(cols);
        List<List<String>> rows = new ArrayList<>();
        rows.add(header);
        for (JsonObject r : records) {
            List<String> row = new ArrayList<>();
            for (String col : header) {
                JsonValue v = r.getIgnoreCase(col);
                row.add(v == null || v.isNull() ? "" : scalar(v));
            }
            rows.add(row);
        }
        return Csv.write(rows);
    }

    private static String scalar(JsonValue v) {
        return v instanceof com.pointblue.scim.json.JsonString s ? s.value() : v.toString();
    }
}
