# Testing

Two layers: an automated suite that runs on every build, and live end-to-end
runbooks for validating a connector against a real target system.

## Automated tests

```sh
mvn test        # 106 tests across all modules
```

| Module | What it covers | Test doubles |
|---|---|---|
| `scim-core` | JSON round-trip, RFC 7644 filter parser/evaluator, PATCH engine, schema validation | none |
| `scim-server` | full SCIM surface over real HTTP (Users/Groups CRUD, PATCH, `.search`, projection, pagination, auth, discovery); in-memory-filter fallback | `InMemoryBackend` |
| `connector-okta` | user/group CRUD, PATCH, lifecycle, filter pushdown, pagination, auth (both JWT and client-secret), error translation | `FakeOkta` (JDK HttpServer) |
| `connector-ldap` | eDirectory/AD/OpenLDAP flavors, membership pairs, active strategies, AD create ordering, RBS group exclusion, cn derivation, rename, filter escaping, LDAPS/referral/timeout env wiring | UnboundID in-memory LDAP server (test scope) |
| `connector-jdbc` | table/column mapping, CRUD, SQL filter pushdown, injection safety, unique conflict, groups via join table, pagination | H2 in-memory (PostgreSQL mode, test scope) |
| `connector-file` | CSV + JSON (parameterized) CRUD, RFC 4180 parsing, type coercion, filter/pagination, uniqueness, inline group membership, reads a hand-written export | temp files |

No live systems are touched by `mvn test`. The connector test doubles imitate the
real API/protocol closely enough to exercise the mapping and translation logic, but
they cannot catch org-specific policy or eventual-consistency behavior — that is
what the live runbooks below are for.

## Live validation — general practice

- **Isolate.** Provision into a dedicated container (LDAP) or use a preview/dev org
  (Okta). Never test against a production identity store's real users.
- **Verify independently.** After each SCIM call, confirm the effect through the
  target's own API/`ldapsearch` — do not trust only the proxy's response.
- **Keep secrets off disk and out of git.** Put credentials in environment
  variables and config files under a scratch dir outside the repo. `.gitignore`
  already blocks `config/scim-proxy.json`, `*.pem`, and `*.p12`. Shred keys when done.
