# Java `java.security` Capture Strategy

> **Navigation:** [Documentation Index](README.md) | [Architecture](architecture_llm.md)

## Purpose

Capture Java `java.security` policy semantics (disabled algorithms, legacy algorithms, named groups, DH key size, cross-file references) as item metadata in the Goat Rodeo ADG.

## Triggers

- `application/x-java-security-properties` MIME type from `JavaSecurityDetector`.
- Path ends with `/conf/security/java.security`, `/lib/security/java.security`, or `/jre/lib/security/java.security`.

## Components

- **Strategy:** `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/JavaSecurity.scala`
- **Parser:** `src/main/scala/io/spicelabs/goatrodeo/util/JavaSecurityParser.scala`
- **Detector:** `src/main/scala/io/spicelabs/goatrodeo/util/JavaSecurityDetector.scala`
- **Registration:** `src/main/scala/io/spicelabs/goatrodeo/omnibor/ToProcess.scala`

## Pipeline

1. Claim files by MIME type or security-directory path. Selection does not read content.
2. During strategy processing, read each file (≤ 1 MB).
3. Parse with `java.util.Properties`.
4. Extract `include` directives and build dependency graph.
5. Topologically sort; break cycles deterministically.
6. Merge effective security data across dependencies (union for lists, overlay for scalars).
7. Process files in sorted order.
8. Emit metadata and cross-file reference GitOIDs.

## Parsed data

- `disabledAlgorithms`: `jdk.tls.disabledAlgorithms`
- `certpathDisabledAlgorithms`: `jdk.certpath.disabledAlgorithms`
- `legacyAlgorithms`: `jdk.tls.legacyAlgorithms`
- `namedGroups`: `jdk.tls.namedGroups`
- `ephemeralDHKeySize`: `jdk.tls.ephemeralDHKeySize`

## Metadata keys

Uses `MetadataKeyConstants.adHoc("java.security")(key)`:

- `java.security:disabled_algorithms`
- `java.security:certpath_disabled_algorithms`
- `java.security:legacy_algorithms`
- `java.security:named_groups`
- `java.security:ephemeral_dh_key_size`
- `java.security:associated_files`

## Cross-file references

Encoded as `containerGitOID:referencedFileGitOID` strings in `java.security:associated_files`.

## Security bounds

- 1 MB read budget per file; truncate and warn on oversized files.
- `include` only within current layer.
- Max include recursion depth 8.
- Cycle detection on include graph.

## Unsupported

- Variable/property substitution.
- Cross-layer `include` resolution.
- Java security constructs beyond the five tracked properties.

## Important note on `java.util.Properties` and `include`

`java.util.Properties.load` does not resolve `include` directives when reading from a `StringReader` or `InputStream`. The strategy therefore resolves `include` directives manually within the selected file set and merges parsed data across the dependency graph. This is a deliberate deviation from the original plan's assumption that `Properties.load` would handle includes; it is required for correctness and to preserve the no-host-filesystem-access boundary.

## Tests

- `JavaSecurityParserSuite`
- `JavaSecurityDetectorSuite`
- `JavaSecuritySuite`

## Related

- ADR: `docs/adr/0004-java-security-strategy.md`
- MIME detection: `info/java_security_capture.md`
- Architecture boundary: `info/architecture_llm.md` §Strategy Selection vs. Processing Boundary
