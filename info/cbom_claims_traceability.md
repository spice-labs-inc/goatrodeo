# CBOM Documentation Claim Traceability

This file maps factual claims in the documentation to tests that verify them.

## README.md / README_llm.md

| Claim | Verified By |
|-------|-------------|
| Goat Rodeo captures OpenSSL configurations. | `OpenSSLConfigSuite`, `OpenSSLConfigParserSuite` |
| Goat Rodeo captures Java `java.security` policies. | `JavaSecuritySuite`, `JavaSecurityParserSuite` |
| Goat Rodeo emits CycloneDX CBOM files. | `CbomEmitterSuite`, `CbomIntegrationSuite` |
| CLI flag `--emit-cbom-dir <dir>` exists and emits CBOMs. | ``CbomEmitterSuite` `CLI flags parse correctly``, ``ConfigCbomFlagsSuite` `GoatRodeoBuilder withExtraArg supports CBOM keys``, ``CbomIntegrationSuite` `mixed directory produces CBOMs with certificate, OpenSSL, and Java security components`` |
| CLI flag `--cbom-version <1.6\|1.7>` exists and defaults to `1.6`. | ``CbomEmitterSuite` `CLI flags parse correctly``, ``ConfigCbomFlagsSuite` `GoatRodeoBuilder withExtraArg supports CBOM keys`` |

## info/goat_rodeo_operation.md / info/goat_rodeo_operation_llm.md

| Claim | Verified By |
|-------|-------------|
| `--emit-cbom-dir` and `--cbom-version` are documented CLI parameters. | ``CbomEmitterSuite` `CLI flags parse correctly``, ``ConfigCbomFlagsSuite` `GoatRodeoBuilder withExtraArg supports CBOM keys`` |
| Strategy list includes `OpenSSLConfig` and `JavaSecurity` in the correct order. | `OpenSSLConfigSuite`, `JavaSecuritySuite` |
| OpenSSL configs are detected by `OpenSSLConfigDetector` adding `application/x-openssl-config`. | `OpenSSLMimeDetectionSuite`, `OpenSSLConfigDetectorSuite` |
| OpenSSL config parser reads at most 1 MB per file. | `OpenSSLConfigParserSuite` |
| OpenSSL dependency ordering resolves `.include` references with depth cap 8 and cycle handling. | `OpenSSLConfigParserSuite`, `OpenSSLConfigSuite` |
| OpenSSL cross-file references use `containerGitOID:referencedFileGitOID` encoding. | `OpenSSLConfigSuite` |
| Java security files are detected by MIME or path (`/conf/security/java.security`, etc.). | `JavaSecurityDetectorSuite` |
| Java security parser extracts the five tracked properties. | `JavaSecurityParserSuite` |
| CBOM emission is optional and triggered after ADG write. | ``CbomEmitterSuite` `no CBOM files are written without --emit-cbom-dir``, ``CbomEmitterSuite` `empty CBOM emitted for root with no crypto material`` |
| CBOMs contain certificate, OpenSSL config, and Java security components when present. | ``CbomEmitterSuite` `single certificate produces a valid CBOM component``, ``CbomEmitterSuite` `OpenSSL config produces a protocol component``, ``CbomEmitterSuite` `Java security produces a component with disabled algorithms``, ``CbomIntegrationSuite` `mixed directory produces CBOMs with certificate, OpenSSL, and Java security components`` |
| Private keys are redacted from CBOMs. | ``CbomEmitterSuite` `private-key-marker Items are emitted faithfully`` |
| CBOM files are written atomically with `0640` permissions and no leftover `.tmp` files. | ``CbomEmitterSuite` `CBOM write is atomic and leaves no temp files`` |
| CBOMs are capped at 100,000 components and marked truncated when exceeded. | ``CbomEmitterSuite` `oversized CBOM is truncated to 100,000 components`` |

## info/goat_rodeo_api.md / info/goat_rodeo_api_llm.md

| Claim | Verified By |
|-------|-------------|
| `GoatRodeoBuilder` exposes `withCbomDir(String)` and `withCbomVersion(String)`. | ``ConfigCbomFlagsSuite` `GoatRodeoBuilder withExtraArg supports CBOM keys`` |
| `withExtraArg` supports `emitCbomDir` and `cbomVersion` keys. | ``ConfigCbomFlagsSuite` `GoatRodeoBuilder withExtraArg supports CBOM keys`` |

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

## Expanded Hashing Coverage (2026-08-14)

Claims in `info/cbom_emitter.md` "Algorithm classification" and
`info/adrs/adr_2026_08_14_crypto_algorithm_registry.md`:

