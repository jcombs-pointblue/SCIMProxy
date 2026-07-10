# SCIM 2.0 Proxy Server

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Java 21+](https://img.shields.io/badge/Java-21%2B-blue.svg)

A standalone SCIM 2.0 server (RFC 7643/7644) that fronts platforms without their own
SCIM interface, via pluggable backend connectors. Zero runtime dependencies — JDK 21+
only. See [DESIGN.md](DESIGN.md) for the full design.

## Status

- **Core + server:** JSON module, schema registry with config-driven extensions, full
  RFC 7644 filter grammar, full PATCH engine, Users/Groups CRUD + `.search`,
  discovery endpoints, static bearer auth, in-memory reference backend.
- **Backends (5):** `memory`, `okta` (private-key JWT or client secret), `ldap`
  (eDirectory / Active Directory / OpenLDAP flavors, LDAPS), `jdbc` (config-driven
  table mapping, PostgreSQL bundled), `file` (CSV / JSON). Each rides the same SPI
  and inherits the full protocol layer.
- **Validated live:** Okta (Preview org), eDirectory 9.3, Active Directory, 389
  Directory Server, PostgreSQL 17 — see [TESTING.md](TESTING.md). 113 tests total.
- **Next:** `/Bulk`, ETags, native TLS listener; generic REST connector and more LDAP
  flavor presets as targets arise.

## Build and run

```sh
mvn package
java -jar scim-server/target/scim-server-1.0.0.jar [config-file]
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

### Inbound authentication

How SCIM clients authenticate **to** the proxy. Configure any combination under
`auth`; each request is tried against every enabled method and the first match wins,
so different clients can use different schemes. At least one method must be configured
— the server refuses to start unauthenticated. Enabled methods are advertised in
`/ServiceProviderConfig`.

- **`bearerTokens`** — long-lived static tokens, one per client. The default; works
  with every SCIM client. Compared constant-time; values are never logged.
- **`oauth`** — OAuth 2.0 bearer-JWT validation (resource-server mode). Validates a
  JWT issued by any standards-compliant IdP: `jwksUrl` (required), plus optional
  `issuer`, `audience`, and `requiredScopes`. Checks the RS256/384/512 signature
  against the IdP's JWKS, then expiry/not-before, issuer, audience, and scopes. Keys
  are cached and refreshed on rotation. This is the production-grade option — the same
  Okta org used as a backend can also issue inbound tokens.
- **`basic`** — HTTP Basic auth for legacy clients that only support username/password.
  Passwords matched constant-time.

```jsonc
"auth": {
  "bearerTokens": [ { "name": "idm-driver", "token": "${SCIM_TOKEN_IDM}" } ],
  "oauth": {
    "jwksUrl": "https://idp.example.com/oauth2/v1/keys",
    "issuer": "https://idp.example.com",
    "audience": "scim-proxy",
    "requiredScopes": ["scim:write"]
  },
  "basic": [ { "name": "legacy", "username": "svc-scim", "password": "${SCIM_BASIC_PW}" } ]
}
```

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

1. Create an **API Services** app. Grant it the **Okta API Scopes**
   `okta.users.manage`, `okta.users.read`, `okta.groups.manage`, `okta.groups.read`.
2. On the app's **Admin roles** tab, assign an admin role (Super Administrator for
   a test org). Without it, tokens issue fine but every API call returns 403.
3. Set **Client authentication** to **Public key / Private key** and register a
   public key. Generate the keypair as PKCS#8:
   `openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt -out okta-key.pem`, then
   register the **public** half. Point `privateKeyPem` at the private PEM.
4. If the app has **Require DPoP** enabled, turn it off — the connector does not
   implement DPoP.
5. To round-trip `externalId` or custom extension attributes, add the matching
   **custom profile attributes** in Okta and map them under `userMappings`.

**Auth method matters.** The Okta Org Authorization Server *requires*
`private_key_jwt` for client-credentials — a **client secret is rejected**
(`invalid_client: must use the private_key_jwt token_endpoint_auth_method`). The
connector also supports `clientSecret` (`client_secret_post`) for custom
authorization servers and quick pilots, but for provisioning against an Okta org
use the private key. See [caveats](#okta-caveats-learned-from-live-validation).

Semantics: SCIM `id` is the Okta user id; `active` maps to Okta status and drives
activate/deactivate/unsuspend lifecycle calls; `DELETE /Users/{id}` deactivates
only unless `deleteBehavior` is `"destroy"`; only `OKTA_GROUP` groups are managed.
Filters push down to Okta `search=` when expressible (`eq`, `sw`, ranges, `and`/`or`,
`pr`); anything else (`co`, `ne`, `not`, value-path filters) is evaluated in the
proxy over a bounded scan.

#### Okta caveats (learned from live validation)

- **Register the key as a JWK, not PEM.** Okta's "Add key" box parses **JSON** — a
  PEM paste fails with "Unable to parse JSON". Convert with any JWK tool (or the
  helper in [TESTING.md](TESTING.md)).
- **Okta assigns its own Key ID.** When you register a JWK, Okta may replace your
  `kid`. The connector omits `kid` by default so Okta matches the signature against
  its registered keys; only set the `kid` config field if you know it matches.
- **Group membership is eventually consistent.** A read immediately after a
  membership change can return stale membership; it settles within seconds. This is
  Okta behavior, not proxy behavior — any SCIM client sees it.

### LDAP backend (eDirectory / Active Directory / OpenLDAP / 389ds)

Set `backend.type` to `"ldap"` and pick a `flavor` (see `backendLdapExample` in the
sample config). A flavor is only a preset — every default it sets (naming attribute,
object classes, search filters, mappings, membership model, active-flag handling)
can be overridden in config.

| | eDirectory | Active Directory | OpenLDAP | 389ds |
|---|---|---|---|---|
| userName | `cn` | `sAMAccountName` | `uid` | `uid` |
| SCIM id | `GUID` | `objectGUID` | `entryUUID` | `entryUUID` |
| `active` | `loginDisabled` | `userAccountControl` bit 2 (other bits preserved) | none (reads true) | `nsAccountLock` |
| password | `userPassword` | `unicodePwd` (requires ldaps://) | `userPassword` | `userPassword` |
| membership | `member`+`groupMembership`, `equivalentToMe`+`securityEquals` (both sides written) | `member` only (AD derives `memberOf`) | `member` only | `member` only |

Membership is modeled as **member pairs**: each pair names the group-side attribute
(gets the user DN) and optionally the user-side attribute the connector must
maintain (gets the group DN). That is the whole difference between the directory
types, so a new directory is usually just a different `memberPairs` list.

The eDirectory group filter excludes iManager Role-Based Services scope objects
(`rbsScope` / `rbsScope2`), which are stored as `groupOfNames` and would otherwise
appear as bogus groups. Override `groups.filter` if your tree needs different
exclusions.

Other behaviors: SCIM filters translate to RFC 4515 LDAP filters (value-path
filters fall back to in-memory evaluation); renames happen via modify-DN when the
RDN-source attribute changes, and the SCIM id (directory GUID) survives them;
`deleteBehavior: "disable"` deactivates instead of deleting entries. On create, `cn`
(required by the `person` object class) is derived from `displayName` / formatted
name / given+family when it is not the RDN, so OpenLDAP-style flavors where `uid` is
the RDN create valid entries. Use the `389ds` flavor for 389 Directory Server /
Red Hat DS (entryUUID id, uid RDN, groupOfNames, and `activeStrategy: nsAccountLock`
so account enable/disable maps to the `nsAccountLock` attribute).

Group **members are included in list responses** (`membersInList`, default true) so
clients that render list rows without re-fetching each group still see membership.
Resolving each member DN to its id costs one lookup per member; set `membersInList:
false` for directories with very large groups where group-list latency matters, and
clients then read members via `GET /Groups/{id}`.

**TLS:** use an `ldaps://` URL. For lab directories with self-signed certificates,
`"insecureSkipTlsVerify": true` trusts any certificate chain **and** disables
hostname/IP verification (needed when connecting to a DC by IP whose cert lists only
its DNS name, typical for AD) — never use it in production.

**Referrals:** `referrals` (default `ignore`) controls how continuation references
are handled. Active Directory returns them when a search spans naming contexts (e.g.
from the domain root); the default ignores them so the search completes with the
in-scope results. Use `follow` to chase them, `throw` to surface them.

**Active Directory specifics:** creating a user runs add-disabled → set password
(`unicodePwd`, requires `ldaps://`) → enable, because AD refuses to create an enabled
account without a password. A user created without a `password` stays disabled until
one is set. Password changes require an `ldaps://` connection.

**Timeouts and paging:** `connectTimeoutMs` (default 5000), `readTimeoutMs`
(default 30000), and `pageSize` (default 500) are configurable per backend. Raise
`readTimeoutMs` when searching very large or partition-spanning subtrees, where the
directory may take tens of seconds to assemble the first page. Point `users.baseDn`
at the narrowest container that holds the entries you need — an unfiltered browse of
a huge root scope is slow regardless of timeout, though filtered searches still push
down and stay fast.

### JDBC / SQL backend

Set `backend.type` to `"jdbc"` (see `backendJdbcExample` in the sample config) to map
SCIM Users and Groups onto database tables — for apps that keep identities in SQL with
no SCIM of their own. Describe the tables and a SCIM-path → column mapping; the
connector discovers column types from the catalog and needs no other schema knowledge.

- `users.columns` maps SCIM paths to columns; a multi-valued attribute is addressed by
  a value-filter path (`emails[type eq "work"].value`). The `idColumn` is the SCIM id;
  auto-generated keys (serial/identity/`uuid DEFAULT …`) are read back on insert.
- `groups` needs a `displayNameColumn` and a `membership` join table
  (`groupColumn`/`userColumn`); members are resolved through it and included in list
  responses.
- Filters push down to parameterized SQL (never string-interpolated, so filter input
  can't inject); string `eq`/`co`/`sw`/`ew` are case-insensitive per SCIM. An unmapped
  attribute falls back to the core's bounded in-memory scan. PATCH is emulated by the
  core (get-apply-replace → `UPDATE`); PUT clears mapped columns absent from the payload.

The PostgreSQL driver is bundled; other databases work by adding their JDBC driver to
the classpath. Pagination uses `LIMIT`/`OFFSET` (Postgres/MySQL/H2/SQLite/MariaDB).

### File backend (CSV / JSON)

Set `backend.type` to `"file"` (see `backendFileExample`) to store SCIM Users and
Groups in CSV or JSON files — useful as a read source for an existing export, or a
sink that materializes provisioning as a file drop for downstream systems.

- `format` is `csv` or `json`; each resource type is one file with a SCIM-path →
  field mapping. Values are stringly typed and coerced to the SCIM attribute type
  (e.g. `active` "true"/"false" → boolean) using the schema.
- The `idColumn` holds the SCIM id (a UUID is generated on create). Group membership
  is stored inline in `membersColumn` — a delimited id list in CSV, a JSON array in
  JSON — and member display names are resolved from the users file.
- The whole file is held in memory, filtered/paginated there, and written back
  atomically (temp file + rename) on each change. Sized for provisioning volumes, not
  high write concurrency. No external dependencies (built-in CSV reader/writer).

## Modules

| Module | Contents |
|---|---|
| `scim-core` | JSON, resource model, schema registry/validator, filter parser+evaluator, PATCH engine, backend SPI |
| `connector-okta` | Okta Management API backend (OAuth2 private-key JWT) |
| `connector-ldap` | LDAP backend: eDirectory / Active Directory / OpenLDAP flavors, LDAPS |
| `connector-jdbc` | JDBC/SQL backend: config-driven table/column mapping, filter pushdown (PostgreSQL bundled) |
| `connector-file` | File backend: CSV / JSON, whole-file with atomic writes |
| `scim-server` | JDK HttpServer front end, bearer auth, endpoints, in-memory backend, runnable shaded jar |

Backends implement `com.pointblue.scim.spi.ScimBackendProvider` and are discovered
via `ServiceLoader`; select one with `backend.type` in the config. Built-in types:
`memory`, `okta`, `ldap`, `jdbc`, `file`.

## Testing

`mvn test` runs 106 unit/integration tests (in-memory backends, FakeOkta, in-memory
LDAP and SQL, temp files). For live end-to-end validation against a real Okta org,
LDAP directory, or database — including the setup gotchas above — see
**[TESTING.md](TESTING.md)**.

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 PointBlue Technology LLC.
