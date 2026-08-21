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
4. Collect Items whose metadata contains a known crypto family key (see list).
5. Do **not** redact private keys in the emitter: private-key bytes are
   discarded at capture time and never enter ADG metadata; marker flags are
   emitted faithfully.
6. Deduplicate by GitOID and cap at 100,000 components per root.
7. Map each Item to a CycloneDX `cryptographic-asset` component, emitting
   `swhid:core` + `omnibor:core` together and the three `goatrodeo:*:path`
   traversal properties.
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

## Core identifiers and traversal paths

- `swhid:core` = `swh:1:cnt:<sha1>` from the item's `alias:from`
  `gitoid:blob:sha1:` edge (no re-hash). `omnibor:core` = the item's own
  `gitoid:blob:sha256:<hex>` (== `bom-ref`).
- `swhid:core` and `omnibor:core` are always emitted together and each equals
  the leaf of its `goatrodeo:*:path`. — `T3.35`, `T3.44`
- `goatrodeo:path` = chain of container names (first `fileNames`, fallback
  gitoid); `goatrodeo:omnibor-path` = chain of `gitoid:blob:sha256` ids;
  `goatrodeo:swhid-path` = chain of `swh:1:cnt` ids; joined by `|:|`.
  — `T3.42`

## ADG handoff

The emitter reads a set of `Item`s (`identifier`, `connections`, `body_mime_type`,
`body` = `ItemMetaData` with `fileNames`/`mimeType`/`fileSize`/`extra`). Edges:
`contained:down` (contains), `contained:up` (containedBy), `alias:from`,
`alias:to`, `build:down`, `build:up`, `tag:from`, `tag:to`. Crypto metadata is in
`extra` keys under any family prefix (`Certificates:`, `openssl.cnf:`,
`java.security:`, `PasswordHash:`, `Usign:`, `SSH:`, `TLSConfig:`,
`EmbeddedCertificates:`, `ServiceCrypto:`, `Kerberos:`, `JWT:`, `JWK:`,
`EmbeddedKey:`, `CryptoAlgorithms:`, `CryptoDependency:`, `MobileTls:`). Roots:
`isRoot()` (metadata mime, not `"tags"`, no `alias:to`/`contained:up`). One CBOM
per root, filename `cbom_gitoid_blob_sha256_<root-hex>.json`. See
`info/cbom_emitter.md` → "Handoff" for the full algorithm.

## Security boundaries

- Symlink components in the output path are rejected. — `T3.21`
- New directories are created with `0750`; files are written with `0640` (POSIX only). — `T3.22`
- Atomic writes prevent partial CBOM files and leave no `.tmp` files behind. — `T3.22`
- Traversal depth ≤ 32, component count ≤ 100,000 per root.
- Private-key bytes never enter the ADG (capture-time enforcement); marker flags are emitted.
- All failures are wrapped in `Try` and logged.

## Tests

`CbomEmitterSuite` (41 tests):
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
- `T3.19` — private-key-marker items emitted faithfully (no redaction).
- `T3.20` — size limit / truncation.
- `T3.21`/`T3.22` — symlink rejection / atomic writes.
- `T3.23`–`T3.28` — algorithm refs (keys, CRLs, EC curves, password hashes, usign).
- `T3.29` — new hash names classify `hash`; 1.6/1.7 schema-valid.
- `T3.30` — `parameterSetIdentifier` correctness.
- `T3.31` — PasswordHash argon2id/nt-hash/apr1 → hash assets.
- `T3.32` — ServiceCrypto blake2b/sha3 → hash assets.
- `T3.33` — golden byte-identity across 15 pre-existing metadata families.
- `T3.34` — hostile JWT `alg` never mints a hash asset.
- `T3.35` — artifact-backed component carries `swhid:core` + `omnibor:core`.
- `T3.36` — no core properties without a sha1 alias; still schema-valid.
- `T3.37` — malformed sha1 aliases ignored.
- `T3.41` — carved RSA-1024 cert in an ELF surfaces in the CBOM.
- `T3.42` — nested container chain produces the three `goatrodeo:*:path`.
- `T3.43` — ArduPilot AP_ROMFS trust-store certs surface with KeySize 1024.
- `T3.44` — `swhid:core`/`omnibor:core` pair agree with the path leaf.

`CryptoAlgorithmsSuite` (6 tests) — registry totality/classification/
parameter/regression/hygiene/collision (R-T-01..06).

## Algorithm classification (Phase H)

Algorithm assets are classified and parameterized by the shared registry
`CryptoAlgorithms` (`src/main/scala/io/spicelabs/goatrodeo/omnibor/CryptoAlgorithms.scala`),
the single source of truth for algorithm vocabulary, primitive
classification, and `parameterSetIdentifier` extraction (ADR:
`adrs/adr_2026_08_14_crypto_algorithm_registry.md`).

- New hash names classify as primitive `hash`: `sha3-224`, `sha3-384`,
  `sha512-224`, `sha512-256`, `blake3`, `shake128`, `shake256`, `sm3`,
  `streebog`, `sha-3`, `md4`, `mdc2`, `blake2b-256`, `blake2b-512`,
  `blake2s-256`, `tiger192`, `haval`, `double-sha`, `nt-hash`, `apr1`.
  — `CryptoAlgorithmsSuite.R-T-02`
- `parameterSetIdentifier`: explicit table (`sha512-224→224`,
  `blake2b-512→512`, `sha3-256→256`, `sha3-512→512`); omitted for
  `argon2*`, `shake*`, `blake3`, `sm3`, `md4`, etc.; legacy
  first-digit-run fallback otherwise. — `CryptoAlgorithmsSuite.R-T-03`,
  `CbomEmitterSuite.T3.30`
- Producer totality: every canonical name any discovery strategy emits is in
  `canonicalVocabulary`. — `CryptoAlgorithmsSuite.R-T-01`,
  `ServiceCryptoSuite.T-B-11`
- Regression: pre-phase names keep old behavior except approved deltas
  C1–C6 (ADR). — `CryptoAlgorithmsSuite.R-T-04`, `CbomEmitterSuite.T3.33`
  (byte-identical golden snapshots, CycloneDX 1.6/1.7)
- JWT `alg` uses the `signature` context (attacker-controlled; never
  free-text classification). — `CbomEmitterSuite.T3.34`

## Schema validation

The test suite validates emitted CBOMs against the official CycloneDX 1.6 and 1.7 JSON schemas using `com.networknt:json-schema-validator`.

## 1.7 support note

The 1.7 implementation uses the same component structure as 1.6 and sets `specVersion` to `"1.7"`. If CycloneDX 1.7 introduces crypto-specific fields not present in 1.6, they can be added in a follow-up change documented in a new ADR.
