# Metadata Parity Guide: Regular JARs (LLM)

## Summary

47 Phase 6 tests + 113 MultiplePurlSuite + 2 BestPurlSuite = 162 tests
verifying Goat Rodeo's metadata parity for regular JARs. All pass.

## Key Tests

- **Test 6.3** (12): Canonical groupId from POM, not filename ("better than the reference scanner")
- **Test 6.4** (12): Canonical pURL matches Maven Central (manually verified)
- **Test 6.5** (10): pURL count >= reference scanner count
- **Test 6.6** (12): Companion POM wins over pom.properties/manifest in pipeline

## Maven Central

12 coordinates verified by fetching directory listings from
`https://repo1.maven.org/maven2/` on 2026-07-08.

## No Production Code Changes

Phase 6 is test-only. Fixes were in Phases 2-4.