| Claim | Verified By |
|-------|-------------|
| All new hash-family names classify as primitive `hash`. | `CryptoAlgorithmsSuite.R-T-02` |
| New hash assets validate against CycloneDX 1.6 and 1.7 schemas. | ``CbomEmitterSuite` `new hash names classify as hash and validate in 1.6 and 1.7``, ``CbomEmitterSuite` `PasswordHash argon2id/nt-hash/apr1 flow into hash assets``, ``CbomEmitterSuite` `ServiceCrypto blake2b/sha3 algorithms classify as hash assets`` |
| `parameterSetIdentifier` uses the explicit table (`sha512-224 → "224"`, `blake2b-512 → "512"`, `sha3-256 → "256"`). | `CryptoAlgorithmsSuite.R-T-03`, ``CbomEmitterSuite` `parameterSetIdentifier correctness for new hash names``, ``CbomEmitterSuite` `ServiceCrypto blake2b/sha3 algorithms classify as hash assets`` |
| `argon2id` carries no `parameterSetIdentifier`. | `CryptoAlgorithmsSuite.R-T-03`, ``CbomEmitterSuite` `parameterSetIdentifier correctness for new hash names`` |
| Every producer-emitted canonical name is in the registry vocabulary. | `CryptoAlgorithmsSuite.R-T-01`, `ServiceCryptoSuite.T-B-11`, `ShadowPasswordSuite.S-T-05` |
| Pre-existing classification/parameter behavior unchanged except approved deltas. | `CryptoAlgorithmsSuite.R-T-04` |
| CBOM output for pre-existing fixture families is byte-identical (1.6/1.7). | ``CbomEmitterSuite` `pre-existing fixture families are byte-identical (golden)`` |
| Attacker-controlled JWT `alg` cannot mint a hash asset. | ``CbomEmitterSuite` `crafted JWT alg never mints a hash asset`` |
| Binary footprint recognizes EVP/Go/.NET md5/md4/sha3/blake2/shake/whirlpool symbols with canonical names and exact emission sets. | `CryptoFootprintSuite.T-E-07`, `CryptoFootprintSuite.T-E-08`, `CryptoFootprintSuite.T-E-09`, `CryptoFootprintSuite.T-E-10` |
| Only the two approved needle overlaps exist (`EVP_sha512 ⊂ EVP_sha512_224/256`). | `CryptoFootprintSuite.R-T-07` |
| PGP S2K hash map is total over RFC 9580 assigned + legacy RFC 4880 tags; reserved tags unmapped. | `PgpStrategyParserTests.P-T-01`, `PgpStrategyParserTests.P-T-02` |
| `/etc/shadow` argon2id/NT/apr1 envelopes parse with correct params/salt and no hash-value emission. | `ShadowPasswordSuite.S-T-01`, `ShadowPasswordSuite.S-T-02`, `ShadowPasswordSuite.S-T-03`, `ShadowPasswordSuite.S-T-04` |
| strongSwan `sha3_*`/`blake2b*` transforms decompose; unknown parts still dropped. | `ServiceCryptoSuite.T-B-09`, `ServiceCryptoSuite.T-B-10` |

## SWHID Identifiers (2026-08-18)

Claims in `info/cbom_emitter.md` "OmniBOR and SWHID identifiers":

| Claim | Verified By |
|-------|-------------|
| Artifact-backed components keep `bom-ref` = `gitoid:blob:sha256` and gain `swhid:core` = `swh:1:cnt:<sha1>` from the `alias:from` sha1 edge; output validates against CycloneDX 1.6 and 1.7. | ``CbomEmitterSuite` `artifact-backed component carries its SWHID and OmniBOR core`` |
| Items without a `gitoid:blob:sha1:` alias emit no SWHID property and stay schema-valid. | ``CbomEmitterSuite` `no SWHID property without a sha1 alias`` |
| Malformed aliases (non-hex, wrong length, uppercase) are ignored — no bogus SWHID is minted. | ``CbomEmitterSuite` `malformed sha1 aliases are ignored`` |

## Carved DER Certificates (2026-08-19)

Claims in the carved-cert plan (`workspace/2026_08_19_carved_certs_plan.md`):

