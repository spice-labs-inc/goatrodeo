# CycloneDX CBOM Emitter — LLM Reference

## Decision

Add an optional post-processing stage that emits one CycloneDX cryptographic bill-of-materials (CBOM) JSON file per top-level ADG root.

## Problem

Goat Rodeo captures cryptographic material (certificates, keys, OpenSSL configs, Java security policies) as ADG metadata, but there was no structured output format for downstream crypto-inventory and risk tools.

## Key files

- `src/main/scala/io/spicelabs/goatrodeo/omnibor/CbomEmitter.scala` — emitter implementation.
- `src/main/scala/io/spicelabs/goatrodeo/util/Config.scala` — `--emit-cbom-dir` and `--cbom-version` flags.
- `src/main/scala/io/spicelabs/goatrodeo/omnibor/Builder.scala` — invokes the emitter after the main processing loop.
- `src/test/scala/io/spicelabs/goatrodeo/omnibor/CbomEmitterSuite.scala` — test suite.
- `src/test/resources/cyclonedx/bom-1.6.schema.json` and `bom-1.7.schema.json` — validation schemas.

## CLI flags

- `--emit-cbom-dir <dir>` — output directory. Disabled when omitted.
- `--cbom-version <1.6|1.7>` — CycloneDX version. Default `1.6`.

Only `1.6` and `1.7` are accepted; everything else is a parse error.

## Pipeline

1. After the ADG is built, `Builder` calls `CbomEmitter.emitForStorage(storage, version, dir)` if `--emit-cbom-dir` is set.
2. Find all root Items (`Item.isRoot()`).
3. For each root, walk `contains` edges with a visited `Set` and a depth limit of 32.
4. Collect Items whose metadata contains `Certificates:`, `openssl.cnf:`, or `java.security:` keys.
5. Omit private-key Items (`Certificates:DerivedFromPrivateKey == true` or `Description` contains "private key").
6. Deduplicate by GitOID and cap at 100,000 components per root.
7. Map each Item to a CycloneDX `cryptographic-asset` component.
8. Write one JSON file per root with an atomic temp-file + rename.

## Component mapping

| Metadata family | `assetType` | Notes |
|-----------------|-------------|-------|
| X.509 cert (`Certificates:SubjectDN` present) | `certificate` | `certificateProperties` with subject/issuer/dates; sig/key/size as `properties` |
| OpenSSL config | `protocol` | `protocolProperties.type: tls` with version and cipher suites |
| Java security | `related-crypto-material` | Disabled/legacy algorithms and named groups as `properties` |
| Keystore | `related-crypto-material` | `type: key` |
| CRL | `related-crypto-material` | `type: other` |
| Public key (SSH/PGP) | `related-crypto-material` | `type: public-key`, includes `size` when known |

## Security boundaries

- Symlink components in the output path are rejected. — `T3.21`
- New directories are created with `0750`; files are written with `0640` (POSIX only). — `T3.22`
- Atomic writes prevent partial CBOM files and leave no `.tmp` files behind. — `T3.22`
- Traversal depth ≤ 32, component count ≤ 100,000 per root.
- Private keys are redacted.
- All failures are wrapped in `Try` and logged.

## Tests

`CbomEmitterSuite` (17 tests):
- `T3.1` / `T3.17` — CLI parsing and validation.
- `T3.2` — empty CBOM.
- `T3.3` / `T3.13` — certificate component mapping.
- `T3.4` — OpenSSL config component.
- `T3.5` — Java security component.
- `T3.6` — CycloneDX 1.7 emission and schema validation.
- `T3.7` — nested-archive traversal.
- `T3.8` — filename stability.
- `T3.9` — no CBOM when `--emit-cbom-dir` is omitted.
- `T3.10` — I/O failure handling.
- `T3.14` — multi-root CBOM.
- `T3.15` — cyclic `contains` graph.
- `T3.16` — duplicate GitOID deduplication.
- `T3.18` — output directory auto-creation.
- `T3.19` — private key redaction.
- `T3.20` — size limit / truncation.

## Schema validation

The test suite validates emitted CBOMs against the official CycloneDX 1.6 and 1.7 JSON schemas using `com.networknt:json-schema-validator`.

## 1.7 support note

The 1.7 implementation uses the same component structure as 1.6 and sets `specVersion` to `"1.7"`. If CycloneDX 1.7 introduces crypto-specific fields not present in 1.6, they can be added in a follow-up change documented in a new ADR.
