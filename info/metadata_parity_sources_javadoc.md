# Metadata Parity Guide: Sources and Javadoc JARs

## How to Run the Tests

```bash
sbt 'testOnly *Phase5MetadataParitySourcesJavadocSuite'
```

Expected runtime: ~2 minutes.

## How to Interpret Results

All 53 tests should pass. Each test is named:

```
Test 5.X: <jar-filename> — <description> (N entries)
```

- **Test 5.1**: Sources JARs discoverable in corpus (count > 100)
- **Test 5.2**: Javadoc JARs discoverable in corpus (count >= 1)
- **Test 5.3**: Sources JAR pURLs match pom.properties (superset, all have `?packaging=sources`, count <= expected + 2)
- **Test 5.4**: Javadoc JAR pURLs match pom.properties (all have `?classifier=javadoc`)
- **Test 5.5**: Sources JAR with companion POM — canonical pURL present with `?packaging=sources`
- **Test 5.6**: Canonical pURL in metadata (`CanonicalPurl` key, starts with `pkg:maven/`, has `?packaging=sources`)
- **Test 5.7**: Standalone sources JAR (no companion POM/main JAR) — emits pURLs with `?packaging=sources`
- **Test 5.8**: pURL count >= pom.properties count (Goat Rodeo finds at least as many pURLs as pom.properties entries)

## Known Differences from the Reference Scanner

1. **Classifier on all pURLs**: Goat Rodeo adds `?packaging=sources` to
   sources JAR pURLs and `?classifier=javadoc` to javadoc JAR pURLs. The reference scanner
   does not add classifiers. Tests normalize by stripping qualifiers before
   comparison.

2. **Javadoc JAR with 0 pom.properties**: The one javadoc JAR in the corpus
   (`wps-demo-1.3.0-javadoc.jar`) has 0 `pom.properties` entries. Goat Rodeo
   still emits pURLs (from companion POM, manifest, or filename). Test 5.4
   verifies all emitted pURLs have `?classifier=javadoc` without requiring a
   minimum count.

3. **Sampling**: Tests 5.3-5.8 sample from the 3051 sources JARs in the
   corpus to keep test runtime ~2 minutes. Sampling is deterministic (sorted
   by path, every Nth file). Full corpus coverage is feasible but would
   take ~60 minutes.

## How Expected pURLs Are Derived (HS-4 Compliance)

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
| Sources JARs exist in corpus (>100) | Test 5.1 |
| Javadoc JARs exist in corpus (>=1) | Test 5.2 |
| Sources JAR pURLs match pom.properties (superset) | Test 5.3 (per JAR) |
| All sources JAR pURLs have `?packaging=sources` | Test 5.3, Test 5.7 |
| Javadoc JAR pURLs match pom.properties | Test 5.4 |
| All javadoc JAR pURLs have `?classifier=javadoc` | Test 5.4 |
| Sources JAR with companion POM has canonical pURL | Test 5.5 (per JAR) |
| Canonical pURL has `?packaging=sources` | Test 5.5, Test 5.6 |
| Canonical pURL in metadata (`CanonicalPurl` key) | Test 5.6 (per JAR) |
| Standalone sources JAR emits pURLs | Test 5.7 (per JAR) |
| pURL count >= pom.properties count | Test 5.8 (per JAR) |
| pURL count not inflated (<= expected + 2) | Test 5.3 (per JAR) |
