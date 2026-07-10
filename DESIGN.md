# SCIM 2.0 Proxy Server — Design

**Status:** Draft v1 · 2026-07-10
**Pilot backend:** Okta Management API

> **Implemented since this design.** The core, HTTP server, and SPI are built as
> designed. Five backends now ship: `memory`, `okta` (private-key JWT and
> client-secret), `ldap` (eDirectory / Active Directory / OpenLDAP flavors, LDAPS),
> `jdbc` (config-driven table mapping, PostgreSQL bundled), and `file` (CSV / JSON).
> Okta, eDirectory, Active Directory, 389 Directory Server, and PostgreSQL have been
> validated against live systems — see [TESTING.md](TESTING.md). The section below is
> the original Okta-pilot design; the backend-independent core (§5–§8) applies to all
> connectors.

## 1. Purpose

A standalone SCIM 2.0 server (RFC 7643/7644) that fronts platforms which do not expose
their own SCIM interface. SCIM clients (IdPs, IGA tools, custom provisioning engines)
talk standard SCIM to this server; a pluggable **backend connector** translates each
operation into the target platform's native API. The pilot connector targets the Okta
Management API, allowing external systems to provision into an Okta org via SCIM.

### Goals
- Full SCIM 2.0 server surface: `/Users`, `/Groups`, `/Bulk`, `/ServiceProviderConfig`,
  `/Schemas`, `/ResourceTypes`, `/Me` (501), `.search`.
- Complete RFC 7644 filter grammar and full PATCH semantics (including `valuePath`
  filters like `members[value eq "..."]`).
- Schema extensions defined in configuration — no code changes to add custom
  attributes or new extension URNs.
- Pluggable backend SPI; connectors implement only native-API translation and declare
  their capabilities. Everything protocol-shaped lives in the core.
- Minimal dependencies: **zero runtime dependencies** (JDK 21 only). Test-scope
  dependencies (JUnit 5) allowed.

### Non-goals (for the pilot)
- Acting as a SCIM *client* (outbound provisioning).
- Multi-tenancy beyond "one config = one backend instance" (see §12 roadmap).
- Persistent local identity store — the proxy is stateless with respect to identity
  data; the backend is the system of record.
- Sorting (`sortBy`) pushdown — accepted but applied in-memory only when feasible.

## 2. High-level architecture

```
 SCIM client (IdP / IGA / NetIQ IDM driver / curl)
        │  HTTPS + Bearer token
        ▼
┌──────────────────────────────────────────────────────┐
│  scim-server (single JVM, zero runtime deps)         │
│                                                      │
│  http        JDK HttpServer, TLS, virtual threads    │
│  auth        static bearer-token authenticator       │
│  protocol    routing, content negotiation, errors    │
│  core        ScimResource model, SchemaRegistry,     │
│              FilterParser/AST, PatchEngine,          │
│              BulkExecutor, pagination                │
│  spi         ScimBackend / ResourceBackend           │
│  json        minimal JSON parser + writer (internal) │
│                                                      │
│  connector-okta   Okta API mapping, OAuth2           │
│                   private-key JWT, rate-limit        │
│                   backoff, cursor↔offset paging      │
└──────────────────────────────────────────────────────┘
        │  HTTPS (java.net.http.HttpClient)
        ▼
   Okta Management API  (/api/v1/users, /api/v1/groups,
                         /oauth2/v1/token)
```

One process, one listener, one active backend per deployment (pilot). The SPI boundary
is the only coupling point between protocol and connector.

## 3. Technology choices

| Concern | Choice | Rationale |
|---|---|---|
| Language / runtime | Java 21 (LTS) | Virtual threads make thread-per-request cheap; records/sealed types for the AST. |
| HTTP server | `com.sun.net.httpserver.HttpServer` + `HttpsServer` | Zero deps, TLS support; adequate for provisioning traffic (low RPS, latency dominated by the backend call). |
| HTTP client | `java.net.http.HttpClient` | Zero deps, HTTP/2, async-capable. |
| JSON | Small internal module (`scim.json`) | SCIM payloads are plain JSON; a strict ~600-line parser/writer is well-bounded. Isolated behind a `Json` facade so Jackson can be swapped in later with a one-package change. |
| JWT signing (Okta) | `java.security.Signature` (RS256) + Base64URL | A client-assertion JWT is just two Base64URL JSON segments and an RSA-SHA256 signature — no library needed. |
| Build | Maven, single multi-module reactor | Ubiquitous; enforces the SPI boundary via module deps. |
| Tests | JUnit 5 (test scope only) | Fake Okta implemented with the same JDK HttpServer. |

