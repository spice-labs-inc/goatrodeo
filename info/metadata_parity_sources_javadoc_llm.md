# Metadata Parity Guide: Sources and Javadoc JARs (LLM)

## Summary

53 corpus-based tests verifying Goat Rodeo finds all pURLs from sources
and javadoc JARs. All pass. No production code changes (fixes were in
Phases 2-4).

## Run

```bash
sbt 'testOnly *Phase5MetadataParitySourcesJavadocSuite'
```

## Key Design

- **HS-4 compliant**: Opens real JAR files at test time, reads pom.properties
- **Sampling**: 50 sources JARs from 3051 total (deterministic, every Nth)
- **Companion POM**: Sources JAR POM is `foo-1.0.pom` (shared with main JAR)
- **Classifier**: ALL pURLs have `?packaging=sources` or `?classifier=javadoc`
- **Known difference from the reference scanner**: Goat Rodeo adds classifiers (the reference scanner doesn't)

## Tests

| Test | What | Count |
|------|------|-------|
| 5.1 | Sources JARs discoverable | 1 |
| 5.2 | Javadoc JARs discoverable | 1 |
| 5.3 | Sources JAR pURLs >= pom.properties | 16 |
| 5.4 | Javadoc JAR pURLs match | 1 |
| 5.5 | Canonical pURL from companion POM | 11 |
| 5.6 | Canonical pURL in metadata | 5 |
| 5.7 | Standalone sources JAR emits pURLs | 10 |
| 5.8 | pURL count >= pom.properties count | 16 |

## No Production Code Changes

Phase 5 is test-only. All fixes were in Phases 2-4.
