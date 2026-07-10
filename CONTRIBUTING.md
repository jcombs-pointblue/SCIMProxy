# Contributing

Thanks for your interest in the SCIM 2.0 Proxy Server. This guide covers building,
testing, and the conventions the codebase follows.

## Build and test

Requires JDK 21+ and Maven.

```sh
mvn package        # build all modules + the runnable shaded jar
mvn test           # run the full test suite
```

`mvn test` uses only in-memory/embedded doubles (in-memory backends, FakeOkta,
UnboundID in-memory LDAP, H2, temp files) — no live systems are contacted. Live
end-to-end validation against real targets is documented in [TESTING.md](TESTING.md).

## Project layout

- `scim-core` — protocol-shaped logic: JSON, schema registry/validator, RFC 7644
  filter parser+evaluator, PATCH engine, and the backend SPI. **Zero dependencies.**
- `scim-server` — JDK `HttpServer` front end, bearer auth, endpoints, in-memory
  reference backend, runnable jar.
- `connector-*` — backends (Okta, LDAP, JDBC, file). Each implements the SPI and
  inherits the entire protocol layer.

## Adding a backend connector

1. Create a `connector-<name>` module depending on `scim-core`.
2. Implement `com.pointblue.scim.spi.ScimBackendProvider` (and `ScimBackend` /
   `ResourceBackend`), returning honest `Capabilities` so `ServiceProviderConfig` and
   the core's fallbacks stay truthful.
3. Register it via `META-INF/services/com.pointblue.scim.spi.ScimBackendProvider`.
4. Add it as a `runtime` dependency of `scim-server` so it is bundled and selectable
   by `backend.type`.

Connectors translate one SCIM operation to native calls and throw only
`BackendException`; they never parse HTTP, produce SCIM error JSON, or validate
protocol correctness — the core owns that.

## Conventions

- **Keep `scim-core` dependency-free.** Connector modules may add their own runtime
  dependency (e.g. a JDBC driver); the core and server may not.
- Every source file carries an SPDX header:
  ```java
  // SPDX-License-Identifier: MIT
  // Copyright (c) 2026 PointBlue Technology LLC
  ```
- Match the surrounding code style. Prefer clear names and comments that explain
  constraints or non-obvious protocol/directory behavior, not restatements of code.
- New behavior needs tests. Bugs found against live systems should get a regression
  test against the relevant in-memory double.

## Pull requests

- Keep changes focused; write a descriptive commit message (this repo uses
  Conventional Commits, e.g. `feat(ldap): …`, `fix(jdbc): …`, `docs: …`).
- Run `mvn test` before opening the PR.
- By contributing, you agree your contributions are licensed under the project's
  [MIT License](LICENSE).
