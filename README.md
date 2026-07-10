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
- **Done (LDAP):** one configurable LDAP connector covering eDirectory, Active
  Directory, and OpenLDAP via flavor presets — including per-directory group
  membership semantics, LDAPS, and an insecure trust-all toggle for lab
  directories. 81 tests total.
- **Next (M4+):** `/Bulk`, ETags, TLS listener, live smoke tests.

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

### LDAP backend (eDirectory / Active Directory / OpenLDAP)

Set `backend.type` to `"ldap"` and pick a `flavor` (see `backendLdapExample` in the
sample config). A flavor is only a preset — every default it sets (naming attribute,
object classes, search filters, mappings, membership model, active-flag handling)
can be overridden in config.

| | eDirectory | Active Directory | OpenLDAP |
|---|---|---|---|
| userName | `cn` | `sAMAccountName` | `uid` |
| SCIM id | `GUID` | `objectGUID` | `entryUUID` |
| `active` | `loginDisabled` | `userAccountControl` bit 2 (other bits preserved) | none (reads true) |
| password | `userPassword` | `unicodePwd` (requires ldaps://) | `userPassword` |
| membership | `member`+`groupMembership`, `equivalentToMe`+`securityEquals` (both sides written) | `member` only (AD derives `memberOf`) | `member` only |

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
`deleteBehavior: "disable"` deactivates instead of deleting entries.

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

## Modules

| Module | Contents |
|---|---|
| `scim-core` | JSON, resource model, schema registry/validator, filter parser+evaluator, PATCH engine, backend SPI |
| `connector-okta` | Okta Management API backend (OAuth2 private-key JWT) |
| `connector-ldap` | LDAP backend: eDirectory / Active Directory / OpenLDAP flavors, LDAPS |
| `scim-server` | JDK HttpServer front end, bearer auth, endpoints, in-memory backend, runnable shaded jar |

Backends implement `com.pointblue.scim.spi.ScimBackendProvider` and are discovered
via `ServiceLoader`; select one with `backend.type` in the config.

## Testing

`mvn test` runs 82 unit/integration tests (in-memory backends, FakeOkta, an
in-memory LDAP server). For live end-to-end validation against a real Okta org or
LDAP directory — including the setup gotchas above — see **[TESTING.md](TESTING.md)**.
