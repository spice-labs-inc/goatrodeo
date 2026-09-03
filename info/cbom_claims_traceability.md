# Phase 4 Documentation Claim Traceability

This file maps factual claims in the Phase 4 documentation to tests that verify them, satisfying R4.8.

## README.md / README_llm.md

| Claim | Verified By |
|-------|-------------|
| Goat Rodeo captures OpenSSL configurations. | `OpenSSLConfigSuite`, `OpenSSLConfigParserSuite` |
| Goat Rodeo captures Java `java.security` policies. | `JavaSecuritySuite`, `JavaSecurityParserSuite` |
| Goat Rodeo emits CycloneDX CBOM files. | `CbomEmitterSuite`, `CbomIntegrationSuite` |
| CLI flag `--emit-cbom-dir <dir>` exists and emits CBOMs. | `CbomEmitterSuite.T3.1`, `ConfigCbomFlagsSuite.T4.4`, `CbomIntegrationSuite.T4.2` |
| CLI flag `--cbom-version <1.6\|1.7>` exists and defaults to `1.6`. | `CbomEmitterSuite.T3.1`, `ConfigCbomFlagsSuite.T4.4` |

## info/goat_rodeo_operation.md / info/goat_rodeo_operation_llm.md

| Claim | Verified By |
|-------|-------------|
| `--emit-cbom-dir` and `--cbom-version` are documented CLI parameters. | `CbomEmitterSuite.T3.1`, `ConfigCbomFlagsSuite.T4.4` |
| Strategy list includes `OpenSSLConfig` and `JavaSecurity` in the correct order. | `OpenSSLConfigSuite`, `JavaSecuritySuite` |
| OpenSSL configs are detected by `OpenSSLConfigDetector` adding `application/x-openssl-config`. | `OpenSSLMimeDetectionSuite`, `OpenSSLConfigDetectorSuite` |
| OpenSSL config parser reads at most 1 MB per file. | `OpenSSLConfigParserSuite` |
| OpenSSL dependency ordering resolves `.include` references with depth cap 8 and cycle handling. | `OpenSSLConfigParserSuite`, `OpenSSLConfigSuite` |
| OpenSSL cross-file references use `containerGitOID:referencedFileGitOID` encoding. | `OpenSSLConfigSuite` |
| Java security files are detected by MIME or path (`/conf/security/java.security`, etc.). | `JavaSecurityDetectorSuite` |
| Java security parser extracts the five tracked properties. | `JavaSecurityParserSuite` |
| CBOM emission is optional and triggered after ADG write. | `CbomEmitterSuite.T3.9`, `CbomEmitterSuite.T3.2` |
| CBOMs contain certificate, OpenSSL config, and Java security components when present. | `CbomEmitterSuite.T3.3`, `CbomEmitterSuite.T3.4`, `CbomEmitterSuite.T3.5`, `CbomIntegrationSuite.T4.2` |
| Private keys are redacted from CBOMs. | `CbomEmitterSuite.T3.19` |
| CBOM files are written atomically with `0640` permissions and no leftover `.tmp` files. | `CbomEmitterSuite.T3.22` |
| CBOMs are capped at 100,000 components and marked truncated when exceeded. | `CbomEmitterSuite.T3.20` |

## info/goat_rodeo_api.md / info/goat_rodeo_api_llm.md

| Claim | Verified By |
|-------|-------------|
| `GoatRodeoBuilder` exposes `withCbomDir(String)` and `withCbomVersion(String)`. | `ConfigCbomFlagsSuite.T4.4` |
| `withExtraArg` supports `emitCbomDir` and `cbomVersion` keys. | `ConfigCbomFlagsSuite.T4.4` |

## docs/adr

| Claim | Verified By |
|-------|-------------|
| Required ADRs exist and contain both human and LLM sections. | `AdrExistenceSuite.T4.5` |

## info/architecture.md / info/architecture_llm.md

| Claim | Verified By |
|-------|-------------|
| Directory structure lists `OpenSSLConfig.scala`, `JavaSecurity.scala`, and `CbomEmitter.scala`. | `OpenSSLConfigSuite`, `JavaSecuritySuite`, `CbomEmitterSuite` |
| Strategy selection order includes `OpenSSLConfig` and `JavaSecurity` before `GenericFile`. | `OpenSSLConfigSuite`, `JavaSecuritySuite` |
| Output phase includes optional CBOM emission via `CbomEmitter.emitForStorage`. | `CbomEmitterSuite`, `CbomIntegrationSuite` |

