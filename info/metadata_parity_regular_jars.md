# Metadata Parity Guide: Regular JARs and Maven Central Validation

## How to Run the Tests

```bash
sbt 'testOnly *RegularJarMetadataParitySuite'
sbt 'testOnly *MultiplePurlSuite'
sbt 'testOnly *BestPurlSuite'
```

Expected runtime: ~30 seconds for RegularJarMetadataParitySuite,
~2 minutes for MultiplePurlSuite.

## How to Interpret Results

### RegularJarMetadataParitySuite

- **Canonical groupId from POM, not filename** ("better than the reference
  scanner"): each per-JAR test (`<jar> — canonical pURL groupId from POM
  (not filename)`) verifies the canonical pURL contains the companion
  POM's groupId and does NOT use the filename as groupId.

- **Maven Central validation**: each per-JAR test (`<jar> — canonical pURL
  matches Maven Central`) checks the canonical pURL exactly matches the
  hardcoded Maven Central coordinate. Coordinates were manually verified
  to exist at `https://repo1.maven.org/maven2/` on 2026-07-08.

- **pURL count >= reference scanner count**: explicit count check using
  MultiplePurlSuite data (sampled).

- **Companion POM priority in full pipeline**: each per-JAR test (`<jar> —
  canonical pURL from companion POM in pipeline`) parses the companion POM
  at test time using production `PomParser`, processes the JAR through the
  full pipeline, asserts canonical pURL matches POM values (not
  pom.properties or manifest).

### MultiplePurlSuite

Tests that Goat Rodeo finds every pURL the reference scanner found (superset) for regular
JARs/WARs. Uses `alias:from` connections on the JAR's Item, with
companion POM and fat JAR filtering.

### BestPurlSuite

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

Coordinates were manually verified to exist in Maven Central on
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
| MultiplePurlSuite passes (superset) | MultiplePurlSuite |
| BestPurlSuite passes (field-level merge) | BestPurlSuite |
| Canonical pURL groupId from POM (not filename) | `<jar> — canonical pURL groupId from POM (not filename)` (per JAR) |
| Canonical pURL matches Maven Central | `<jar> — canonical pURL matches Maven Central` (per JAR) |
| pURL count >= reference scanner count | count-check tests over the MultiplePurlSuite sample |
| Companion POM priority in full pipeline | `<jar> — canonical pURL from companion POM in pipeline` (per JAR) |
| Superset for all reference scanner JARs | MultiplePurlSuite |