- **Clean up.** Delete every object created. With `deleteBehavior: deactivate`
  (the default for both connectors' safe modes), a SCIM `DELETE` only disables —
  hard-delete via the target API to fully clean up.

---

## Runbook: Okta

### Prerequisites (Okta side)

1. **API Services app.** Admin console → Applications → Create App Integration →
   API Services.
2. **Scopes.** App → *Okta API Scopes* → grant `okta.users.manage`,
   `okta.users.read`, `okta.groups.manage`, `okta.groups.read`.
3. **Admin role.** App → *Admin roles* → assign one (Super Administrator for a test
   org). **Tokens issue without it, but every API call 403s.**
4. **Client authentication → Public key / Private key.** Generate a keypair and
   register the public half **as a JWK** (see the gotcha below):
   ```sh
   openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt -out okta-key.pem
   ```
5. **Disable "Require DPoP"** on the app (General → Client Credentials) if present —
   the connector does not implement DPoP proofs.

### Gotchas (each cost real debugging time; all now handled or documented)

| Symptom | Cause | Fix |
|---|---|---|
| `invalid_client: must use the private_key_jwt token_endpoint_auth_method` | The Org Authorization Server rejects client secrets outright. | Use private-key JWT. `clientSecret` in the connector is only for custom auth servers. |
| `invalid_dpop_proof: The DPoP proof JWT header is missing` | App has *Require DPoP* on. | Turn it off on the app. |
| "Unable to parse JSON" when adding the key | Okta's *Add key* box wants a **JWK**, not PEM. | Convert to JWK (script below). |
| `invalid_client` with a JWK that has a `kid` | Okta assigned its **own** `kid`; a mismatched `kid` in the assertion fails matching. | Omit `kid` (connector default) so Okta matches by trying keys, or set the exact `kid` Okta shows. |
| API calls 403 despite a valid token | App has no admin role. | Assign an admin role to the app. |
| Group members empty right after add, or removed member still present | Okta group membership is **eventually consistent**. | Re-read after a few seconds; not a proxy bug. |

Convert the public key to a JWK for Okta's *Add key* box:

```sh
python3 - okta-key.pem <<'EOF'
import base64, hashlib, subprocess, sys, json
mod = subprocess.check_output(["openssl","rsa","-in",sys.argv[1],"-noout","-modulus"]).decode()
n = bytes.fromhex(mod.strip().split("=")[1])
if n[0] == 0: n = n[1:]
b = lambda x: base64.urlsafe_b64encode(x).decode().rstrip("=")
kid = b(hashlib.sha256(n).digest())[:22]
print(json.dumps({"kty":"RSA","e":"AQAB","use":"sig","kid":kid,"alg":"RS256","n":b(n)}))
EOF
```

### Config

`okta-test-config.json` (private key path and secrets via env / scratch dir):

```json
{
  "server": { "port": 8092 },
  "auth": { "bearerTokens": [ { "name": "live-test", "token": "${SCIM_TEST_TOKEN}" } ] },
  "backend": {
    "type": "okta",
    "orgUrl": "https://YOUR-ORG.oktapreview.com",
    "clientId": "0oa...",
    "privateKeyPem": "/abs/path/okta-key.pem",
    "scopes": ["okta.users.manage","okta.users.read","okta.groups.manage","okta.groups.read"],
    "deleteBehavior": "deactivate"
  }
}
```

> Use the **org** domain (`your-org.oktapreview.com`), not the `-admin` console domain,
> in `orgUrl` — the token and API endpoints live on the org domain.

### Scenario (what a full run validates)

Start the proxy against the org, then drive the SCIM surface and confirm each step
via the Okta API:

1. **Health** → `GET /healthz` returns ok (proves auth + connectivity).
2. **Create user** → SCIM `id` is the Okta `00u…` id; mapped profile attributes
   (`email`, `title`, enterprise `department`) present in Okta.
3. **Filter pushdown** → `userName eq "…"` returns the user (translated to Okta
   `search=profile.login eq …`).
4. **PATCH `active:false`** → Okta status becomes `DEPROVISIONED`; `active:true`
   returns it to `PROVISIONED`/`ACTIVE`.
5. **PUT replace** (include all attributes to keep, e.g. `email` if the org requires
   it) → omitted attributes cleared; supplied ones written.
6. **Group create with members**, then **PATCH remove** via
   `members[value eq "…"]` → membership reconciles (allow for eventual consistency).
7. **Filter groups** by `displayName` → pushes down to Okta group `search`.
8. **DELETE user** with default `deleteBehavior` → returns 204, Okta status
   `DEPROVISIONED`, user still exists.
9. **Error translation** → a duplicate group returns SCIM `409 uniqueness`; an
   org-required-but-missing attribute returns `400 invalidValue`.

### Cleanup

`DELETE` only deactivates by default. To fully remove test objects, deactivate then
hard-delete via the Okta API:

```sh
# with a manage-scoped token:
curl -s -X DELETE  "$ORG/api/v1/groups/$GID"                 -H "Authorization: Bearer $T"
curl -s -X POST    "$ORG/api/v1/users/$UID/lifecycle/deactivate" -H "Authorization: Bearer $T"
curl -s -X DELETE  "$ORG/api/v1/users/$UID"                  -H "Authorization: Bearer $T"
```

Then shred the private key (`shred -u okta-key.pem`) and remove any token files.

**Last validated:** 2026-07-10 against an Okta Preview org — all nine steps passed,
org left clean.

---

## Runbook: LDAP (eDirectory / Active Directory / OpenLDAP / 389ds)

### Prerequisites

- A bind account with rights to create/modify/delete under a **dedicated test OU**.
- For LDAPS with a self-signed cert, `insecureSkipTlsVerify: true` (lab only).
- Confirm reachability and discover the tree first:
  ```sh
  export LDAPTLS_REQCERT=never   # only for self-signed lab certs
  ldapsearch -H ldaps://HOST:636 -x -D "BIND_DN" -w "$PW" \
    -s base -b "" namingContexts vendorVersion
  ```
- Create an isolated container so testing never touches real accounts:
  ```sh
  ldapadd -H ldaps://HOST:636 -x -D "BIND_DN" -w "$PW" <<'EOF'
  dn: ou=scim-test,BASE
  objectClass: organizationalUnit
  ou: scim-test
  EOF
  ```

### Config

```json
{
  "server": { "port": 8091 },
  "auth": { "bearerTokens": [ { "name": "live-test", "token": "${SCIM_TEST_TOKEN}" } ] },
  "backend": {
    "type": "ldap",
    "url": "ldaps://HOST:636",
    "bindDn": "BIND_DN",
    "bindPassword": "${LDAP_BIND_PW}",
    "insecureSkipTlsVerify": true,
    "flavor": "edirectory",
    "users":  { "baseDn": "ou=scim-test,BASE" },
    "groups": { "baseDn": "ou=scim-test,BASE" },
    "deleteBehavior": "delete"
  }
}
```

Pick `flavor` to match the directory: `edirectory`, `ad`, or `openldap`. Override any
preset (RDN attribute, object classes, search filter, mappings, `memberPairs`,
`activeStrategy`, `guidAttr`) in the same block if your schema differs.

### Scenario (what a full run validates)

Confirm each step with an independent `ldapsearch`:

1. **Health** → probes the user base DN.
2. **Create user** → entry appears at the expected DN; mapped attributes present;
   SCIM `id` is the directory GUID.
3. **Filter pushdown** → `userName eq` translates to an RFC 4515 filter and matches;
   a value like `*)(objectClass=*` is escaped and matches nothing (injection check).
4. **PATCH `active`** → toggles the flavor's active attribute (`loginDisabled`, or
   `userAccountControl` bit 2 with other bits preserved).