## 4. Module layout

```
scim-proxy/
├── scim-core/                    # no deps on http or any connector
│   └── src/main/java/com/pointblue/scim/
│       ├── json/                 # Json.parse/write, JsonValue tree
│       ├── model/                # ScimResource, AttributePath, Meta
│       ├── schema/               # Schema, Attribute, SchemaRegistry, Validator
│       ├── filter/               # Lexer, Parser, AST (sealed), Evaluator
│       ├── patch/                # PatchOp, PatchEngine
│       └── spi/                  # ScimBackend, ResourceBackend, capabilities
├── scim-server/                  # depends on scim-core
│   └── src/main/java/com/pointblue/scim/server/
│       ├── http/                 # server bootstrap, TLS, router
│       ├── auth/                 # BearerAuthenticator
│       ├── endpoints/            # Users, Groups, Bulk, discovery, .search
│       ├── bulk/                 # BulkExecutor
│       └── config/               # config loading, env-var substitution
├── connector-okta/               # depends on scim-core only
│   └── src/main/java/com/pointblue/scim/okta/
│       ├── OktaBackend.java
│       ├── OktaUsers.java / OktaGroups.java
│       ├── auth/                 # PrivateKeyJwt, TokenCache
│       ├── map/                  # AttributeMapper (config-driven)
│       └── http/                 # OktaClient: retries, rate-limit backoff
└── scim-dist/                    # shaded runnable jar + sample config
```

Connectors are discovered via `ServiceLoader<ScimBackendProvider>` and selected by
name in configuration — adding a connector means adding a jar to the classpath and a
config block, no core changes.

## 5. Core resource model

SCIM resources are **not** mapped to static POJOs — that would make config-driven
schema extensions impossible. Instead:

```java
public final class ScimResource {
    private final Set<String> schemas;                 // schema URNs present
    private final Map<String, JsonValue> core;         // attrs of the base schema
    private final Map<String, Map<String, JsonValue>> extensions; // urn -> attrs
    private final Meta meta;                           // resourceType, created, ...
    // typed accessors: getString(path), getMulti(path), with(path, value) ...
}
```

- `AttributePath` implements RFC 7644 attribute-path syntax
  (`urn:...:User:name.givenName`, `emails[type eq "work"].value`) and is shared by
  the filter evaluator, PATCH engine, and `attributes`/`excludedAttributes`
  projection.
- All validation (required, mutability, canonicalValues, caseExact, uniqueness
  declaration, unknown-attribute rejection) happens in `schema.Validator` against the
  `SchemaRegistry` — connectors never validate protocol-level correctness.
- `returned=never` attributes (e.g. `password`) are stripped centrally on the way out.

## 6. Schema registry and extensions

Built-in schemas (shipped as classpath JSON resources, verbatim from RFC 7643):
core User, core Group, Enterprise User extension, plus the ServiceProviderConfig /
ResourceType / Schema meta-schemas.

**Extensions are pure configuration.** A deployment adds files to `config/schemas/`:

```jsonc
// config/schemas/pointblue-user.json  — standard RFC 7643 schema representation
{
  "id": "urn:pointblue:scim:schemas:extension:custom:2.0:User",
  "name": "PointBlueUser",
  "attributes": [
    { "name": "costCenter", "type": "string", "multiValued": false, ... },
    { "name": "badges", "type": "complex", "multiValued": true,
      "subAttributes": [ ... ] }
  ]
}
```

and a resource-type binding in the main config attaching the extension (required or
optional) to `User` or `Group`. The registry then automatically:

- serves the schema at `/Schemas/{urn}` and lists it in `/ResourceTypes`,
- validates inbound payloads including the extension namespace,
- makes extension attributes addressable in filters and PATCH paths.

How an extension attribute reaches the backend is the **connector's** concern, via
its own mapping config (§9.3). An extension attribute with no backend mapping is
rejected at startup (fail fast) unless marked `"unmapped": "ignore"`.

## 7. SCIM protocol layer

### 7.1 Endpoints

| Route | Notes |
|---|---|
| `GET/POST /Users`, `GET/PUT/PATCH/DELETE /Users/{id}` | full CRUD |
| `POST /Users/.search` | body-based search, same code path as GET list |
| same for `/Groups` | |
| `POST /Bulk` | §7.4 |
| `GET /ServiceProviderConfig` | generated from active backend capabilities |
| `GET /Schemas`, `/Schemas/{urn}`, `/ResourceTypes`, `/ResourceTypes/{name}` | from registry |
| `GET /Me` | 501 Not Implemented |