## Per-Feature Documentation

The following documents already include their own `Verified by` sections and are not re-traced here:

- `info/openssl_mime_detection.md` / `info/openssl_mime_detection_llm.md`
- `info/openssl_config_capture.md` / `info/openssl_config_capture_llm.md`
- `info/java_security_capture.md` / `info/java_security_capture_llm.md`
- `info/cbom_emitter.md` / `info/cbom_emitter_llm.md`
- `docs/adr/0001-openssl-config-mime-augmenter.md`
- `docs/adr/0002-openssl-config-strategy.md`
- `docs/adr/0004-java-security-strategy.md`
- `docs/adr/0005-cbom-output-format.md`

## Phase H — Expanded Hashing Coverage (2026-08-14)

Claims in `info/cbom_emitter.md` "Algorithm classification" and
`info/adrs/adr_2026_08_14_crypto_algorithm_registry.md`:

| Claim | Verified By |
|-------|-------------|
| All new hash-family names classify as primitive `hash`. | `CryptoAlgorithmsSuite.R-T-02` |
| New hash assets validate against CycloneDX 1.6 and 1.7 schemas. | `CbomEmitterSuite.T3.29`, `CbomEmitterSuite.T3.31`, `CbomEmitterSuite.T3.32` |
| `parameterSetIdentifier` uses the explicit table (`sha512-224 → "224"`, `blake2b-512 → "512"`, `sha3-256 → "256"`). | `CryptoAlgorithmsSuite.R-T-03`, `CbomEmitterSuite.T3.30`, `CbomEmitterSuite.T3.32` |
| `argon2id` carries no `parameterSetIdentifier`. | `CryptoAlgorithmsSuite.R-T-03`, `CbomEmitterSuite.T3.30` |
| Every producer-emitted canonical name is in the registry vocabulary. | `CryptoAlgorithmsSuite.R-T-01`, `ServiceCryptoSuite.T-B-11`, `ShadowPasswordSuite.S-T-05` |
| Pre-phase classification/parameter behavior unchanged except approved deltas. | `CryptoAlgorithmsSuite.R-T-04` |
| CBOM output for pre-existing fixture families is byte-identical (1.6/1.7). | `CbomEmitterSuite.T3.33` |
| Attacker-controlled JWT `alg` cannot mint a hash asset. | `CbomEmitterSuite.T3.34` |
| Binary footprint recognizes EVP/Go/.NET md5/md4/sha3/blake2/shake/whirlpool symbols with canonical names and exact emission sets. | `CryptoFootprintSuite.T-E-07`, `CryptoFootprintSuite.T-E-08`, `CryptoFootprintSuite.T-E-09`, `CryptoFootprintSuite.T-E-10` |
| Only the two approved needle overlaps exist (`EVP_sha512 ⊂ EVP_sha512_224/256`). | `CryptoFootprintSuite.R-T-07` |
| PGP S2K hash map is total over RFC 9580 assigned + legacy RFC 4880 tags; reserved tags unmapped. | `PgpStrategyParserTests.P-T-01`, `PgpStrategyParserTests.P-T-02` |
| `/etc/shadow` argon2id/NT/apr1 envelopes parse with correct params/salt and no hash-value emission. | `ShadowPasswordSuite.S-T-01`, `ShadowPasswordSuite.S-T-02`, `ShadowPasswordSuite.S-T-03`, `ShadowPasswordSuite.S-T-04` |
| strongSwan `sha3_*`/`blake2b*` transforms decompose; unknown parts still dropped. | `ServiceCryptoSuite.T-B-09`, `ServiceCryptoSuite.T-B-10` |

## Phase I — SWHID Identifiers (2026-08-18)

Claims in `info/cbom_emitter.md` "OmniBOR and SWHID identifiers":

| Claim | Verified By |
|-------|-------------|
| Artifact-backed components keep `bom-ref` = `gitoid:blob:sha256` and gain `swhid:core` = `swh:1:cnt:<sha1>` from the `alias:from` sha1 edge; output validates against CycloneDX 1.6 and 1.7. | `CbomEmitterSuite.T3.35` |
| Items without a `gitoid:blob:sha1:` alias emit no SWHID property and stay schema-valid. | `CbomEmitterSuite.T3.36` |
| Malformed aliases (non-hex, wrong length, uppercase) are ignored — no bogus SWHID is minted. | `CbomEmitterSuite.T3.37` |

## Phase J — Carved DER Certificates (2026-08-19)

