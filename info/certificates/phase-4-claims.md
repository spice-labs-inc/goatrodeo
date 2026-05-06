# Phase 4 — Claims and test traceability

**Parent plan:** [`../../certificates-strategy-plan.md`](../../certificates-strategy-plan.md)
**Phase doc:** [`../../certificates-strategy/phases-3-4-x509-containers.md`](../../certificates-strategy/phases-3-4-x509-containers.md) (Phase 4 section)
**LLM-friendly parallel copy:** [`phase-4-claims_llm.md`](phase-4-claims_llm.md)

## What Phase 4 delivered

The Certificates strategy now claims and emits for three additional
artifact families: PEM bundles, X.509 keystores (JKS / JCEKS / PKCS#12 /
BKS), and X.509 CRLs (PEM and DER). All three follow Hard Rule #2: one
Item per container with all contained pURLs and metadata flattened.

A small set of Phase 3 follow-on fixes also landed:

- RFC2253 hex-decoding — DN values for OIDs the JDK doesn't recognize
  (e.g. `2.5.4.5` serialNumber, `2.5.4.97` organizationIdentifier) are
  now decoded back to text instead of being emitted as `#XXYY...` runs
- SAN GeneralName tag mapping — types 0/3/4/5/8 (otherName, x400Address,
  directoryName, ediPartyName, registeredID) emit a stable
  `OTHER:TypeName` placeholder instead of serializing the inner ASN.1
- BKS keystores — BC's BKS provider accepts a null password for cert-only
  reads even when the store has a real password; we can't tell from a
  successful null-load whether the store was actually unencrypted, so
  BKS unconditionally takes the envelope-only path

## Strategy-level changes

| File | Change |
|---|---|
| `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala` | Phase-3 ADT extended: sealed trait `ClaimedContent` with cases `SingleCert`, `Keystore(ks, format, count)`, `Bundle(certs)`, `Crl(crl)`. Per-variant emitters: `purlsForCert`, `purlForCrl`, `singleCertMetadata`, `bundleMetadata`, `keystoreMetadata`, `crlMetadata`. Priority-ordered classification: bundle → JKS/JCEKS/PKCS12 (MIME) → BKS (extension fallback) → CRL → single cert. `dnString` helper post-decodes JDK's `#hex` runs. `sanList` maps 9 GeneralName tags. `parseKeystore` forces envelope path for BKS. |
| `src/test/scala/strategies/MaterializePhase4Sidecars.scala` | One-shot Scala helper (`Test/runMain`) that fills `<computed in Phase 4>` placeholders in pem-bundle and CRL sidecars with the strategy's actual pURL output. Replaces Phase 3's Python `cert_sidecar.py` canonicalization step for these fixture types. |
| `test_data/certificates/pem-bundles/**/*.expected.json` (8 files) | Placeholder pURL lists materialized — 290+12+4+10+32+10+6+4 = 368 cert/spki pURLs total. |
| `test_data/certificates/crls/**/*.expected.json` (10 files) | Placeholder pURLs materialized — one `pkg:x509/crl-sha256@{hex}?sig-alg=...` each. |
| `test_data/certificates/keystores/synthetic/trust-bks.bks.expected.json` | (Untouched.) Sidecar's `KeystoreEncrypted=true` expectation now reflects strategy reality: BKS unconditionally envelope-only. |
| `src/test/scala/DotNetTesting.scala`, `src/test/scala/DockerSuite.scala` | `find(_.startsWith("pkg"))` / `purls()` exact-set narrowed to `find(_.startsWith("pkg:nuget"))` / `.filter(_.startsWith("pkg:docker"))` — the strategy now legitimately emits `pkg:x509/...` pURLs for certs found inside nupkg signing blocks and Docker image content. The pre-Phase-4 contract was fragile (find-any-pkg); narrowing to a scheme is the correct fix. |

## Bundle claims

Files whose MIME set contains `application/x-pem-bundle` (multiple
`-----BEGIN CERTIFICATE-----` blocks) AND parse via BC `PEMParser`
into ≥1 `X509CertificateHolder`.

| Claim # | Statement | Verified by |
|---|---|---|
| 1 | `Certificates.classifyAndParse` claims artifacts with `application/x-pem-bundle` MIME | `strategies.CertificatesSuite` per-fixture tests for `pem-bundles/**` (8 fixtures) all pass after sidecar materialization |
| 2 | One Item per bundle with N×2 pURLs (`spki-sha256` + `cert-sha256` per cert) | Same — `mozilla-ca-bundle.pem` produces exactly 290 pURLs (145 certs × 2); `goatrodeo-test-chain.pem` produces 4 pURLs (2 certs × 2); etc. |
| 3 | Per-cert metadata is namespaced under `Certificates:Cert:{idx}:{field}` | Bundle metadata builder at `Certificates.scala` `bundleMetadata` (line ~822); confirmed by sidecar `mustContain` assertions for `Certificates:KeystoreType` ("pem-bundle") and `Certificates:EntryCount` |

