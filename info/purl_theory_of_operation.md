# pURL Resolution: Theory of Operation

## Overview

Goat Rodeo resolves Maven package URLs (pURLs) for JAR, WAR, EAR, and
POM files. This document explains how pURL resolution works, the
priority chain, and how Goat Rodeo achieves metadata parity.

## pURL Format

Goat Rodeo emits pURLs in the format:

```
pkg:maven/<groupId>/<artifactId>@<version>[?<qualifier>=<value>]
```

Qualifiers:
- `?packaging=sources` — for pURLs from sources JARs
- `?classifier=javadoc` — for pURLs from javadoc JARs
- `?type=pom` — for pURLs from POM files

## Canonical pURL

Each artifact gets exactly one **canonical pURL** — the "best" pURL,
determined by priority chain. The canonical pURL is stored in the Item's
metadata under the `CanonicalPurl` key.

### Priority Chain (highest to lowest)

1. **Companion POM (external)** — the sibling `.pom` file paired with
   the JAR by `computeMavenFiles`. This is the authoritative published
   Maven metadata. See ADR 0012.
2. **Embedded `pom.properties`** — `META-INF/maven/.../pom.properties`
   inside the JAR. When multiple exist (fat/uber JARs), only the one
   whose artifactId best matches the filename is selected via `matchScore`.
   See ADR 0014.
3. **Embedded POM** — `META-INF/maven/.../pom.xml` inside the JAR,
   selected by the same `matchScore` logic.
4. **MANIFEST.MF** (for groupId and version) / **Filename** (for artifactId) —
   OSGi headers and standard headers. For artifactId, filename has higher
   priority than manifest because `Implementation-Title` is human-readable.
5. **Filename heuristics** (for groupId and version) / **MANIFEST.MF**
   (for artifactId) — last resort.

### Field-Level Independence

Each field (groupId, artifactId, version) is resolved independently from
the highest-priority source that provides it. This means the canonical
pURL can have groupId from the companion POM, version from pom.properties,
and artifactId from the filename — whichever source has the highest
priority for each individual field.

**Verified by:** `MavenPropertyTests` — "resolveGroupIdArtifactIdVersion:
falls through each layer deterministically" and "external POM always wins
when complete".

## Secondary pURLs

Beyond the canonical pURL, Goat Rodeo emits **secondary pURLs** for each
embedded `pom.properties` entry that doesn't match the canonical. These
represent dependencies bundled inside uberjar/fat JARs.

- Secondary pURLs come **exclusively** from `pom.properties` — never from
  the filename.
- Secondary pURLs from sources JARs include `?packaging=sources`.
- Secondary pURLs from javadoc JARs include `?classifier=javadoc`.

**Verified by:** `Phase4SecondaryClassifierSuite` — guard test for no
filename in secondary pURLs. `Phase7aPropertySuite` — Property 7.4.

## Matching Algorithm

When multiple `pom.properties` entries exist (common in fat JARs), the
`matchScore` algorithm selects the one whose artifactId best matches the
JAR filename:

| Match Type | Score | Example |
|-----------|-------|---------|
| Exact match | 3 | `commons-lang3` matches `commons-lang3-3.9.jar` |
| Prefix match with separator | 2 | `commons-lang` matches `commons-lang3-3.9.jar` |
| Reverse prefix match with separator | 1 | `commons-lang3` matches `commons-lang-2.6.jar` |
| No match | 0 | `logback-classic` vs `commons-lang3-3.9.jar` |

Among same-score matches, the longest artifactId is preferred.

**Verified by:** `Phase3MatchingSuite` — 13 unit tests.
`Phase3MatchingPropertySuite` — 2 property tests.

## Sources and Javadoc JARs

Sources JARs (`-sources.jar`) and javadoc JARs (`-javadoc.jar`) are NOT
treated as main JARs. They are processed as companions to main JARs or
as standalone artifacts.

All pURLs from sources JARs include `?packaging=sources`.
All pURLs from javadoc JARs include `?classifier=javadoc`.

**Verified by:** `Phase5MetadataParitySourcesJavadocSuite` — 53 corpus tests.
`Phase4SecondaryClassifierPropertySuite` — Properties 4.9, 4.10.

## Metadata Parity

Goat Rodeo's pURLs are a superset of the reference scanner's pURLs:
- Goat Rodeo finds every pURL the reference scanner finds (verified by `MultiplePurlSuite`)
- Goat Rodeo adds classifiers to sources/javadoc pURLs (the reference scanner doesn't)
- Goat Rodeo uses companion POMs for canonical pURL (the reference scanner doesn't)
- Goat Rodeo's canonical pURLs match real Maven Central artifacts

**Verified by:**
- `MultiplePurlSuite` — 113 tests (superset)
- `Phase6MetadataParityRegularBestPurlSuite` — 47 tests (Maven Central validation)
- `BestPurlSuite` — 2 tests (field-level merge)

## Test Summary

| Suite | Tests | What |
|-------|-------|------|
| MavenPhase1Suite | 60 | groupId/artifactId/version chain, filename parsing |
| MavenPropertyTests | 37 | Property tests for interpolation, priority, parsing |
| Phase2CanonicalPrioritySuite | 10 | Canonical pURL priority chain |
| Phase3MatchingSuite | 13 | matchScore algorithm |
| Phase3MatchingPropertySuite | 2 | Property tests for matching |
| Phase4SecondaryClassifierSuite | 8 | Classifier on secondary pURLs |
| Phase4SecondaryClassifierPropertySuite | 2 | Property tests for classifier |
| Phase5MetadataParitySourcesJavadocSuite | 53 | Corpus tests for sources/javadoc |
| Phase6MetadataParityRegularBestPurlSuite | 47 | Maven Central validation |
| Phase7aPropertySuite | 5 | Cross-cutting property tests |
| MultiplePurlSuite | 113 | Metadata parity (superset) |
| BestPurlSuite | 2 | Field-level merge |
| **Total pURL-related** | **352** | |
