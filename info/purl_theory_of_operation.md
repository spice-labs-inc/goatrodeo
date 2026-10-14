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
   Maven metadata.
2. **Embedded `pom.properties`** — `META-INF/maven/.../pom.properties`
   inside the JAR. When multiple exist (fat/uber JARs), only the one
   whose artifactId best matches the filename is selected via `matchScore`.
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

### When only the manifest is left

Spring Boot's own JARs ship without `pom.properties`, so a Spring Boot
application's dependencies are where the manifest path does most of its work.
Two rules govern it.

**groupId.** A manifest header such as `Bundle-SymbolicName`,
`Automatic-Module-Name` or `Implementation-Title` names the artifact in one of
two conventions, and the manifest alone cannot always tell which:
`<group>.<artifactId>` (the group is everything before the last segment) or the
group itself (`org.hamcrest` is the group of `hamcrest`). The artifactId from
the file name separates them, so the rules are applied in this order:

1. the value ends with `.<artifactId>` and at least two segments remain → those
   segments are the group (`com.example.foo` + `foo` → `com.example`);
2. otherwise, the last segment equals the artifactId, the artifactId without its
   hyphens, or the artifactId's first hyphen-separated token → the whole value is
   the group (`org.hamcrest` + `hamcrest` → `org.hamcrest`; `io.perfmark` +
   `perfmark-api` → `io.perfmark`; `org.reactivestreams` + `reactive-streams` →
   `org.reactivestreams`);
3. otherwise → the parent (`com.example.mybundle` → `com.example`).

Rule 1 must precede rule 2, or the second rule would claim `com.example.foo` is
the group of `com.example.foo` with artifactId `foo`, breaking a shape that
already worked.

`Implementation-Vendor-Id` outranks all of this. When no source yields a groupId
at all, the artifactId is used as the groupId, so that a purl exists rather than
none — which is why a JAR whose manifest has only an undotted
`Implementation-Title` becomes `pkg:maven/<artifactId>/<artifactId>@<version>`.

**Verified by:** `ManifestPurlDerivationSuite.A-1` through `A-9`.

**version.** `Implementation-Version` outranks `Bundle-Version`, which outranks
`Specification-Version`. A `Bundle-Version` is an OSGi version and may carry
qualifiers and padding the Maven release does not, so a purl built from it
matches no artifact in a repository: `3.2.0` for a `3.2` JAR,
`1.0.27.RELEASE` for `1.0.27`.

**Verified by:** `ManifestPurlDerivationSuite.B-1` through `B-4`.

### What the manifest cannot tell you, and why that is accepted

A `spring-*` JAR names neither its group nor a `<group>.<artifactId>` pair.
`spring-boot-2.7.14.jar` carries `Implementation-Title: Spring Boot`,
`Automatic-Module-Name: spring.boot` and `Implementation-Version: 2.7.14`, and
nothing in the file states `org.springframework.boot`. No rule that reads only
the artifact can recover it, so these JARs are emitted under the group the
module name implies — `spring`, or `spring.security` for
`spring-security-crypto`.

**An emitted pURL is a best effort.** Goat Rodeo emits the coordinates the
artifact's own evidence supports, and does not withhold a purl because the
evidence is incomplete. A purl whose groupId is inferred from the manifest is
worth more than none: it resolves for the many artifacts whose module name does
carry the group, and when it is wrong the wrongness is visible in the output
rather than hidden. Nothing here consults an external package index; the
artifact is the only evidence used.

**Verified by:** `ManifestPurlDerivationSuite.C-1`, which pins the emitted
groupId for the three Spring shapes as the accepted outcome rather than as a
defect awaiting repair.

## Secondary pURLs

Beyond the canonical pURL, Goat Rodeo emits **secondary pURLs** for each
embedded `pom.properties` entry that doesn't match the canonical. These
represent dependencies bundled inside uberjar/fat JARs.

- Secondary pURLs come **exclusively** from `pom.properties` — never from
  the filename.
- Secondary pURLs from sources JARs include `?packaging=sources`.
- Secondary pURLs from javadoc JARs include `?classifier=javadoc`.

**Verified by:** `SecondaryPurlClassifierSuite` — guard test for no
filename in secondary pURLs. `CanonicalSecondaryPurlPropertySuite` — secondary
pURLs never use the filename.

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

**Verified by:** `PurlMatchingSuite` — unit tests.
`PurlMatchingPropertySuite` — property tests.

## Sources and Javadoc JARs

Sources JARs (`-sources.jar`) and javadoc JARs (`-javadoc.jar`) are NOT
treated as main JARs. They are processed as companions to main JARs or
as standalone artifacts.

All pURLs from sources JARs include `?packaging=sources`.
All pURLs from javadoc JARs include `?classifier=javadoc`.

**Verified by:** `SourcesJavadocMetadataParitySuite` — corpus tests.
`SecondaryPurlClassifierPropertySuite` — shared-classifier property tests.

## Metadata Parity

Goat Rodeo's pURLs are a superset of the reference scanner's pURLs:
- Goat Rodeo finds every pURL the reference scanner finds (verified by `MultiplePurlSuite`)
- Goat Rodeo adds classifiers to sources/javadoc pURLs (the reference scanner doesn't)
- Goat Rodeo uses companion POMs for canonical pURL (the reference scanner doesn't)
- Goat Rodeo's canonical pURLs match real Maven Central artifacts

**Verified by:**
- `MultiplePurlSuite` — superset
- `RegularJarMetadataParitySuite` — Maven Central validation
- `BestPurlSuite` — field-level merge

## Test Summary

| Suite | What |
|-------|------|
| MavenCoordinateResolutionSuite | groupId/artifactId/version chain, filename parsing |
| ManifestPurlDerivationSuite | groupId and version rules when only the manifest is left |
| MavenPropertyTests | Property tests for interpolation, priority, parsing |
| CanonicalPurlPrioritySuite | Canonical pURL priority chain |
| PurlMatchingSuite | matchScore algorithm |
| PurlMatchingPropertySuite | Property tests for matching |
| SecondaryPurlClassifierSuite | Classifier on secondary pURLs |
| SecondaryPurlClassifierPropertySuite | Property tests for classifier |
| SourcesJavadocMetadataParitySuite | Corpus tests for sources/javadoc |
| RegularJarMetadataParitySuite | Maven Central validation |
| CanonicalSecondaryPurlPropertySuite | Cross-cutting property tests |
| MultiplePurlSuite | Metadata parity (superset) |
| BestPurlSuite | Field-level merge |