`Content-Type: application/scim+json` produced; `application/json` accepted.
Errors use the `urn:ietf:params:scim:api:messages:2.0:Error` shape with proper
`scimType` (`invalidFilter`, `invalidPath`, `mutability`, `uniqueness`, `tooMany`,
...). All backend/native errors are translated at the SPI boundary — connectors throw
`BackendException(status, scimType, detail)` and nothing else escapes.

### 7.2 Filter grammar

Full RFC 7644 §3.4.2.2 grammar: `eq ne co sw ew gt ge lt le pr`, `and or not`,
grouping, and `valuePath` (`emails[type eq "work" and value co "@x.com"]`).
Hand-written lexer + recursive-descent parser → immutable sealed AST:

```java
sealed interface Filter permits Compare, Present, And, Or, Not, ValuePath {}
```

The AST is handed to the backend inside `SearchRequest`. The connector returns either
a native query translation or `PushdownResult.UNSUPPORTED`, in which case the core
**post-filters in memory** using `filter.Evaluator` over pages fetched from the
backend. Pushdown-vs-fallback is per-AST-node-tree, not per-operator, so the common
IdP probes (`userName eq "jdoe"`) always push down even when an exotic clause would
not.

### 7.3 PATCH engine

Full RFC 7644 §3.5.2 semantics implemented once in `scim-core`:
`add`/`replace`/`remove`, no-path add/replace (merge), valuePath targets, multi-value
member manipulation, mutability enforcement (`readOnly` targets → 400 `mutability`).

Connector integration: `ResourceBackend.patch()` receives the parsed ops. The default
SPI implementation does **get → apply(PatchEngine) → replace**; a connector overrides
it when the native API has cheaper primitives (Okta does: partial profile update
`POST /users/{id}`, and group membership `PUT/DELETE /groups/{gid}/users/{uid}`).
The Okta connector pattern-matches common op shapes (membership add/remove, simple
attribute replace, `active` flips) to native calls and falls back to
get-apply-replace for anything else.

### 7.4 Bulk

- Limits advertised in `ServiceProviderConfig` (default `maxOperations: 100`,
  `maxPayloadSize: 1 MiB`) and enforced with 413.
- Operations execute **sequentially in request order** (simple, deterministic; the
  backend is the bottleneck anyway).
- `bulkId` resolution: temporary ids created by earlier POSTs are substituted into
  later operations' paths and `$ref`/`value` member fields; unresolved or circular
  references → per-operation 409 `invalidValue`.
- `failOnErrors` honored; response mirrors request order with per-op status.

### 7.5 Pagination and listing

SCIM is 1-based offset (`startIndex`/`count`); connectors declare their native model.
For cursor-based backends (Okta) the core provides a **cursor bridge**: a bounded,
TTL'd in-memory cache keyed by `(auth-principal, filter, count)` mapping page
boundaries to native cursors, so a well-behaved client walking pages sequentially
never re-scans. Random `startIndex` access degrades to walking cursors from the start
— documented limitation, irrelevant for IdP reconciliation patterns which walk
sequentially. `totalResults` is served when the backend can supply it cheaply,
otherwise estimated per RFC (clients must tolerate `totalResults` absence — we return
the fetched-so-far count with `itemsPerPage` semantics kept exact).

### 7.6 ETags / concurrency

`meta.version` is a weak ETag derived from the backend's change stamp (Okta:
`lastUpdated`). `If-Match` on PUT/PATCH/DELETE is honored when present (412 on
mismatch); advertised as `"etag": {"supported": true}` only if the active backend
provides a change stamp.

## 8. Backend SPI

```java
public interface ScimBackendProvider {           // ServiceLoader entry point
    String name();                               // "okta"
    ScimBackend create(BackendConfig cfg, SchemaRegistry schemas);
}

public interface ScimBackend extends AutoCloseable {
    Capabilities capabilities();                 // patch-native?, sort?, etag?,
                                                 // filter pushdown probe, paging model
    ResourceBackend resource(String type);       // "User", "Group", ...
    void healthCheck() throws BackendException;  // used by /healthz and startup
}

public interface ResourceBackend {
    ScimResource create(ScimResource r) throws BackendException;
    ScimResource get(String id) throws BackendException;
    ScimResource replace(String id, ScimResource r) throws BackendException;
    default ScimResource patch(String id, List<PatchOp> ops) { /* get-apply-replace */ }
    void delete(String id) throws BackendException;
    SearchResult search(SearchRequest req) throws BackendException;
        // SearchRequest: Filter AST, projection, page token/offset, count
        // SearchResult: resources, nextPageToken?, totalResults?
}
```

