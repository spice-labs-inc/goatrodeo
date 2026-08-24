# CycloneDX CBOM Emitter

> **Navigation:** [Documentation Index](README.md) | [Architecture](architecture.md)
>
> **Handoff:** this document is the specification for a system that generates
> CBOMs from a Goat Rodeo Artifact Dependency Graph (ADG). See
> [Handoff: generating CBOMs from the ADG](#handoff-generating-cboms-from-the-adg)
> for the input data model and the exact algorithm to reproduce.

## Overview

The CycloneDX CBOM emitter is an optional, post-processing output stage that produces one cryptographic bill-of-materials (CBOM) JSON file per top-level input file. It walks the Artifact Dependency Graph (ADG) from each root, collects cryptographic Items, and maps them to CycloneDX 1.6 or 1.7 `cryptographic-asset` components.

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

Items are recognized as cryptographic when their metadata (`ItemMetaData.extra`)
contains keys under any of these prefixes:

`Certificates:`, `openssl.cnf:`, `java.security:`, `PasswordHash:`, `Usign:`,
`SSH:`, `TLSConfig:`, `EmbeddedCertificates:`, `ServiceCrypto:`, `Kerberos:`,
`JWT:`, `JWK:`, `EmbeddedKey:`, `CryptoAlgorithms:`, `CryptoDependency:`,
`MobileTls:`, `CloudKey:`, `DbEncryption:`.

Each recognized Item becomes one CycloneDX component with
`type: cryptographic-asset` and a `bom-ref` equal to the Item's GitOID — with
one exception: `CryptoDependency:` items become **`library`** components (see
below).

### Component name and description

- `name` = the first value of `Name`, else the first value of `Description`,
  else the Item's GitOID. An Item whose derived name is empty emits **no**
  component.
- `description` = the first value of `Description`, when present.
- Every `extra` value under a recognized prefix is emitted as a separate
  `properties` entry (`{name, value}`), with its raw key as the name.

### Family dispatch (evaluated in this order; first match wins)

An Item can carry more than one family; the emitter evaluates the families in
this exact order and applies the first matching branch:

| Family (key prefix) | `cryptoProperties.assetType` | Related material type | Algorithm assets |
|---------------------|------------------------------|----------------------|------------------|
| `Certificates:` (X.509 cert) | `certificate` | — (`certificateProperties`) | key alg → `pke` (+ size/curve), sig alg → `signature` |
| `openssl.cnf:` | `protocol` | — (`protocolProperties.type: tls`) | — |
| `java.security:` | `related-crypto-material` | `other` | — |
| `Certificates:` (keystore key entries) | `related-crypto-material` | `key` | entry key algs → `pke` (+ size/curve) |
| `Certificates:` (CRL) | `related-crypto-material` | `other` | sig alg → `signature` |
| `Certificates:` (public key) | `related-crypto-material` | `public-key` | key alg → `pke`; SSH-cert sig → `signature` |
| `PasswordHash:` | `related-crypto-material` | `password` | `PasswordHash:Algorithm` → `hash` |
| `Usign:` | `related-crypto-material` | `public-key` | key alg → `pke` (+ size) |
| `SSH:` | `related-crypto-material` | from `SSH:MaterialType` (`private-key-placeholder` → `private-key`) | key alg → `pke`; SSH-cert sig → `signature` |
| `TLSConfig:` | `related-crypto-material` | `other` | — |
| `EmbeddedCertificates:` | `related-crypto-material` | `other` | — |
| `EmbeddedKey:` | `related-crypto-material` | from `EmbeddedKey:kind` | key alg → `pke` (+ size) |
| `ServiceCrypto:` | `related-crypto-material` | `other` | `ServiceCrypto:algorithms` ∪ `DbEncryption:algorithms` → `other` |
| `Kerberos:` | `related-crypto-material` | `other` | `Kerberos:algorithms` → `other` |
| `JWT:` | `related-crypto-material` | `other` | `JWT:signature_algorithm` → `signature` (`none` filtered) |
| `JWK:` | `related-crypto-material` | `public-key` / `private-key` (from `JWK:private_present`) | `kty` RSA→`rsa`, EC→`ec` → `pke` (+ size) |
| `CryptoAlgorithms:` | (none — no material component) | — | `CryptoAlgorithms:algorithm` → `other`; empty → material `other` |
| `CryptoDependency:` | `library` components (not keyed by GitOID) | — | crypto-family `properties`, joined `algorithms` property |
| `MobileTls:` | `related-crypto-material` | `other` | `MobileTls:algorithms` → `other` |
| `CloudKey:` | `related-crypto-material` | `other` | none (identifiers/specs are properties) |
| `DbEncryption:` | `related-crypto-material` | `other` | `DbEncryption:algorithms` ∪ `ServiceCrypto:algorithms` → `other` |

Algorithm assets are synthetic components keyed
`alg:<primitive>:<normalized-name>`; the name is lowercased with
non-alphanumeric runs collapsed to `-` (leading/trailing `-` stripped; empty
result → no component). `primitive` is the context where given (`pke`,
`signature`, `hash`), else the registry classification. `parameterSetIdentifier`
and `curve` come from the size/curve metadata and the registry. Synthetic
algorithm components are deduplicated across the whole CBOM by `bom-ref`,
keeping the first occurrence.

### X.509 certificates

Certificate Items map to `cryptoProperties.assetType: certificate` with
`certificateProperties` that include:

- `subjectName` (from `Certificates:SubjectDN` or `Certificates:Cert:0:SubjectDN`)
- `issuerName` (from `Certificates:IssuerDN` or `Certificates:Cert:0:IssuerDN`)
- `notValidBefore` and `notValidAfter` (ISO-8601 UTC, from
  `Certificates:NotBefore`/`NotAfter`)
- `certificateFormat: "X.509"`

Signature algorithm, public key algorithm, and key size are preserved as
component `properties` (e.g., `Certificates:SigAlgorithm`,
`Certificates:KeyAlgorithm`, `Certificates:KeySize`). — verified by
`CbomEmitterSuite.T3.3` and `CbomEmitterSuite.T3.13`.

### OpenSSL configuration

OpenSSL config Items map to `cryptoProperties.assetType: protocol` with
`protocolProperties.type: tls`. The captured `min_protocol`, `max_protocol`, and
`cipher_string` values are emitted as `version` and `cipherSuites`. — verified
by `CbomEmitterSuite.T3.4`.

### Java security policy

Java `java.security` Items map to `cryptoProperties.assetType: related-crypto-material`.
Disabled algorithms, legacy algorithms, named groups, and other captured values
are emitted as component `properties`. — verified by `CbomEmitterSuite.T3.5`.

### Keys, keystores, CRLs, and other material

Public keys, SSH keys, PGP keys, keystores, and CRLs are emitted as
`cryptographic-asset` components with `relatedCryptoMaterialProperties`.
Keystore entries are typed as `key`; CRLs and Java security files are typed as
`other`; public keys are typed as `public-key`. Key size is included when
available.

## Private-key handling

Private keys are **not redacted in the emitter**. The private-key hard constraint
is enforced at *capture* time: decoded private-key bytes are discarded and never
enter ADG metadata, so they cannot appear in any CBOM. Because of this, every
ADG field that maps to a valid CBOM field is included — including private-key
marker flags such as `Certificates:DerivedFromPrivateKey` and
`SSH:MaterialType`. An item that carries these markers is emitted faithfully
(with the marker flags as properties), not dropped. — verified by `CbomEmitterSuite.T3.19`.

## Security boundaries

- Output directory creation rejects symlink components and uses `0750` permissions when POSIX is available. — verified by `CbomEmitterSuite.T3.21`.
- CBOM files are written atomically (temp file + rename) with `0640` permissions and no leftover `.tmp` files. — verified by `CbomEmitterSuite.T3.22`.
- Traversal is bounded: depth ≤ 32, and each root is capped at 100,000 components. If the cap is exceeded, a partial CBOM is emitted with a `cbom:truncated` top-level property and a warning is logged. — verified by `CbomEmitterSuite.T3.20`.
- I/O failures are captured in `Try` and logged; they do not crash the main build. — verified by `CbomEmitterSuite.T3.10`.

## Schema validation

Emitted CBOMs are validated against the official CycloneDX 1.6 and 1.7 JSON schemas using a JSON schema validator in the test suite. — verified by `CbomEmitterSuite.T3.2`, `CbomEmitterSuite.T3.3`, `CbomEmitterSuite.T3.6`, and `CbomEmitterSuite.T3.15`.

## Algorithm classification

Algorithm assets (`cryptoProperties.assetType: algorithm`) are classified and parameterized by the shared registry `CryptoAlgorithms` (`src/main/scala/io/spicelabs/goatrodeo/omnibor/CryptoAlgorithms.scala`) — the single source of truth for algorithm vocabulary, primitive classification, and `parameterSetIdentifier` extraction. See `adrs/adr_2026_08_14_crypto_algorithm_registry.md`.

The registry's hash family includes `md5`, `sha1`, `sha224`, `sha256`, `sha384`, `sha512`, `sha3-224`, `sha3-256`, `sha3-384`, `sha3-512`, `sha512-224`, `sha512-256`, `blake2b`, `blake2s`, `blake2b-256`, `blake2b-512`, `blake2s-256`, `blake3`, `shake128`, `shake256`, `whirlpool`, `ripemd160`, `sm3`, `streebog`, `sha-3`, `md4`, `mdc2`, `tiger192`, `haval`, `double-sha`, `bcrypt`, `scrypt`, `yescrypt`, `argon2`, `argon2d`, `argon2i`, `argon2id`, `nt-hash`, and `apr1` — all classify as primitive `hash`. — verified by `CryptoAlgorithmsSuite.R-T-02`.

`parameterSetIdentifier` uses an explicit per-name table (`sha512-224 → "224"`, `blake2b-512 → "512"`, `sha3-256 → "256"`, `sha3-512 → "512"`) and omits the parameter entirely for names whose digits are version/family digits (`argon2*`, `shake*`, `blake3`, `sm3`, `md4`, …). — verified by `CryptoAlgorithmsSuite.R-T-03`, `CbomEmitterSuite.T3.30`.

Every canonical name a discovery strategy can emit is a member of the registry vocabulary (`CryptoAlgorithms.canonicalVocabulary`); no strategy can emit a name the classifier never registered. — verified by `CryptoAlgorithmsSuite.R-T-01`, `ServiceCryptoSuite.T-B-11`.

Pre-existing behavior is preserved: classification and parameter extraction for all pre-phase names is unchanged except the explicitly approved deltas (ADR Consequences). — verified by `CryptoAlgorithmsSuite.R-T-04`, `CbomEmitterSuite.T3.33` (byte-identical golden snapshots for 15 metadata families, CycloneDX 1.6 and 1.7).

JWT `alg` values are attacker-controlled; they are emitted with the `signature` context, never via free-text classification, so a crafted `alg` such as `md4` cannot mint a `hash` asset. — verified by `CbomEmitterSuite.T3.34`.

## OmniBOR, SWHID, and traversal-path identifiers

Every artifact-backed cryptographic-asset component is keyed by the artifact's OmniBOR identifier: `bom-ref` is the `gitoid:blob:sha256:<hex>` of the Item. Each component additionally carries a paired core-identifier pair and a three-way traversal path:

- `swhid:core` — `swh:1:cnt:<sha1>`, the Software Heritage content identifier derived from the Item's `alias:from` `gitoid:blob:sha1:<hex>` edge (same sha1 bytes, SWHID prefix — no re-hashing).
- `omnibor:core` — the Item's own `gitoid:blob:sha256:<hex>` OmniBOR id (equals `bom-ref`).
- `swhid:core` and `omnibor:core` are **always emitted together**: neither appears without the other, and each equals the final (leaf) node of its corresponding `goatrodeo:*:path`. — verified by `CbomEmitterSuite.T3.35`, `CbomEmitterSuite.T3.44`.
- `goatrodeo:path` — the chain of container names (each node's first `fileNames`, falling back to its gitoid) joined by `|:|`. Example: `firmware.img|:|romfs|:|etc/ssl/certs/root-ca.crt`.
- `goatrodeo:omnibor-path` — the same chain as `gitoid:blob:sha256:<hex>` identifiers.
- `goatrodeo:swhid-path` — the same chain as `swh:1:cnt:<sha1>` identifiers (nodes without a sha1 alias are omitted, best-effort).

Malformed sha1 aliases (non-hex, wrong length, uppercase) are ignored rather than emitted as bogus identifiers, and items without a well-formed alias emit neither `swhid:core` nor `omnibor:core`. — verified by `CbomEmitterSuite.T3.36`, `CbomEmitterSuite.T3.37`. Traversal-path emission is verified by `CbomEmitterSuite.T3.42`.

When a Goat Rodeo run sets a correlation ID (see [Tamper-Evident Logging](tamper_evident_logging.md)), each CBOM additionally carries a top-level `goatrodeo:correlation-id` property, linking the CBOM to the run that produced it and to its tamper-evident log. — verified by `CbomEmitterSuite` (correlation-id omitted when no run is active).

## Handoff: generating CBOMs from the ADG

This section is the contract for a downstream system that reads a Goat Rodeo
ADG and reproduces the CBOM output without running the Scala emitter.

### ADG input model

The ADG is a set of **Items**. Each Item is keyed by its `identifier` — a
`gitoid:blob:sha256:<hex>` OmniBOR id. An Item has:

| Field | Meaning |
|-------|---------|
| `identifier` | `gitoid:blob:sha256:<hex>` |
| `connections` | a set of `(edgeType, targetGitOID)` directed edges |
| `bodyMimeType` | `application/vnd.cc.goatrodeo` for metadata-bearing items (`ItemMetaData.mimeType`) |
| `body` | `ItemMetaData`: `fileNames` (set), `mimeType` (set), `fileSize` (Int), `extra` (map of key → set of string values) |

Edge-type strings (`EdgeType`):

| Constant | String value | Direction |
|----------|--------------|-----------|
| `contains` | `contained:down` | container → contained |
| `containedBy` | `contained:up` | contained → container |
| `aliasFrom` | `alias:from` | item → its OmniBOR/SWHID aliases (e.g. `gitoid:blob:sha1:<hex>`) |
| `aliasTo` | `alias:to` | alias → item |
| `builtFrom` | `build:down` | build → source |
| `buildsTo` | `build:up` | source → build |
| `tagFrom` | `tag:from` | tag → item |
| `tagTo` | `tag:to` | item → tag |

Cryptographic material lives in `ItemMetaData.extra`, whose keys use a
`<Family>:<Field>` prefix (see the prefix list below).

### Obtaining the ADG

- In-process: implement the `Storage` interface (`read(key)`, `keys()`); the
  canonical reference is `MemStorage`. `emitForStorage(storage, version, outDir)`
  is the single entry point.
- On disk: `--dump-json <dir>` writes `items_<timestamp>.json`, a JSON array of
  all Items (`Storage.emitAllItemsToDir`); `--dump-roots <dir>` writes
  `roots_<timestamp>.json`, the identifiers of all roots. Both are a faithful
  serialization of the same Items.

### Algorithm (reproduce exactly)

1. **Roots.** One CBOM per Item where `isRoot()` holds: `bodyMimeType ==
   "application/vnd.cc.goatrodeo"`, `identifier != "tags"`, and no `alias:to`
   or `contained:up` edge. — `CbomEmitterSuite.T3.14`.
2. **Traversal.** Breadth-first over `contained:down` edges from each root.
   Track a visited `Set` of gitoids and the chain root → … → item. Depth is
   capped at 32; nodes beyond depth and already-visited nodes are skipped.
   The same gitoid reached by multiple paths appears once. — `T3.7`, `T3.15`,
   `T3.16`.
3. **Crypto detection.** An Item is cryptographic when any `extra` key starts
   with one of: `Certificates:`, `openssl.cnf:`, `java.security:`,
   `PasswordHash:`, `Usign:`, `SSH:`, `TLSConfig:`, `EmbeddedCertificates:`,
   `ServiceCrypto:`, `Kerberos:`, `JWT:`, `JWK:`, `EmbeddedKey:`,
   `CryptoAlgorithms:`, `CryptoDependency:`, `MobileTls:`, `CloudKey:`,
   `DbEncryption:`. — `T3.3`–`T3.5`, `T3.38`–`T3.43`.
4. **Cap.** Per root, at most 100,000 collected components; beyond that the CBOM
   is emitted with a `cbom:truncated` top-level property. — `T3.20`.
5. **Map.** For each collected Item, derive `name`/`description` from `Name` /
   `Description` (empty name → skip the Item), then apply the
   [family dispatch table](#family-dispatch-evaluated-in-this-order-first-match-wins)
   **in the documented order, first match wins**. The chosen branch produces the
   main component (`certificate` / `protocol` / `related-crypto-material` /
   `library` — or none for pure-`CryptoAlgorithms` items) plus the referenced
   synthetic `algorithm` components keyed `alg:<primitive>:<name>`. Algorithm
   names use the per-branch context (`pke`, `signature`, `hash`, `other`);
   `ServiceCrypto` and `DbEncryption` branches union both families'
   `algorithms` sets. — `T3.3`, `T3.23`–`T3.28`, `T3.41`–`T3.43`.
6. **Identifiers.** See the identifiers section above: emit `swhid:core` +
   `omnibor:core` together (from the item's sha1 alias and its own id), and the
   three `goatrodeo:*:path` traversal properties.
7. **Filename.** `cbom_<escaped-first-file-name>_<last-16-of-gitoid>.json` —
   the root's first `fileNames` entry escaped (`[A-Za-z0-9_-]` kept, everything
   else → `_`, truncated to 80 chars keeping the tail) plus the last 16 hex
   chars of the root gitoid. — `T3.8`, `TamperEvidentSuite.T-05`.
8. **Schema.** `specVersion` = `1.6` or `1.7`; `serialNumber` =
   `urn:uuid:` + UUID v5 name-based on the root identifier's UTF-8 bytes;
   `metadata.tools` = `{type: application, name: goatrodeo, version:
   <BuildInfo.version>}`; `metadata.timestamp` is the emit-time instant (the
   only non-deterministic field). Output must validate against the official
   CycloneDX 1.6/1.7 JSON schemas. — `T3.6`.

### Notes and edge cases

- The root Item itself is checked for cryptographic metadata and, if crypto,
  included in the CBOM with chain `[root]`.
- Synthetic `algorithm` components are deduplicated across the whole CBOM by
  `bom-ref`, keeping the first occurrence. — `T3.16`.
- `CryptoDependency:` items become `library` components (`bom-ref`
  `dep-<name>`), not cryptographic-asset components; their `CryptoDependency:`
  values become `crypto-family` properties plus a joined `algorithms` property.
- JWT `none` never becomes an algorithm; JWT algorithms use the `signature`
  context (attacker-controlled input). — `T3.34`.
- SSH `private-key-placeholder` maps to CycloneDX `private-key` material type;
  the original marker is preserved as a property. — `ExtendedCaptureCbomSuite`.
- An Item whose derived `name` is empty emits no component at all.
- No whole-image or whole-graph loads are required; the traversal reads Items by
  key. Cryptographic items and containers may be embedded in AP_ROMFS and other
  nested containers (ArduPilot `AP_ROMFS`, PX4 tar ROMFS). — `T3.41`, `T3.43`.

## Verification

- `CbomEmitterSuite` (41 tests) covers CLI parsing, empty CBOMs, certificate mapping, OpenSSL and Java security mapping, CycloneDX 1.7 emission, nested-archive traversal, filename stability, I/O failure handling, multi-root emission, cycle detection, duplicate GitOID deduplication, directory auto-creation, private-key-marker fidelity, size limits, the opt-out behavior, expanded hash classification/parameters (T3.29–T3.32), golden byte-identity (T3.33), the hostile-JWT guard (T3.34), SWHID/OmniBOR core emission (T3.35–T3.37), carved certs (T3.41), traversal paths (T3.42), AP_ROMFS certs (T3.43), and the core/path-leaf agreement (T3.44).
- `CryptoAlgorithmsSuite` (6 tests) pins the shared registry: producer-vocabulary totality (R-T-01), new-name classification (R-T-02), parameter rules (R-T-03), behavior regression (R-T-04), canonical-form hygiene (R-T-05), and substring-collision safety (R-T-06).

## Related

- Implementation: `src/main/scala/io/spicelabs/goatrodeo/omnibor/CbomEmitter.scala`
- CLI wiring: `src/main/scala/io/spicelabs/goatrodeo/util/Config.scala` and `src/main/scala/io/spicelabs/goatrodeo/omnibor/Builder.scala`
- ADR: `docs/adr/0005-cbom-output-format.md`
