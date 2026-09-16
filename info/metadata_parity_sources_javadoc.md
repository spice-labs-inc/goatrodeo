# Metadata Parity Guide: Sources and Javadoc JARs

## How to Run the Tests

```bash
sbt 'testOnly *SourcesJavadocMetadataParitySuite'
```

Expected runtime: ~2 minutes.

## How to Interpret Results

Each corpus test is named:

```
<jar-filename> — <description> (N entries)
```

- **`Discover sources JARs in test corpus`**: sources JARs discoverable in corpus (count > 100)
- **`Discover javadoc JARs in test corpus`**: javadoc JARs discoverable in corpus (count >= 1)
- **`<jar> — pURLs match pom.properties (N entries)`**: sources JAR pURLs match pom.properties (superset, all have `?packaging=sources`, count <= expected + 2)
- **`<jar> — pURLs match pom.properties (N entries)`**: javadoc JAR pURLs match pom.properties (all have `?classifier=javadoc`)
- **`<jar> — canonical pURL from companion POM`**: sources JAR with companion POM — canonical pURL present with `?packaging=sources`
- **`<jar> — canonical pURL in metadata`**: canonical pURL in metadata (`CanonicalPurl` key, starts with `pkg:maven/`, has `?packaging=sources`)
- **`<jar> — standalone sources JAR emits pURLs`**: standalone sources JAR (no companion POM/main JAR) — emits pURLs with `?packaging=sources`
- **`<jar> — pURL count >= pom.properties count (N entries)`**: pURL count >= pom.properties count (Goat Rodeo finds at least as many pURLs as pom.properties entries)

## Known Differences from the Reference Scanner

1. **Classifier on all pURLs**: Goat Rodeo adds `?packaging=sources` to
   sources JAR pURLs and `?classifier=javadoc` to javadoc JAR pURLs. The reference scanner
   does not add classifiers. Tests normalize by stripping qualifiers before
   comparison.

2. **Javadoc JAR with 0 pom.properties**: The one javadoc JAR in the corpus
   (`wps-demo-1.3.0-javadoc.jar`) has 0 `pom.properties` entries. Goat Rodeo
   still emits pURLs (from companion POM, manifest, or filename). The
   javadoc tests verify all emitted pURLs have `?classifier=javadoc` without
   requiring a minimum count.

3. **Sampling**: The per-JAR tests sample from the 3051 sources JARs in the
   corpus to keep test runtime ~2 minutes. Sampling is deterministic (sorted
   by path, every Nth file). Full corpus coverage is feasible but would
   take ~60 minutes.

## How Expected pURLs Are Derived

Expected pURLs are extracted at test time — NOT pre-computed:

1. Open the JAR as a `ZipFile`
2. Find all entries matching `META-INF/maven/.../pom.properties`
3. Parse each entry's content (key=value pairs, lowercased keys)
4. Extract `groupId`, `artifactId`, `version`
5. Build expected pURL: `pkg:maven/<g>/<a>@<v>?packaging=sources`
   (or `?classifier=javadoc` for javadoc JARs)

## Companion POM Handling

For sources JARs, the companion POM is `foo-1.0.pom` (NOT
`foo-1.0-sources.pom`). Maven does not publish separate POMs for
sources/javadoc JARs — they share the main artifact's POM.

The `companionPom` function strips `-sources.jar`, `-javadoc.jar`,
`-javadocs.jar`, `.jar`, `.war`, etc. before appending `.pom`.

## Claims and Test References

| Claim | Test |
|-------|------|
| Sources JARs exist in corpus (>100) | `Discover sources JARs in test corpus` |
| Javadoc JARs exist in corpus (>=1) | `Discover javadoc JARs in test corpus` |
| Sources JAR pURLs match pom.properties (superset) | `<jar> — pURLs match pom.properties (N entries)` (per JAR) |
| All sources JAR pURLs have `?packaging=sources` | pURLs-match tests and the standalone-sources test |
| Javadoc JAR pURLs match pom.properties | `<jar> — pURLs match pom.properties (N entries)` (per JAR) |
| All javadoc JAR pURLs have `?classifier=javadoc` | pURLs-match tests |
| Sources JAR with companion POM has canonical pURL | `<jar> — canonical pURL from companion POM` (per JAR) |
| Canonical pURL has `?packaging=sources` | companion-POM and metadata tests |
| Canonical pURL in metadata (`CanonicalPurl` key) | `<jar> — canonical pURL in metadata` (per JAR) |
| Standalone sources JAR emits pURLs | `<jar> — standalone sources JAR emits pURLs` (per JAR) |
| pURL count >= pom.properties count | `<jar> — pURL count >= pom.properties count (N entries)` (per JAR) |
| pURL count not inflated (<= expected + 2) | pURLs-match tests (per JAR) |