Design rules:
- Connectors speak `ScimResource` in and out; **all mapping to native shapes is
  internal to the connector.**
- Connectors never see raw HTTP requests, never produce SCIM error JSON, never
  paginate for the client — they translate one operation to native call(s).
- `Capabilities` drives `ServiceProviderConfig` generation, so the advertised
  feature set is always truthful for the active backend.

## 9. Okta connector

### 9.1 Authentication — OAuth2 private-key JWT

Service app (`client_credentials`) with scoped grants `okta.users.manage`,
`okta.users.read`, `okta.groups.manage`, `okta.groups.read`.

- Build client-assertion JWT (`RS256`, `aud` = `{org}/oauth2/v1/token`, 60 s lifetime)
  signed with a locally held PEM private key via JDK crypto.
- Exchange at `/oauth2/v1/token`; cache the access token, refresh at
  `expires_in − 60 s`, single-flight refresh under contention.
- Key supplied as PEM file path in config; JWKS registration on the Okta service app
  is a documented setup step.

### 9.2 Endpoint mapping

| SCIM operation | Okta call(s) |
|---|---|
| `POST /Users` | `POST /api/v1/users?activate={active}` |
| `GET /Users/{id}` | `GET /api/v1/users/{id}` |
| `GET /Users?filter=` | `GET /api/v1/users?search=...` (pushdown, §9.4) |
| `PUT /Users/{id}` | `PUT /api/v1/users/{id}` (full profile replace) + lifecycle call if `active` changed |
| `PATCH /Users/{id}` | `POST /api/v1/users/{id}` (partial update) / lifecycle / fallback |
| `DELETE /Users/{id}` | deactivate → delete (§9.5) |
| `POST /Groups` | `POST /api/v1/groups` |
| `PATCH /Groups/{id}` members | `PUT/DELETE /api/v1/groups/{gid}/users/{uid}` |
| `GET /Groups/{id}` members | `GET /api/v1/groups/{gid}/users` (paged, assembled) |

`meta.created/lastModified` ← Okta `created`/`lastUpdated`; SCIM `id` ← Okta id;
`externalId` stored as a mapped profile attribute (default: custom attribute
`externalId` on the Okta user profile, created during setup).

### 9.3 Attribute mapping (config-driven)

Default mapping ships built-in (userName↔login, name.givenName↔firstName,
name.familyName↔lastName, primary email↔email, secondary↔secondEmail,
phoneNumbers[type eq "mobile"]↔mobilePhone, enterprise ext: department, division,
organization, employeeNumber, manager↔managerId, ...). Deployments extend it:

```jsonc
// config/mapping/okta-user.json
{
  "resourceType": "User",
  "map": [
    { "scim": "urn:pointblue:...:User:costCenter", "okta": "profile.costCenter" },
    { "scim": "title",                             "okta": "profile.title" }
  ]
}
```

Extension attributes map to **Okta custom profile attributes**; the connector
verifies at startup (via the Schemas API) that each mapped Okta attribute exists,
failing fast with a clear message otherwise.

### 9.4 Filter pushdown

