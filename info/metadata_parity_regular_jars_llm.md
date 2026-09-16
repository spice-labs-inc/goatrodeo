# Metadata Parity Guide: Regular JARs (LLM)

## Summary

The regular-JAR metadata parity suites (`RegularJarMetadataParitySuite`,
`MultiplePurlSuite`, `BestPurlSuite`) verify Goat Rodeo's metadata parity
for regular JARs. All pass.

## Key Tests

- **Canonical groupId from POM, not filename** — per-JAR tests named
  `<jar> — canonical pURL groupId from POM (not filename)` ("better than
  the reference scanner")
- **Canonical pURL matches Maven Central** — per-JAR tests named
  `<jar> — canonical pURL matches Maven Central` (coordinates manually
  verified)
- **pURL count >= reference scanner count** — explicit count checks
- **Companion POM wins over pom.properties/manifest in pipeline** — per-JAR
  tests named `<jar> — canonical pURL from companion POM in pipeline`

## Maven Central

Coordinates verified by fetching directory listings from
`https://repo1.maven.org/maven2/` on 2026-07-08.

## Test-Only

These suites are test-only; they verify the pURL resolution behavior,
they do not change it.