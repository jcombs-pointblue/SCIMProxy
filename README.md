# SCIM 2.0 Proxy Server

A standalone SCIM 2.0 server (RFC 7643/7644) that fronts platforms without their own
SCIM interface, via pluggable backend connectors. Zero runtime dependencies — JDK 21+
only. See [DESIGN.md](DESIGN.md) for the full design.

## Status

- **Done (M1/M2):** JSON module, schema registry with config-driven extensions, full
  RFC 7644 filter grammar, full PATCH engine, Users/Groups CRUD + `.search`,
  discovery endpoints, static bearer auth, in-memory reference backend.
- **Done (M3):** Okta connector — OAuth2 private-key JWT auth, config-driven
  attribute mapping (incl. externalId and schema extensions to Okta custom profile
  attributes), filter pushdown to Okta `search=` with in-memory fallback, native
  PATCH (partial profile updates, lifecycle for `active`, group membership calls),
  cursor pagination, rate-limit backoff. DELETE deactivates by default
  (`deleteBehavior: "destroy"` opts into real deletion). 66 tests total.
- **Next (M4+):** `/Bulk`, ETags, TLS listener, live Okta smoke test.

## Build and run

```sh
mvn package
java -jar scim-server/target/scim-server-0.1.0-SNAPSHOT.jar [config-file]
```

With no config file the server starts in **dev mode**: in-memory backend, HTTP on
port 8080, and a generated bearer token printed to the log.

```sh
TOKEN=...   # from the startup log
curl -s -X POST http://localhost:8080/scim/v2/Users \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/scim+json' \
  -d '{"userName":"jdoe","name":{"givenName":"Jane"}}'

curl -s "http://localhost:8080/scim/v2/Users?filter=userName%20eq%20%22jdoe%22" \
  -H "Authorization: Bearer $TOKEN"
```

## Configuration

Copy `config/scim-proxy.sample.json` to `config/scim-proxy.json`. Secrets are
referenced as `${ENV_VAR}` and resolved from the environment at startup.

### Schema extensions

Custom attributes are pure configuration — no code changes:

1. Put an RFC 7643 schema definition in `config/schemas/*.json`
   (see `config/schemas/custom-user.sample.json`).
2. Bind it to a resource type under `resourceTypes` in the main config.

The extension is then published at `/Schemas/{urn}`, listed in `/ResourceTypes`,
validated on writes, and addressable in filters and PATCH paths
(`urn:...:User:costCenter eq "CC-1"`).

### Okta backend

Set `backend.type` to `"okta"` (see the `backendOktaExample` block in the sample
config). Setup on the Okta side:

1. Create an **API Services** app (client credentials), grant it the scopes
   `okta.users.manage`, `okta.users.read`, `okta.groups.manage`, `okta.groups.read`.
2. Register a public key on the app and keep the private key as a **PKCS#8 PEM**
   (`openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt -out okta-key.pem`);
   point `privateKeyPem` at it.
3. To round-trip `externalId` or custom extension attributes, add the matching
   **custom profile attributes** in Okta and map them under `userMappings`.

Semantics: SCIM `id` is the Okta user id; `active` maps to Okta status and drives
activate/deactivate/unsuspend lifecycle calls; `DELETE /Users/{id}` deactivates
only unless `deleteBehavior` is `"destroy"`; only `OKTA_GROUP` groups are managed.
Filters push down to Okta `search=` when expressible (`eq`, `sw`, ranges, `and`/`or`,
`pr`); anything else (`co`, `ne`, `not`, value-path filters) is evaluated in the
proxy over a bounded scan.

## Modules

| Module | Contents |
|---|---|
| `scim-core` | JSON, resource model, schema registry/validator, filter parser+evaluator, PATCH engine, backend SPI |
| `scim-server` | JDK HttpServer front end, bearer auth, endpoints, in-memory backend, runnable shaded jar |

Backends implement `com.pointblue.scim.spi.ScimBackendProvider` and are discovered
via `ServiceLoader`; select one with `backend.type` in the config.
