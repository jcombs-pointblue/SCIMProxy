package com.pointblue.scim.server.config;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.schema.Schema;
import com.pointblue.scim.schema.SchemaRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Loaded server configuration with ${ENV_VAR} substitution. */
public final class ServerConfig {

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final JsonObject root;
    private final Map<String, String> tokensByValue = new LinkedHashMap<>(); // token -> client name

    private ServerConfig(JsonObject root) {
        this.root = root;
        if (root.getIgnoreCase("auth") instanceof JsonObject auth
                && auth.getIgnoreCase("bearerTokens") instanceof JsonArray tokens) {
            for (JsonValue v : tokens) {
                JsonObject t = v.asObject();
                String token = t.optString("token", null);
                String name = t.optString("name", "client");
                if (token == null || token.isBlank()) {
                    throw new IllegalArgumentException("auth.bearerTokens entry missing 'token'");
                }
                tokensByValue.put(token, name);
            }
        }
    }

    public static ServerConfig load(Path file) {
        try {
            String text = Files.readString(file);
            return new ServerConfig(Json.parseObject(substituteEnv(text)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read config " + file, e);
        }
    }

    /** Dev-mode config: in-memory backend, HTTP, a generated (printed) bearer token. */
    public static ServerConfig devDefaults() {
        byte[] rnd = new byte[24];
        new SecureRandom().nextBytes(rnd);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        JsonObject root = new JsonObject();
        JsonObject auth = new JsonObject();
        JsonArray tokens = new JsonArray();
        tokens.add(new JsonObject().put("name", "dev").put("token", token));
        auth.put("bearerTokens", tokens);
        root.put("auth", auth);
        root.put("backend", new JsonObject().put("type", "memory"));
        return new ServerConfig(root);
    }

    static String substituteEnv(String text) {
        Matcher m = ENV_REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String val = System.getenv(name);
            if (val == null) throw new IllegalArgumentException("config references undefined env var " + name);
            // the substitution happens inside a JSON document: escape accordingly
            m.appendReplacement(sb, Matcher.quoteReplacement(jsonEscape(val)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private JsonObject section(String name) {
        return root.getIgnoreCase(name) instanceof JsonObject o ? o : new JsonObject();
    }

    public int port() {
        JsonValue v = section("server").getIgnoreCase("port");
        return v == null ? 8080 : v.asNumber().intValue();
    }

    public String contextPath() {
        String p = section("server").optString("contextPath", "/scim/v2");
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /** External base URL including context path; actualPort is the bound port. */
    public String baseUrl(int actualPort) {
        String b = section("server").optString("baseUrl", null);
        if (b == null) b = "http://localhost:" + actualPort;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + contextPath();
    }

    public Map<String, String> bearerTokens() { return tokensByValue; }

    public JsonObject backend() {
        JsonValue v = root.getIgnoreCase("backend");
        if (!(v instanceof JsonObject o) || o.optString("type", null) == null) {
            throw new IllegalArgumentException("config requires backend.type");
        }
        return (JsonObject) v;
    }

    public int maxPayloadBytes() {
        JsonValue v = section("limits").getIgnoreCase("maxPayloadBytes");
        return v == null ? 1_048_576 : v.asNumber().intValue();
    }

    public int defaultPageSize() {
        JsonValue v = section("limits").getIgnoreCase("defaultPageSize");
        return v == null ? 100 : v.asNumber().intValue();
    }

    /** Loads extension schema files and resource-type bindings into the registry. Fail-fast. */
    public void applySchemas(SchemaRegistry registry) {
        String dir = section("schemas").optString("dir", null);
        if (dir != null) {
            Path schemaDir = Path.of(dir);
            if (!Files.isDirectory(schemaDir)) {
                throw new IllegalArgumentException("schemas.dir is not a directory: " + dir);
            }
            try (Stream<Path> files = Files.list(schemaDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json"))
                     .sorted()
                     .forEach(p -> {
                         try {
                             registry.register(Schema.fromJson(Json.parseObject(Files.readString(p))));
                         } catch (IOException e) {
                             throw new UncheckedIOException("cannot read schema file " + p, e);
                         }
                     });
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list schema dir " + dir, e);
            }
        }
        if (root.getIgnoreCase("resourceTypes") instanceof JsonObject rts) {
            rts.forEach((typeName, val) -> {
                if (!(val instanceof JsonObject rtCfg)) return;
                if (rtCfg.getIgnoreCase("extensions") instanceof JsonArray exts) {
                    for (JsonValue e : exts) {
                        JsonObject ext = e.asObject();
                        String urn = ext.optString("schema", null);
                        if (urn == null) throw new IllegalArgumentException(
                                "resourceTypes." + typeName + ".extensions entry missing 'schema'");
                        registry.addExtension(typeName, urn, ext.optBoolean("required", false));
                    }
                }
            });
        }
    }
}
