# OpenSSL Configuration Capture Strategy

> **Navigation:** [Documentation Index](README.md) | [OpenSSL MIME Detection](openssl_mime_detection.md)

## Overview

The `OpenSSLConfig` strategy captures OpenSSL configuration files inside Goat Rodeo's strategy pipeline. It is triggered by the `application/x-openssl-config` MIME type added by [`OpenSSLConfigDetector`](openssl_mime_detection.md).

At each archive layer, the strategy:

1. Claims all artifacts whose MIME type is `application/x-openssl-config`.
2. Parses each file for security-relevant directives.
3. Resolves `.include` references within the same layer.
4. Orders files so referenced files are processed before the files that reference them.
5. Emits metadata under `openssl.cnf:*` keys and records cross-file references with both the referenced-file GitOID and the container GitOID.

## Why a dedicated strategy?

OpenSSL configs declare TLS defaults such as cipher suites and minimum protocol versions. Capturing these settings as metadata makes them available to downstream CBOM generation and security auditing.

## Claiming

The strategy claims files by MIME type. It never scans every file at a layer; it relies on the pre-computed MIME sets from `ArtifactWrapper`. Files that do not carry `application/x-openssl-config` fall through to other strategies (for example, PEM certificates go to the `Certificates` strategy, generic text goes to `GenericFile`).

## Bundling

All OpenSSL config files discovered at the same archive layer are bundled into a single `OpenSSLConfigToProcess`. This is required because:

- Files can reference each other via `.include`.
- Cross-file reference metadata needs the GitOID of the referenced file.
- Dependency ordering is only possible when all files are processed together.

## Dependency ordering

Files are topologically sorted so that a referenced file is always processed before the file that references it. The sort is deterministic:

- Starting order is alphabetical by path.
- If file A references file B, file B is processed first.
- Cycles are broken by skipping the reference edge that would revisit a file already on the depth-first stack.

## Parsing

The parser (`OpenSSLConfigParser`) reads at most 1 MB of each file. It extracts:

- Section names that contain security-relevant keys or participate in `ssl_conf` indirection.
- `CipherString` and `Ciphersuites` values.
- `MinProtocol` and `MaxProtocol` values.
- `Options` values (split on commas).
- `.include` references.
- `ssl_conf` section references.

The parser follows `ssl_conf` indirection chains within the same file up to a depth of 8. For example:

```ini
[openssl_init]
ssl_conf = ssl_sect

[ssl_sect]
system_default = system_default_sect

[system_default_sect]
CipherString = DEFAULT
MinProtocol = TLSv1.2
```

The effective `CipherString` and `MinProtocol` values are captured.

## Metadata keys

Extracted values are stored in `ItemMetaData.extra` under the following keys:

| Key | Content |
|-----|---------|
| `openssl.cnf:sections` | Names of active sections |
| `openssl.cnf:cipher_string` | `CipherString` value |
| `openssl.cnf:cipher_suites` | `Ciphersuites` value |
| `openssl.cnf:min_protocol` | `MinProtocol` value |
| `openssl.cnf:max_protocol` | `MaxProtocol` value |
| `openssl.cnf:options` | Discrete `Options` values |
| `openssl.cnf:associated_files` | Cross-file references encoded as `containerGitOID:referencedFileGitOID` |

## Cross-file references

When a config file references another OpenSSL file in the same layer via `.include`, the referencing Item's metadata includes `openssl.cnf:associated_files` values. Each value encodes both the container GitOID and the referenced-file GitOID, disambiguating the association because the same config file can appear in multiple images with different companions.

## Error handling

The parser never throws. Malformed files, binary data, and invalid UTF-8 result in empty parsed data, but the file is still claimed so it does not fall through to `GenericFile` with misleading metadata.

## Security boundaries

- Reads are bounded to 1 MB per file.
- `.include` resolution is limited to the current archive layer.
- Section-indirection recursion is limited to depth 8.
- Cycle detection prevents infinite loops on cyclic `.include` references.
- Variable substitution, conditional sections, engine blocks, and references outside the current layer are not supported.

## Verification

- `OpenSSLConfigParserSuite` verifies parsing, inheritance, reference recording, and read-budget compliance.
- `OpenSSLConfigSuite` verifies MIME-based claiming, bundling, dependency ordering, metadata emission, cross-file references, cycle handling, and strategy coexistence.

## Related

- Implementation: `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/OpenSSLConfig.scala`
- Parser: `src/main/scala/io/spicelabs/goatrodeo/util/OpenSSLConfigParser.scala`
- Registration: `src/main/scala/io/spicelabs/goatrodeo/omnibor/ToProcess.scala`
- ADR: `docs/adr/0002-openssl-config-strategy.md`
