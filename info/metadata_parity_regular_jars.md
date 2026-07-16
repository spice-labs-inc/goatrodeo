# Metadata Parity Guide: Regular JARs and Maven Central Validation

## How to Run the Tests

```bash
# Phase 6 suite (47 tests)
sbt 'testOnly *Phase6MetadataParityRegularBestPurlSuite'

# MultiplePurlSuite (113 tests, metadata superset)
sbt 'testOnly *MultiplePurlSuite'

# BestPurlSuite (2 tests, field-level merge)
sbt 'testOnly *BestPurlSuite'
```

Expected runtime: ~30 seconds for Phase 6, ~2 minutes for MultiplePurlSuite.

## How to Interpret Results

### Phase 6 Tests (47 tests)

- **Test 6.3** (12 tests): "Better than the reference scanner" — canonical pURL groupId
  from companion POM, not the filename. Each test verifies the canonical
  pURL contains the POM's groupId and does NOT use the filename as groupId.

- **Test 6.4** (12 tests): Maven Central validation — canonical pURL
  exactly matches hardcoded Maven Central coordinates. Coordinates were
  manually verified to exist at `https://repo1.maven.org/maven2/` on
  2026-07-08.

- **Test 6.5** (10 tests): pURL count >= reference scanner pURL count. Explicit count
  check using MultiplePurlSuite data (sampled).

- **Test 6.6** (12 tests): Companion POM priority in full pipeline.
  Parses the companion POM at test time using production `PomParser`,
  processes the JAR through the full pipeline, asserts canonical pURL
  matches POM values (not pom.properties or manifest).

### MultiplePurlSuite (113 tests)

Tests that Goat Rodeo finds every pURL the reference scanner found (superset) for regular
JARs/WARs. Uses `alias:from` connections on the JAR's Item, with
companion POM and fat JAR filtering.

### BestPurlSuite (2 tests)

Tests that field-level merge produces Maven Central pURLs for JARs
where pom.properties is absent. Calls `resolveGroupIdArtifactIdVersion`
directly with `externalPom=None`.

## Known Differences from the Reference Scanner

1. **Classifier on sources/javadoc pURLs**: Goat Rodeo adds
   `?packaging=sources` and `?classifier=javadoc`. The reference scanner does not.
   MultiplePurlSuite normalizes by stripping qualifiers before comparison.

2. **Companion POM for canonical pURL**: Goat Rodeo uses the companion
   POM as the highest priority source for canonical pURL groupId/
   artifactId/version. The reference scanner does not use companion POMs — it relies on
   pom.properties inside the JAR. This makes Goat Rodeo's canonical pURL
   more accurate than the reference scanner's for JARs where pom.properties is absent or
   different from the POM.

3. **Field-level merge**: Goat Rodeo resolves each field (groupId,
   artifactId, version) independently from the highest-priority source.
   The reference scanner resolves all fields from the same source. This means Goat Rodeo
   can produce a pURL with groupId from manifest, artifactId from
   filename, and version from pom.properties — which is more likely to
   match a real Maven Central artifact.

## Maven Central Validation

12 coordinates were manually verified to exist in Maven Central on
2026-07-08:

| groupId | artifactId | version | Maven Central URL |
|---------|-----------|---------|-------------------|
| uk.co.firstzero | AddOnJavaAntTasks | 2.11 | `maven2/uk/co/firstzero/AddOnJavaAntTasks/2.11/` |
| tech.ugma.customcomponents | AddRemoveComboBox | 0.5 | `maven2/tech/ugma/customcomponents/AddRemoveComboBox/0.5/` |
| uk.co.real-logic | Agrona | 0.1 | `maven2/uk/co/real-logic/Agrona/0.1/` |
| xyz.lamergameryt | Allen4J | 1.0.1 | `maven2/xyz/lamergameryt/Allen4J/1.0.1/` |
| uk.ac.mmu.tdmlab.uima | AnnotationSummariser | 1.2.0 | `maven2/uk/ac/mmu/tdmlab/uima/AnnotationSummariser/1.2.0/` |
| ws.argo.wireline | ArgoWirelineFormat | 0.3.1 | `maven2/ws/argo/wireline/ArgoWirelineFormat/0.3.1/` |
| za.co.absa | abris | 0.0.1 | `maven2/za/co/absa/abris/0.0.1/` |
| za.co.absa.shaded | absa-shaded-jackson | 0.0.1 | `maven2/za/co/absa/shaded/absa-shaded-jackson/0.0.1/` |
| tech.rsqn.useful-things | abstraction-models | 1.0.48 | `maven2/tech/rsqn/useful-things/abstraction-models/1.0.48/` |
| tech.figure.classification.asset | ac-client | 2.0.0 | `maven2/tech/figure/classification/asset/ac-client/2.0.0/` |
| tz.co.asoft | access-system | 0.0.14 | `maven2/tz/co/asoft/access-system/0.0.14/` |
| uk.ac.cam.ch.wwmm | acpgeo | 0.0.2 | `maven2/uk/ac/cam/ch/wwmm/acpgeo/0.0.2/` |

## Claims and Test References

| Claim | Test |
|-------|------|
| MultiplePurlSuite passes (superset) | MultiplePurlSuite (113 tests) |
| BestPurlSuite passes (field-level merge) | BestPurlSuite (2 tests) |
| Canonical pURL groupId from POM (not filename) | Test 6.3 (12 JARs) |
| Canonical pURL matches Maven Central | Test 6.4 (12 JARs) |
| pURL count >= reference scanner count | Test 6.5 (10 JARs) |
| Companion POM priority in full pipeline | Test 6.6 (12 JARs) |
| Superset for all 164 reference scanner JARs | MultiplePurlSuite (113 tests) |
