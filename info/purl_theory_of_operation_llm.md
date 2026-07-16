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

## ADRs

| ADR | Phase | Topic |
|-----|-------|-------|
| 010 | 0 | GAV acronym elimination (rename) |
| 011 | 1 | Directory-based test infrastructure |
| 012 | 2 | Canonical pURL priority (companion POM highest) |
| 013 | 4 | Secondary pURL classifier fix |
| 014 | 3 | Matching improvement (matchScore) |
| 015 | 5 | Metadata parity for sources/javadoc |
| 016 | 6 | Metadata parity for regular JARs + Maven Central |

## Test Count

352 pURL-related tests across 12 test suites. All pass.
Full suite: 1977 tests, 0 failures.

## No Unimplemented Code

All features implemented. No `todo!()` or `throw new RuntimeException("to be implemented")`.