Claims in the carved-cert phase plan (`workspace/2026_08_19_carved_certs_plan.md`):

| Claim | Verified By |
|-------|-------------|
| Carved DER X.509 certs in binaries are detected in the 256 KB probe window and missed beyond it (doctrine). | `CarvedCertAugmenterSuite.A-2`, `CarvedCertificatesSuite.C-5` |
| The carve parses only fully valid certs, dedupes, and honours caps. | `CarvedCertificatesSuite.C-1`, `C-2`, `CarvedCertAugmenterSuite.A-3` |
| An RSA-1024 cert embedded in an ELF surfaces in the CBOM as a certificate component with KeySize 1024 and `alg:pke:rsa`/1024. | `CbomEmitterSuite.T3.41` |
| mbedTLS symbols flag firmware binaries with classifier `mbedtls` and `unknown=true` (no invented algorithm). | `CryptoFootprintSuite.T-E-11` |
| Unknown-flagged footprint items are not silently dropped from the CBOM. | `IoTGoatCbomSuite.T4.5` (regression restored), full `sbt test` |

## Phase K — Traversal-Derived CBOM Paths (2026-08-20)

Claims in `info/cbom_enhancements.md`:

| Claim | Verified By |
|-------|-------------|
| Every item-backed component carries `goatrodeo:path`, `goatrodeo:omnibor-path`, `goatrodeo:swhid-path` built from the `contains` hierarchy (root → … → item), joined by `|:|`. | `CbomEmitterSuite.T3.42` |
| Adding the path properties is the only delta to pre-existing output (byte-identity preserved otherwise). | `CbomEmitterSuite.T3.33` (regenerated goldens; diff verified = only the three props) |
| Algorithm assets carry the path of the item that produced them. | `CbomEmitterSuite.T3.42`, golden content |
| Full regression after the emitter change. | `sbt test` (2,340/0) |

## Phase L — ArduPilot AP_ROMFS Container Reader (2026-08-20)

| Claim | Verified By |
|-------|-------------|
| ArduPilot `AP_ROMFS` is treated as an archive: its embedded files become inner artifacts (read via `withStream` only, bounded). | `ApRomfsSuite.AR-1`, `AR-2`, `AR-3` |
| The Surveyor-OT-Demo trust-store certs (RSA-1024) surface in the CBOM with `KeySize 1024` and `goatrodeo:path`. | `CbomEmitterSuite.T3.43` |
| Corpus: ArduPilot + PX4 images under `test_data/firmware-images/`. | fixture presence + AR-1 |

## Phase 2 (2026-09-02) — MIME hints + PKCS#7 certificates

| Claim | Verified By |
|-------|-------------|
| Wrappers may carry an authoritative producer-stamped MIME hint, unioned into the effective MIME set; never sniffed; authoritative; survives spill | `MimeHintSuite.T5.1–T5.8` |
| The Certificates strategy claims `application/pkcs7-signature` (and not `application/pkcs7-mime`); exactly one non-terminal strategy claims it | `CertificatesPkcs7Suite.T6.1, T6.2`, `SingleCertificatesStrategySuite.T6.8` |
| Detached PKCS#7 SignedData parses to the embedded X.509 chain; bare DER shares the path; invalid/empty blobs skip cleanly | `CertificatesPkcs7Suite.T6.3–T6.6` |
| Cert MIME constants owned by the Certificates module | `CertificatesPkcs7Suite.T6.7` |
| PKCS#7 certs surface in the CBOM as cryptographic-asset/certificate with bundle + per-cert metadata; invalid blobs never appear as cert components; component-equivalent to a PEM bundle | `Pkcs7CbomSuite.T7.1–T7.4` |

## Phase 4/5 (2026-09-02) — GRD EOF, `.user-ready`, git-provenance not in CBOM

| Claim | Verified By |
|-------|-------------|
| Any negative GRD entry length is EOF (incl. −65536, min-int); positive past EOF is end-of-data; real round-trip unchanged | `GrdEofSuite.T13.1–T13.3` |
| `.user-ready` marker tolerated: discovery skips dot-names by name (readable or not); deletion never throws on un-deletable marker; never pollutes captured git trees | `UserReadyToleranceSuite.T14.1, T14.2, T14.4`, `GitRunInfoSuite.T14.3` |
| Git provenance Items (gitoid:commit:/tree:) are ItemTagData, never CBOM crypto inputs | `GitProvenanceNotInCbomSuite` |