5. **Group membership** → for eDirectory, verify **all four** attributes on add
   (`member`, `equivalentToMe` on the group; `groupMembership`, `securityEquals` on
   the user) and that **all four** clear on remove. For AD/OpenLDAP, verify only the
   group-side `member` is written and no user-side attribute is touched.
6. **Rename** → change `userName`; the entry moves via modify-DN and the SCIM `id`
   (GUID) stays stable.
7. **PUT replace** → omitted mapped attributes cleared; supplied ones kept.
8. **DELETE** → removes the entry (`delete`) or disables it (`disable`).

### Cleanup

```sh
ldapdelete -H ldaps://HOST:636 -x -D "BIND_DN" -w "$PW" "ou=scim-test,BASE"  # recursively empty first
```

**Last validated:** 2026-07-10 against NetIQ eDirectory 9.3 over LDAPS
(self-signed, `insecureSkipTlsVerify`) — all steps passed, test OU removed.

### LDAP — validated live

- **eDirectory 9.3** over LDAPS (self-signed, `insecureSkipTlsVerify`) — full run
  above, 2026-07-10.
- **Active Directory** (Windows Server, a lab domain) over LDAPS,
  2026-07-10: browse, filter pushdown, `active`↔`userAccountControl`, group
  membership (group-side `member` only), create (add-disabled → set `unicodePwd` →
  enable), delete-disable. Surfaced and fixed: LDAPS-by-IP hostname verification,
  domain-root continuation references (`referrals`), and AD create ordering.
- **389 Directory Server 3.1.2** via the `389ds` flavor, 2026-07-10: browse,
  get-by-id (`entryUUID`), filter pushdown (`uid`), create (with derived `cn`),
  group create/membership, `active`↔`nsAccountLock` toggle, delete-disable, delete.

Watch on a real AD: the default user filter includes `objectCategory=person`
(correct on AD; the in-memory test server omits it, so tests override the filter),
and password writes require `ldaps://`.

---

## Runbook: JDBC / SQL

Configure `backend.type: "jdbc"` with the user/group tables and a SCIM-path → column
mapping (see `backendJdbcExample` in the sample config). Validate the same scenario
as the other backends, confirming each step with a direct SQL query.

**Last validated:** 2026-07-10 against PostgreSQL 17 — browse, SQL filter pushdown,
create, PATCH (get-apply-replace → `UPDATE`), group create/membership add-remove via
the join table, delete; originals left intact. Not yet run against MySQL / SQL Server
(pagination is `LIMIT`/`OFFSET`; SQL Server would need an `OFFSET/FETCH` variant).

## Runbook: File (CSV / JSON)

Configure `backend.type: "file"` with `format` and per-resource-type files (see
`backendFileExample`). No external server needed — inspect the files on disk to
verify.

**Last validated:** 2026-07-10 (CSV) — reads a pre-existing export, create/PATCH
append and update rows, group membership persisted as a delimited id list with
display resolved from the users file. JSON format covered by the parameterized unit
suite.
