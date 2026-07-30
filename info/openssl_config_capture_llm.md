# OpenSSL Configuration Capture Strategy

> **Navigation:** [Documentation Index](README.md) | [OpenSSL MIME Detection](openssl_mime_detection_llm.md)

## Purpose

Capture OpenSSL configuration semantics (cipher suites, protocol versions, options, cross-file references) as item metadata in the Goat Rodeo ADG.

## Trigger

`application/x-openssl-config` MIME type from `OpenSSLConfigDetector`.

## Components

- **Strategy:** `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/OpenSSLConfig.scala`
- **Parser:** `src/main/scala/io/spicelabs/goatrodeo/util/OpenSSLConfigParser.scala`
- **Registration:** `src/main/scala/io/spicelabs/goatrodeo/omnibor/ToProcess.scala`

## Pipeline

1. Claim files with MIME `application/x-openssl-config`.
2. Parse each file (≤ 1 MB).
3. Resolve `.include` references within the layer.
4. Topologically sort; break cycles deterministically.
5. Process files in sorted order.
6. Emit metadata and cross-file reference GitOIDs.

## Parsed data

- `sections`: active section names
- `cipher_string`, `cipher_suites`, `min_protocol`, `max_protocol`
- `options`: comma-split values
- `includeReferences`: raw `.include` paths
- `sslConfReferences`: section names from `ssl_conf`

## Metadata keys

Uses `MetadataKeyConstants.adHoc("openssl.cnf")(key)`:

- `openssl.cnf:sections`
- `openssl.cnf:cipher_string`
- `openssl.cnf:cipher_suites`
- `openssl.cnf:min_protocol`
- `openssl.cnf:max_protocol`
- `openssl.cnf:options`
- `openssl.cnf:associated_files`

## Cross-file references

Encoded as `containerGitOID:referencedFileGitOID` strings in `openssl.cnf:associated_files`.

## Security bounds

- 1 MB read budget per file.
- `.include` only within current layer.
- Max section-indirection depth 8.
- Cycle detection on `.include` graph.

## Unsupported

- Variable substitution, conditional sections, engine blocks.
- Cross-layer `.include` resolution.

## Tests

- `OpenSSLConfigParserSuite`
- `OpenSSLConfigSuite`

## Related

- ADR: `docs/adr/0002-openssl-config-strategy.md`
- MIME detection: `info/openssl_mime_detection_llm.md`
