# Java `java.security` Capture Strategy

> **Navigation:** [Documentation Index](README.md) | [Architecture](architecture.md)

## Overview

The `JavaSecurity` strategy captures Java `java.security` policy files inside Goat Rodeo's strategy pipeline. It is triggered by:

- The `application/x-java-security-properties` MIME type added by `JavaSecurityDetector` for included/sibling security properties files.
- The path of files named `java.security` inside a JDK/JRE security directory (`lib/security`, `conf/security`, or `jre/lib/security`).

At each archive layer, the strategy:

1. Claims files by MIME type or by known security-directory path.
2. Parses each file with `java.util.Properties` for the five security-relevant properties.
3. Resolves `include` references within the same layer.
4. Merges effective security data across the dependency graph (dependencies first, then the dependent file).
5. Orders files so referenced files are processed before the files that reference them.
6. Emits metadata under `java.security:*` keys and records cross-file references with both the referenced-file GitOID and the container GitOID.

## Why a dedicated strategy?

Java runtime security policy files declare which algorithms and protocols are disabled, which are legacy, and which named groups are allowed. Capturing these settings as metadata makes them available to downstream CBOM generation and security auditing.

## Claiming

The strategy claims files in two ways:

- **MIME type:** files that `JavaSecurityDetector` has flagged as `application/x-java-security-properties`. This captures included/sibling security properties files that may not be named `java.security`.
- **Path:** files named `java.security` whose path ends with one of the known security-directory layouts:
  - `/conf/security/java.security`
  - `/lib/security/java.security`
  - `/jre/lib/security/java.security`

Selection is content-agnostic: `computeJavaSecurityFiles` does not read file contents. It uses only the precomputed MIME type and the logical path. Files that are not claimed fall through to other strategies (for example, generic text goes to `GenericFile`).

## Bundling

All Java security files discovered at the same archive layer are bundled into a single `JavaSecurityToProcess`. This is required because:

- Files can reference each other via `include` directives.
- Cross-file reference metadata needs the GitOID of the referenced file.
- Dependency ordering is only possible when all files are processed together.

## Dependency ordering

Files are topologically sorted so that a referenced file is always processed before the file that references it. The sort is deterministic:

- Starting order is alphabetical by path.
- If file A references file B, file B is processed first.
- Cycles are broken by skipping the reference edge that would revisit a file already on the depth-first stack.

## Parsing

The parser (`JavaSecurityParser`) reads at most 1 MB of each file as ISO-8859-1 and uses `java.util.Properties` to parse the content. It extracts:

- `jdk.tls.disabledAlgorithms`
- `jdk.certpath.disabledAlgorithms`
- `jdk.tls.legacyAlgorithms`
- `jdk.tls.namedGroups`
- `jdk.tls.ephemeralDHKeySize`

Comma-separated list values are split, trimmed, and stored as immutable sets with empty tokens removed. Internal spaces are preserved (e.g., `RSA keySize < 2048` stays as one token).

## Include resolution

Java `include` directives (for example, `include extra.security`) are resolved within the selected set of files in the same archive layer. Because `java.util.Properties.load` does not resolve `include` directives when reading from a `StringReader` or `InputStream`, the strategy performs the resolution itself.

Effective security data is computed by merging each file's parsed data with the data of its dependencies:

- List-valued properties (`disabled_algorithms`, `certpath_disabled_algorithms`, `legacy_algorithms`, `named_groups`) are unioned.
- Scalar values (`ephemeral_dh_key_size`) are taken from the dependent file if present.

This preserves the semantics that a base policy plus an included override together describe the runtime security posture.

## Metadata keys

Extracted values are stored in `ItemMetaData.extra` under the following keys:

| Key | Content |
|-----|---------|
| `java.security:disabled_algorithms` | Values from `jdk.tls.disabledAlgorithms` |
| `java.security:certpath_disabled_algorithms` | Values from `jdk.certpath.disabledAlgorithms` |
| `java.security:legacy_algorithms` | Values from `jdk.tls.legacyAlgorithms` |
| `java.security:named_groups` | Values from `jdk.tls.namedGroups` |
| `java.security:ephemeral_dh_key_size` | Value from `jdk.tls.ephemeralDHKeySize` |
| `java.security:associated_files` | Cross-file references encoded as `containerGitOID:referencedFileGitOID` |

## Cross-file references

When a `java.security` file references another security properties file in the same layer via `include`, the referencing Item's metadata includes `java.security:associated_files` values. Each value encodes both the container GitOID and the referenced-file GitOID, disambiguating the association because the same security file can appear in multiple images with different companions.

## Error handling

The parser never throws. Malformed files, unreadable files, and binary data result in empty parsed data or `Failure`, but the file is still claimed so it does not fall through to `GenericFile` with misleading metadata.

## Security boundaries

- Reads are bounded to 1 MB per file; larger files are truncated and a warning is logged.
- `include` resolution is limited to the current archive layer.
- Include recursion is limited to depth 8.
- Cycle detection prevents infinite loops on cyclic `include` references.
- Variable/property substitution and references outside the current layer are not supported.

## Verification

- `JavaSecurityParserSuite` verifies parsing, tokenization, line continuations, escapes, whitespace handling, IOException handling, and read-budget compliance.
- `JavaSecurityDetectorSuite` verifies MIME detection, additive behavior, and the 4 KB detection budget.
- `JavaSecuritySuite` verifies MIME-based and path-based claiming, bundling, metadata emission, include resolution, nested-archive discovery, and strategy coexistence.

## Related

- Implementation: `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/JavaSecurity.scala`
- Parser: `src/main/scala/io/spicelabs/goatrodeo/util/JavaSecurityParser.scala`
- Detector: `src/main/scala/io/spicelabs/goatrodeo/util/JavaSecurityDetector.scala`
- Registration: `src/main/scala/io/spicelabs/goatrodeo/omnibor/ToProcess.scala`
- ADR: `docs/adr/0004-java-security-strategy.md`
- Architecture boundary: `info/architecture.md` §Strategy Selection vs. Processing Boundary