Okta's `search=` parameter supports `eq co sw gt ge lt le pr and or not` over profile
attributes — nearly the whole SCIM grammar. The connector walks the AST and rewrites
SCIM paths to Okta paths through the same mapping table
(`userName` → `profile.login`). Nodes referencing unmapped attributes, `ew`, or
`valuePath` filters make that subtree unsupported → core in-memory fallback (whole
tree falls back only if the unsupported node isn't AND-prunable).

### 9.5 Semantics decisions

- `active: false` → `POST .../lifecycle/deactivate`; `active: true` on a deactivated
  user → `.../lifecycle/activate?sendEmail=false`. Okta `status` → SCIM `active`
  (`ACTIVE`, `PASSWORD_EXPIRED`, `RECOVERY`, `LOCKED_OUT` → true; `DEPROVISIONED`,
  `SUSPENDED` → false).
- **`DELETE /Users/{id}` performs deactivate only** by default
  (`okta.deleteBehavior: "deactivate"`); `"destroy"` opts into the irreversible
  two-step deactivate+delete. Safe default because SCIM clients routinely DELETE on
  unassignment.
- Passwords: `password` accepted on create/replace (write-only), forwarded to Okta
  `credentials.password.value`; never returned.
- Groups are restricted to `OKTA_GROUP` type; attempts to modify AD-mastered or
  APP_GROUP groups return 403 with a clear detail message.

### 9.6 Resilience

- Honor `x-rate-limit-remaining` / `x-rate-limit-reset`: proactive throttle below a
  configurable remaining-fraction, and on 429 wait until reset (bounded), retry once,
  then surface SCIM 429 with `Retry-After`.
- Retries with jittered backoff on 5xx/connect errors for **idempotent** calls only.
- Timeouts: connect 5 s, request 30 s (configurable).

## 10. Configuration

Single JSON file (`config/scim-proxy.json`), `${ENV_VAR}` substitution for secrets:

```jsonc
{
  "server": { "port": 8443, "baseUrl": "https://scim.example.com",
              "tls": { "keystore": "config/tls.p12", "password": "${TLS_PW}" } },
  "auth":   { "bearerTokens": [ { "name": "idm-driver", "token": "${SCIM_TOKEN_IDM}" } ] },
  "schemas":   { "dir": "config/schemas" },
  "resourceTypes": {
    "User": { "extensions": [ { "schema": "urn:pointblue:...:User", "required": false } ] }
  },
  "backend": {
    "type": "okta",
    "orgUrl": "https://acme.okta.com",
    "clientId": "0oa...",
    "privateKeyPem": "config/okta-key.pem",
    "scopes": ["okta.users.manage","okta.groups.manage"],
    "deleteBehavior": "deactivate",
    "mappingDir": "config/mapping"
  },
  "limits": { "maxBulkOperations": 100, "maxPayloadBytes": 1048576, "maxPageSize": 200 }
}
```

Startup fail-fast validation: schema files parse, mappings resolve against both the
schema registry and (for Okta) the live org schema, token exchange succeeds,
`healthCheck()` passes.

## 11. Security

- TLS required (HTTP only behind an explicit `--insecure-dev` flag).
- Bearer tokens compared constant-time; per-token names appear in access logs, token
  values never logged. 401 with `WWW-Authenticate: Bearer` on failure.
- Request bodies capped (`maxPayloadBytes`); JSON parser enforces depth/size limits.
- Secrets only via env-var substitution; config files can be world-readable.
- Access log: method, path (query stripped of filter values at INFO), principal,
  status, duration, Okta call count. `java.util.logging` with a JSON formatter —
  no logging dependency.

## 12. Threading model

`HttpServer` with `Executors.newVirtualThreadPerTaskExecutor()` — thread-per-request,
blocking style throughout (simple code, and the JDK `HttpClient` parks virtual
threads efficiently). No shared mutable state outside the token cache and cursor
cache, both small and lock-guarded.

## 13. Testing

- **Unit (bulk of coverage):** filter lexer/parser (RFC examples + fuzz-ish
  malformed inputs), filter evaluator, PATCH engine (RFC §3.5.2 examples, valuePath
  edge cases), schema validator, JSON round-trip, attribute-path parsing.
- **Connector:** `FakeOkta` — an in-memory Okta implemented on the JDK HttpServer
  honoring the subset of the Okta API we use, including rate-limit headers and
  cursor paging. Full CRUD/PATCH/search/bulk suites run against it in CI.
- **Contract:** one suite that runs the *entire SCIM surface* against
  `ScimBackend` implementations generically — every future connector inherits it.
- **Live smoke (manual/tagged):** against a real Okta dev org.

## 14. Milestones

1. **M1 — skeleton:** json module, model, schema registry, HTTP server, auth,
   discovery endpoints, in-memory demo backend, contract-test harness.
2. **M2 — protocol core:** filter parser/evaluator, PATCH engine, pagination,
   errors; Users CRUD end-to-end against the in-memory backend.
3. **M3 — Okta users:** connector auth, user mapping, pushdown, lifecycle,
   FakeOkta tests; live smoke.
4. **M4 — groups + bulk:** Groups CRUD/membership both layers, BulkExecutor,
   ETags.
5. **M5 — hardening:** rate-limit behavior, cursor bridge, config validation,
   packaging (shaded jar + systemd/container docs).

## 15. Open questions

1. `externalId` storage: default plan is a custom Okta profile attribute — confirm
   the org allows adding it, or choose an existing attribute.
2. Should `PUT /Users/{id}` clearing an attribute null it in Okta (true replace) or
   preserve unmapped Okta-side attributes? Proposed: true replace for *mapped*
   attributes only; unmapped Okta profile attributes untouched.
3. Group `members` on large groups: return members up to a cap (default 1000) with
   full list via paged `GET /Groups/{id}?attributes=members`+cursor, or always
   truncate? Affects IGA reconciliation.
4. Is multi-org (multiple Okta tenants behind one proxy, path- or token-routed)
   needed soon? It changes config shape; cheap now, annoying later.
