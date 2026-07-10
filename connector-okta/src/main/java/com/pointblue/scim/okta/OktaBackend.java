// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.okta;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.okta.auth.PrivateKeyJwt;
import com.pointblue.scim.okta.auth.TokenSource;
import com.pointblue.scim.okta.http.OktaClient;
import com.pointblue.scim.okta.map.UserMapper;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.Capabilities;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.ScimBackend;
import com.pointblue.scim.spi.ScimBackendProvider;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

/** Okta Management API backend. */
public final class OktaBackend implements ScimBackend {

    private final OktaClient client;
    private final OktaUsers users;
    private final OktaGroups groups;

    public OktaBackend(JsonObject backendConfig, SchemaRegistry registry) {
        OktaConfig config = new OktaConfig(backendConfig);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        PrivateKeyJwt jwt = config.privateKeyPem == null
                ? null : new PrivateKeyJwt(Path.of(config.privateKeyPem), config.kid);
        TokenSource tokens = new TokenSource(http, jwt, config.clientSecret,
                config.clientId, config.tokenUrl(), config.scopes);
        this.client = new OktaClient(http, tokens, config.orgUrl);
        UserMapper mapper = new UserMapper(config.userMappings);
        this.users = new OktaUsers(client, mapper, config, registry);
        this.groups = new OktaGroups(client, config, registry);
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, false, 200, 10_000);
    }

    @Override
    public ResourceBackend resource(String resourceTypeName) {
        return switch (resourceTypeName) {
            case "User" -> users;
            case "Group" -> groups;
            default -> null;
        };
    }

    @Override
    public void healthCheck() {
        client.get("/api/v1/users?limit=1");
    }

    public static final class Provider implements ScimBackendProvider {
        @Override public String name() { return "okta"; }
        @Override public ScimBackend create(JsonObject backendConfig, SchemaRegistry registry) {
            return new OktaBackend(backendConfig, registry);
        }
    }
}
