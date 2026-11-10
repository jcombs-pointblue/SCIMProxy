# Security Policy

## Reporting a vulnerability

Please report security vulnerabilities **privately** — do not open a public issue for
them.

- Preferred: use GitHub's **private vulnerability reporting** on this repository
  (the *Security* tab → *Report a vulnerability*).
- Alternatively, contact the maintainers at PointBlue Technology LLC.

Please include enough detail to reproduce the issue (affected version/commit,
configuration, and steps). We aim to acknowledge a report within a few business days
and will coordinate a fix and disclosure timeline with you.

## Scope

This project is a SCIM 2.0 proxy that brokers identity operations to backend systems.
Areas especially worth scrutiny:

- Inbound authentication (bearer-token handling) and request parsing.
- Filter and PATCH-path parsing (injection into downstream LDAP/SQL queries).
- Backend credential handling and outbound TLS (including the LDAP
  `insecureSkipTlsVerify` toggle, which is intended for lab use only).

## Supported versions

Security fixes are applied to the latest `1.x` release and land on `main`. Pin to a
tagged release (e.g. `v1.1.0`) for reproducible builds, and upgrade to the newest
`1.x` to receive fixes.

## Operational notes

- Keep real credentials out of the repo. Deployment config (`config/scim-proxy.json`),
  `*.pem`, and `*.p12` are git-ignored; supply secrets via `${ENV_VAR}` substitution.
- `insecureSkipTlsVerify: true` disables all TLS verification for an LDAP backend and
  must not be used in production.