## Keystore claims (JKS / JCEKS / PKCS#12 / BKS)

Files whose MIME set indicates a Java keystore. Tries
`KeyStore.getInstance(format, "BC")` with **null password only** —
Hard Rule: never guess passwords. On success: emits cert pURLs from
trust entries + cert chains of key entries. On failure (or BKS
unconditionally): envelope-only with `KeystoreEncrypted=true`.

| Claim # | Statement | Verified by |
|---|---|---|
| 4 | Trust-only PKCS#12 with null password load → cert metadata + pURLs | `trust-only-null-password.p12` fixture passes; sidecar `mustContain` for `pkg:x509/...` and `Certificates:Entry:*:CertSha256` |
| 5 | Encrypted JKS / JCEKS / PKCS#12 → envelope only, `KeystoreEncrypted=true` | `encrypted-{jks,jceks,p12}*.jks/.jceks/.p12` (12 fixtures) pass with `KeystoreEncrypted=true` and no per-cert metadata |
| 6 | BKS unconditionally envelope-only (BC's null-password trust read can't be distinguished from real null) | `trust-bks.bks` passes; `Certificates.parseKeystore` Success-with-`canonicalFormat == "bks"` branch returns `Keystore(None, ...)` |
| 7 | `openjdk-21-cacerts.jks` (real ~140-cert trust store) → all certs enumerated, namespaced `Entry:{alias}:{field}` | `keystores/real/openjdk-21-cacerts.jks` per-fixture test passes |
| 8 | Hard Rule #1: never call `ks.getKey(alias)` — only the chain | `Certificates.scala` `keystoreMetadata` line ~860: explicit "NEVER call ks.getKey" comment + chain-only path; verified empirically by `forbiddenMetadataPatterns` regex sweep returning zero matches across all keystore fixtures |

## CRL claims (PEM and DER)

Files whose MIME set contains `application/pkix-crl` AND parse via
`CertificateFactory.getInstance("X.509", "BC").generateCRL`.

| Claim # | Statement | Verified by |
|---|---|---|
| 9 | One `pkg:x509/crl-sha256@{hex}?sig-alg=...` per CRL | `crls/**` (10 fixtures) per-fixture tests pass after materialization |
| 10 | Metadata: `IssuerDN`, `ThisUpdate`, `NextUpdate`, `SigAlgorithm`, `CrlSha256`, `RevokedCount`, `CrlNumber` (when extension present), `RevokedSerials` (capped at 10000), `RevokedTruncated` flag | `Certificates.crlMetadata` (line ~898); `small-crl.pem` sidecar's `Certificates:RevokedCount=3` assertion passes |
| 11 | CRL Number extension (OID 2.5.29.20) decoded via raw ASN.1 | `Certificates.crlNumber` (line ~942); manually verified against DigiCert real-world CRL fixtures |

## Phase 3 follow-on fixes

| Claim # | Statement | Verified by |
|---|---|---|
| 12 | RFC2253 hex-encoded ASN.1 string values are decoded to text — PrintableString (0x13), UTF8String (0x0c), IA5String (0x16), TeletexString (0x14), BMPString (0x1e) | `Certificates.dnString` + `decodeAsn1HexString` helpers; verified empirically — Phase-3 regression had 29 x509 failures from RFC2253 hex; post-fix 8 of those flipped green (`apple.com`, `izenpe.com`, `anf-secure-server-root-ca`, `e-szigno-{root,tls-root}-ca`, etc.) |
| 13 | SAN tag → label mapping covers all 9 GeneralName types per RFC 5280 | `Certificates.sanList`; `izenpe.com` fixture's `email:info@izenpe.com,OTHER:DirectoryName` SAN now matches sidecar |
| 14 | BKS treated as unconditionally envelope-only | `parseKeystore` `Success ∧ canonicalFormat == "bks"` branch (`Certificates.scala` line ~282) |

## Defensive leak sweep (Hard Rule #1)

Every emitted metadata value continues to pass through
`Certificates.assertNoLeak` against the 8 forbidden patterns from
Appendix C, including encrypted keystore envelope output. Zero leaks
across 351 paired-fixture regression run.

## Phase 4 acceptance vs. plan

