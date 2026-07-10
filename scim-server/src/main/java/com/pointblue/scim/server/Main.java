// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server;

import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.server.config.ServerConfig;
import com.pointblue.scim.server.http.ScimHttpServer;
import com.pointblue.scim.spi.ScimBackend;
import com.pointblue.scim.spi.ScimBackendProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.logging.Logger;

public final class Main {

    private static final Logger log = Logger.getLogger("scim.server");

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(args.length > 0 ? args[0] : "config/scim-proxy.json");
        ServerConfig config;
        if (Files.exists(configFile)) {
            config = ServerConfig.load(configFile);
        } else {
            config = ServerConfig.devDefaults();
            log.warning("no config file at " + configFile + " — starting in DEV mode "
                    + "(in-memory backend, HTTP). Bearer token: "
                    + config.bearerTokens().keySet().iterator().next());
        }

        SchemaRegistry registry = SchemaRegistry.withDefaults();
        config.applySchemas(registry);

        String backendType = config.backend().optString("type", null);
        ScimBackendProvider provider = null;
        for (ScimBackendProvider p : ServiceLoader.load(ScimBackendProvider.class)) {
            if (p.name().equalsIgnoreCase(backendType)) provider = p;
        }
        if (provider == null) {
            throw new IllegalArgumentException("no backend provider named '" + backendType
                    + "' on the classpath");
        }

        ScimBackend backend = provider.create(config.backend(), registry);
        backend.healthCheck();

        ScimHttpServer server = new ScimHttpServer(config, registry, backend);
        int port = server.start();
        log.info("SCIM server listening on port " + port + " at " + server.baseUrl()
                + " (backend: " + backendType + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
