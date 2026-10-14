# pURL Resolution: Theory of Operation (LLM)

## Summary

Goat Rodeo resolves Maven pURLs with a 5-level priority chain:
companion POM > pom.properties > embedded pom.xml > manifest > filename.
Each field (groupId, artifactId, version) is resolved independently.

## Key Design

- **Canonical pURL**: one per artifact, from highest-priority source
- **Secondary pURLs**: from pom.properties only, never filename
- **Classifiers**: `?packaging=sources` / `?classifier=javadoc` on ALL pURLs
- **Matching**: exact (3) > prefix-with-separator (2) > reverse-prefix (1) > none (0)
- **Metadata parity**: superset (Goat Rodeo finds all reference scanner pURLs + more)

## Manifest-only derivation (`Maven.scala`)

Reached when the JAR has no companion POM, no `pom.properties` and no embedded
`pom.xml`. Spring Boot's own JARs ship without `pom.properties`, so this path
carries most of a Spring Boot application's dependency set.

`resolveGroupIdArtifactIdVersionFromManifest(manifest, fileArtifactId)` — the
artifactId comes from the **file name** (`extractIdentityFromFilename`) and is
computed *before* the manifest call in `resolveGroupIdArtifactIdVersion`, because
the groupId rules need it.

**groupId, three rules, in this order (`groupIdFromName`):**

| # | Condition | Result | Example |
|---|---|---|---|
| 1 | value ends `.<artifactId>` and ≥2 segments remain | the remaining segments | `com.example.foo` + `foo` → `com.example`; `io.projectreactor.netty.reactor-netty-core` + `reactor-netty-core` → `io.projectreactor.netty` |
| 2 | last segment == artifactId, == artifactId minus `-`, or == artifactId's first `-` token | the whole value | `org.hamcrest`+`hamcrest`; `org.reactivestreams`+`reactive-streams`; `io.perfmark`+`perfmark-api` |
| 3 | otherwise | the parent (historical) | `com.example.mybundle` → `com.example` |

Rule order is load bearing: rule 2 alone would make `com.example.foo` + `foo`
yield `com.example.foo`.

Header priority: `Implementation-Vendor-Id` > `Bundle-SymbolicName` >
`Automatic-Module-Name` > `Extension-Name` > `Implementation-Title` > `Package`.
A value with no dots yields nothing. If no source yields a groupId, the
artifactId is used as the groupId (`Maven.scala:384`), which is why an undotted
`Implementation-Title` gives `pkg:maven/<artifactId>/<artifactId>@<version>`.

**version:** `Implementation-Version` > `Bundle-Version` > `Specification-Version`.
`Bundle-Version` is an OSGi version and may carry a qualifier or padding the
Maven release does not (`3.2.0` for `3.2`, `1.0.27.RELEASE` for `1.0.27`), and a
purl built from it matches nothing in a repository.

**Known limit, accepted:** `spring-*` JARs. `spring.boot` / `spring.core` name
neither the group nor a `<group>.<artifactId>` pair, and the JAR never states
`org.springframework.boot` / `org.springframework`, so these emit group `spring`
(or `spring.security` for `spring-security-crypto`). Pinned by
`ManifestPurlDerivationSuite.C-1`.

**Policy: an emitted pURL is a best effort.** Emit the coordinates the artifact's
own evidence supports; never withhold a purl because the evidence is incomplete,
and never consult an external package index. A manifest-inferred groupId resolves
for the many artifacts whose module name carries the group, and where it is wrong
the wrongness is visible in the output.

Tests: `ManifestPurlDerivationSuite` A-1…A-9 (groupId), B-1…B-4 (version),
AB-1 (the six non-Spring rows of issue #337), C-1 (the Spring best effort).

## Test counts

Mechanical outcomes (test counts, run totals) are recorded in the
`execution_state` directory, not here, per the project documentation rules. The
suites that carry the claims above are named beside each claim, and
`ManifestPurlDerivationSuite` is the one added for the manifest-only rules.

## No Unimplemented Code

All features implemented. No `todo!()` or `throw new RuntimeException("to be implemented")`.