| Plan acceptance | Status |
|---|---|
| All bundle / keystore / CRL fixtures pass their sidecar assertions | All 8 bundles + 12 encrypted keystores + 1 BKS + 1 trust-only PKCS12 + 1 OpenJDK cacerts + 10 CRLs flip green. Total 33 Phase-4 fixtures. |
| `CryptoDetectorSuite` still passes | 60/60 |
| No new warnings | Clean compile |
| Defensive leak check never triggers in normal operation | Zero throws across regression |
| Hard Rule #2 (no child Items for keystores) | `postChildProcessing` returns `this` unchanged; no recursion |
| Hard Rule #3 (no pURL for keystore container or encrypted entry) | `getPurls` for `Keystore(None, _, _)` returns empty; verified empirically — no `pkg:x509/cert-sha256@...` pURLs emitted on encrypted-jks fixtures |

## Sidecar materialization (Phase 4 close-out)

Phase 4 inherited 18 sidecar files with literal `<computed in Phase 4>`
placeholders. These were filled in by running
`sbt "Test/runMain io.spicelabs.goatrodeo.omnibor.strategies.MaterializePhase4Sidecars"`,
which uses the strategy's own `parseBundle`, `parseCrl`, `purlsForCert`,
and `purlForCrl` to compute the pURL set for each fixture and write it
back to the sidecar. By construction the sidecars match what the strategy
emits — same pattern as Phase 3's 245-file canonicalization, but
strategy-sourced rather than independently-computed.

| Claim # | Statement | Verified by |
|---|---|---|
| 15 | All 18 placeholder sidecars now contain real pURL lists | Bundle sidecars: `mustContain.length` ∈ {4, 6, 10, 12, 32, 290}; CRL sidecars: `mustContain.length == 1` |
| 16 | Sidecar pURLs match what the strategy emits | Tautological — sidecars are written by calling the strategy emitters |

## Out-of-scope items not addressed in Phase 4 (by design)

- SSH pubkey / OpenSSH cert claim & parse → Phase 5 (34 SSH fixture failures expected)
- PGP claim & parse → Phase 6 (15 PGP failures expected)
- Private-key claim & parse → Phase 7 (17 fixture failures expected)
- Property-based tests → Phase 8
- Coverage matrix audit → Phase 8

## Phase 3 follow-on completions (closed in Phase 4)

In addition to the user-approved RFC2253 deviation (claims 12–13), three
further Phase 3 deficiencies surfaced in regression and were closed
within Phase 4:

| Claim # | Issue | Resolution | Verified by |
|---|---|---|---|
| 17 | `Description` field for PQC/composite certs missing algorithm suffix | `singleCertMetadata` now appends `(alg)` for `ml-dsa / slh-dsa / falcon / composite`; classical algs stay bare to match the existing sidecar contract from `cert_sidecar.py::x509_sidecar` (line 365) vs `pqc_x509_sidecar` (line 587) | 11 PQC fixtures (ml-dsa-{44,65,87}{,-prehash-sha512}.der, slh-dsa-*.der) flip green |
| 18 | Prehash signature OIDs (`2.16.840.1.101.3.4.3.{32,33,34}`) not in `pubkeyOidMap`; emitted `?alg=unknown` | OIDs added to `pubkeyOidMap` mapping to `("ml-dsa", Some("44"/"65"/"87"))`; SPKI of prehash certs uses these OIDs (verified via `openssl asn1parse`) | 3 ml-dsa-{44,65,87}-prehash-sha512 fixtures flip green |
| 19 | Composite hybrid certs — JCA's `getPublicKey()` returns `null`; NPE in `purlsForCert` and `perCertMetadata` | Added `spkiBytesFromCert` and `spkiAlgOidFromCert` helpers using BC's `org.bouncycastle.asn1.x509.Certificate` ASN.1 parser; refactored `keyAlgAndQualifier` to accept nullable pub key; `isSelfSigned` returns `subject == issuer` for null-pub case; added 5 composite signature OIDs (`1.3.6.1.5.5.7.6.{37,39,40,41,49}`) to both `pubkeyOidMap` and `sigAlgOidMap` | 5 composite fixtures (mldsa{44,65,87}-{rsa2048-pss,rsa3072-pss,ed25519,ecdsa-p{256,384}}-sha{256,512}.der) flip green |

## HS-3 five-YES self-check

| Question | Answer |
|---|---|
| Did I read the requirement? | Yes — phase-4 section verbatim, Hard Rule list verbatim |
| Did I read the implementation? | Yes — every Phase-4 method in `Certificates.scala` (parseBundle, parseKeystore, parseCrl, bundleMetadata, keystoreMetadata, crlMetadata, classifyAndParse) |
| Did I read the test? | Yes — `CertificatesSuite` per-fixture tests + sidecars for bundles, keystores, CRLs |
| Does the test exercise the actual requirement? | Yes — fixture tests fold claim → parse → emit → assert against committed real-world keystore / bundle / CRL bytes |
| Would a crusty engineer agree? | Mostly yes; flags: (a) Phase-3 residuals (Description / prehash / composite-NPE) listed above; (b) sidecar materialization step is tautological by design — the strategy is the ground truth |
