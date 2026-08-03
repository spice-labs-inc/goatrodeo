# CycloneDX CBOM Emitter

> **Navigation:** [Documentation Index](README.md) | [Architecture](architecture.md)

## Overview

The CycloneDX CBOM emitter is an optional, post-processing output stage that produces one cryptographic bill-of-materials (CBOM) JSON file per top-level input file. It walks the Artifact Dependency Graph (ADG) from each root, collects cryptographic Items, redacts private keys, and maps the remaining Items to CycloneDX 1.6 or 1.7 `cryptographic-asset` components.

## CLI flags

Two new command-line flags control the emitter:

| Flag | Meaning | Default |
|------|---------|---------|
| `--emit-cbom-dir <dir>` | Output directory for CBOM files. Disabled when omitted. | None |
| `--cbom-version <1.6\|1.7>` | CycloneDX specification version to emit. | `1.6` |

Only `"1.6"` and `"1.7"` are accepted; other values are a parse error. — verified by `CbomEmitterSuite.T3.1` and `CbomEmitterSuite.T3.17`.

## One CBOM per root

For every Item where `Item.isRoot()` is true, the emitter writes a single CBOM file. The filename is derived deterministically from the root GitOID, so rerunning the emitter on the same ADG produces the same filenames. — verified by `CbomEmitterSuite.T3.14` and `CbomEmitterSuite.T3.8`.

If a root contains no cryptographic material, the emitter still writes a valid CBOM with an empty `components` array. — verified by `CbomEmitterSuite.T3.2`.

## Traversal

The emitter follows `contains` edges (`EdgeType.contains`) transitively from each root. Cycles are detected with an immutable visited `Set` and a maximum depth of 32. The same GitOID reached through multiple paths appears only once in the CBOM. — verified by `CbomEmitterSuite.T3.7`, `CbomEmitterSuite.T3.15`, and `CbomEmitterSuite.T3.16`.

## Component mapping

Items are recognized as cryptographic when their metadata contains keys under any of these prefixes:

- `Certificates:` — from the `Certificates` strategy.
- `openssl.cnf:` — from the `OpenSSLConfig` strategy.
- `java.security:` — from the `JavaSecurity` strategy.

Each recognized Item becomes one CycloneDX component with `type: cryptographic-asset` and a `bom-ref` equal to the Item's GitOID.

### X.509 certificates

Certificate Items map to `cryptoProperties.assetType: certificate` with `certificateProperties` that include:

- `subjectName` (from `Certificates:SubjectDN`)
- `issuerName` (from `Certificates:IssuerDN`)
- `notValidBefore` and `notValidAfter` (ISO-8601 UTC)
- `certificateFormat: "X.509"`

Signature algorithm, public key algorithm, and key size are preserved as component `properties` (e.g., `Certificates:SigAlgorithm`, `Certificates:KeyAlgorithm`, `Certificates:KeySize`). — verified by `CbomEmitterSuite.T3.3` and `CbomEmitterSuite.T3.13`.

### OpenSSL configuration

OpenSSL config Items map to `cryptoProperties.assetType: protocol` with `protocolProperties.type: tls`. The captured `min_protocol`, `max_protocol`, and `cipher_string` values are emitted as `version` and `cipherSuites`. — verified by `CbomEmitterSuite.T3.4`.

### Java security policy

Java `java.security` Items map to `cryptoProperties.assetType: related-crypto-material`. Disabled algorithms, legacy algorithms, named groups, and other captured values are emitted as component `properties`. — verified by `CbomEmitterSuite.T3.5`.

### Keys, keystores, CRLs, and other material

Public keys, SSH keys, PGP keys, keystores, and CRLs are emitted as `cryptographic-asset` components with `relatedCryptoMaterialProperties`. Keystore entries are typed as `key`; CRLs and Java security files are typed as `other`; public keys are typed as `public-key`. Key size is included when available.

## Private key redaction

Items that are private keys are omitted from the CBOM. An Item is considered a private key if either:

- `Certificates:DerivedFromPrivateKey` has value `true`, or
- its `Description` contains the phrase "private key".

This covers plaintext PEM private keys, OpenSSH private keys, PGP secret keys, and encrypted private keys. — verified by `CbomEmitterSuite.T3.19`.

## Security boundaries

- Output directory creation rejects symlink components and uses `0750` permissions when POSIX is available. — verified by `CbomEmitterSuite.T3.21`.
- CBOM files are written atomically (temp file + rename) with `0640` permissions and no leftover `.tmp` files. — verified by `CbomEmitterSuite.T3.22`.
- Traversal is bounded: depth ≤ 32, and each root is capped at 100,000 components. If the cap is exceeded, a partial CBOM is emitted with a `cbom:truncated` top-level property and a warning is logged. — verified by `CbomEmitterSuite.T3.20`.
- I/O failures are captured in `Try` and logged; they do not crash the main build. — verified by `CbomEmitterSuite.T3.10`.

## Schema validation

Emitted CBOMs are validated against the official CycloneDX 1.6 and 1.7 JSON schemas using a JSON schema validator in the test suite. — verified by `CbomEmitterSuite.T3.2`, `CbomEmitterSuite.T3.3`, `CbomEmitterSuite.T3.6`, and `CbomEmitterSuite.T3.15`.

## Verification

- `CbomEmitterSuite` (17 tests) covers CLI parsing, empty CBOMs, certificate mapping, OpenSSL and Java security mapping, CycloneDX 1.7 emission, nested-archive traversal, filename stability, I/O failure handling, multi-root emission, cycle detection, duplicate GitOID deduplication, directory auto-creation, private key redaction, size limits, and the opt-out behavior when `--emit-cbom-dir` is omitted.

## Related

- Implementation: `src/main/scala/io/spicelabs/goatrodeo/omnibor/CbomEmitter.scala`
- CLI wiring: `src/main/scala/io/spicelabs/goatrodeo/util/Config.scala` and `src/main/scala/io/spicelabs/goatrodeo/omnibor/Builder.scala`
- ADR: `docs/adr/0005-cbom-output-format.md`
