# Security policy

## Reporting a vulnerability

Report privately through
[GitHub Security Advisories](https://github.com/RRMADON26/palang/security/advisories/new).
Please do not open a public issue for a security problem.

Expect an acknowledgement within 72 hours.

## Scope

Palang sits in the request path and stores response bodies, so the areas that matter
most are:

- **Key collisions across tenants.** Keys are namespaced with a configurable prefix,
  but a custom `KeyResolver` that derives keys from attacker-controlled input without
  scoping them to a principal could let one caller read another's stored response.
  Scope keys to the authenticated user when requests are not already isolated.
- **Stored response contents.** Successful response bodies are held in the store for
  the configured TTL. If those bodies contain sensitive data, the store inherits that
  sensitivity — secure and expire Redis accordingly.
- **Memory exhaustion.** Bodies are buffered to be fingerprinted. `max-body-bytes`
  caps this at 1MB by default; raising it raises the exposure.

## Supported versions

Pre-1.0. Only the latest release receives fixes.
