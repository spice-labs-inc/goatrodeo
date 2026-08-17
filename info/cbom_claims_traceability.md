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