| Claim | Verified By |
|-------|-------------|
| Carved DER X.509 certs in binaries are detected in the 256 KB probe window and missed beyond it (doctrine). | `CarvedCertAugmenterSuite.A-2`, `CarvedCertificatesSuite.C-5` |
| The carve parses only fully valid certs, dedupes, and honours caps. | `CarvedCertificatesSuite.C-1`, `C-2`, `CarvedCertAugmenterSuite.A-3` |
| An RSA-1024 cert embedded in an ELF surfaces in the CBOM as a certificate component with KeySize 1024 and `alg:pke:rsa`/1024. | ``CbomEmitterSuite` `carved RSA-1024 cert in an ELF surfaces in the CBOM`` |
| mbedTLS symbols flag firmware binaries with classifier `mbedtls` and `unknown=true` (no invented algorithm). | `CryptoFootprintSuite.T-E-11` |
| Unknown-flagged footprint items are not silently dropped from the CBOM. | ``IoTGoatCbomSuite` `IoTGoat x86 CBOM contains all discovered static cryptographic material`` (regression restored), full `sbt test` |

## Traversal-Derived CBOM Paths (2026-08-20)

Claims in `info/cbom_enhancements.md`:

| Claim | Verified By |
|-------|-------------|
| Every item-backed component carries `goatrodeo:path`, `goatrodeo:omnibor-path`, `goatrodeo:swhid-path` built from the `contains` hierarchy (root → … → item), joined by `|:|`. | ``CbomEmitterSuite` `nested components carry traversal-derived paths`` |
| Adding the path properties is the only delta to pre-existing output (byte-identity preserved otherwise). | ``CbomEmitterSuite` `pre-existing fixture families are byte-identical (golden)`` (regenerated goldens; diff verified = only the three props) |
| Algorithm assets carry the path of the item that produced them. | ``CbomEmitterSuite` `nested components carry traversal-derived paths``, golden content |
| Full regression after the emitter change. | `sbt test` (2,340/0) |

## ArduPilot AP_ROMFS Container Reader (2026-08-20)

| Claim | Verified By |
|-------|-------------|
| ArduPilot `AP_ROMFS` is treated as an archive: its embedded files become inner artifacts (read via `withStream` only, bounded). | `ApRomfsSuite.AR-1`, `AR-2`, `AR-3` |
| The Surveyor-OT-Demo trust-store certs (RSA-1024) surface in the CBOM with `KeySize 1024` and `goatrodeo:path`. | ``CbomEmitterSuite` `ArduPilot AP_ROMFS trust-store certs surface with KeySize 1024`` |
| Corpus: ArduPilot + PX4 images under `test_data/firmware-images/`. | fixture presence + AR-1 |

## MIME Hints + PKCS#7 Certificates (2026-09-02)

| Claim | Verified By |
|-------|-------------|
| Wrappers may carry an authoritative producer-stamped MIME hint, unioned into the effective MIME set; never sniffed; authoritative; survives spill | ``MimeHintSuite` `hintDefaultsToNone`–T5.8` |
| The Certificates strategy claims `application/pkcs7-signature` (and not `application/pkcs7-mime`); exactly one non-terminal strategy claims it | ``CertificatesPkcs7Suite` `pkcs7SignatureMimeIsClaimed`, T6.2`, ``SingleCertificatesStrategySuite` `exactly one non-terminal strategy claims pkcs7-signature`` |
| Detached PKCS#7 SignedData parses to the embedded X.509 chain; bare DER shares the path; invalid/empty blobs skip cleanly | ``CertificatesPkcs7Suite` `detachedSignedDataParsesToChain`–T6.6` |
| Cert MIME constants owned by the Certificates module | ``CertificatesPkcs7Suite` `certMimeConstantsOwnedByCertificates`` |
| PKCS#7 certs surface in the CBOM as cryptographic-asset/certificate with bundle + per-cert metadata; invalid blobs never appear as cert components; component-equivalent to a PEM bundle | ``Pkcs7CbomSuite` `pkcs7CertAppearsInCbom`–T7.4` |

## GRD EOF, `.user-ready`, Git Provenance Not in CBOM (2026-09-02)

| Claim | Verified By |
|-------|-------------|
| Any negative GRD entry length is EOF (incl. −65536, min-int); positive past EOF is end-of-data; real round-trip unchanged | `GrdEofSuite.T13.1–T13.3` |
| `.user-ready` marker tolerated: discovery skips dot-names by name (readable or not); deletion never throws on un-deletable marker; never pollutes captured git trees | ``UserReadyToleranceSuite` `fileDiscoverySkipsDotFiles (readable and unreadable)`, T14.2, T14.4`, ``GitRunInfoSuite` `markerInWorktreeDoesNotPolluteCapturedTree`` |
| Git provenance Items (gitoid:commit:/tree:) are ItemTagData, never CBOM crypto inputs | `GitProvenanceNotInCbomSuite` |
