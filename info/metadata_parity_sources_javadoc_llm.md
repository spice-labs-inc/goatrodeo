# Metadata Parity Guide: Sources and Javadoc JARs (LLM)

## Summary

Corpus-based tests verifying Goat Rodeo finds all pURLs from sources
and javadoc JARs. All pass.

## Run

```bash
sbt 'testOnly *SourcesJavadocMetadataParitySuite'
```

## Key Design

- Opens real JAR files at test time, reads pom.properties
- **Sampling**: 50 sources JARs from 3051 total (deterministic, every Nth)
- **Companion POM**: Sources JAR POM is `foo-1.0.pom` (shared with main JAR)
- **Classifier**: ALL pURLs have `?packaging=sources` or `?classifier=javadoc`
- **Known difference from the reference scanner**: Goat Rodeo adds classifiers (the reference scanner doesn't)

## Tests

Per-JAR tests are named `<jar-filename> — <description> (N entries)`; the
group-level discovery tests are `Discover sources JARs in test corpus` and
`Discover javadoc JARs in test corpus`.

| Test | What |
|------|------|
| `Discover sources JARs in test corpus` | Sources JARs discoverable |
| `Discover javadoc JARs in test corpus` | Javadoc JARs discoverable |
| `<jar> — pURLs match pom.properties (N entries)` | Sources JAR pURLs >= pom.properties, superset |
| `<jar> — pURLs match pom.properties (N entries)` | Javadoc JAR pURLs match |
| `<jar> — canonical pURL from companion POM` | Canonical pURL from companion POM |
| `<jar> — canonical pURL in metadata` | Canonical pURL in metadata |
| `<jar> — standalone sources JAR emits pURLs` | Standalone sources JAR emits pURLs |
| `<jar> — pURL count >= pom.properties count (N entries)` | pURL count >= pom.properties count |

## Test-Only

These suites are test-only; they verify the metadata parity behavior,
they do not change it.