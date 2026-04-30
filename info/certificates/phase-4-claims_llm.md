# Phase 4 — Claims (LLM)

Parallel of [`phase-4-claims.md`](phase-4-claims.md).

## State

Phase 4 done. Strategy claims PEM bundles, JKS/JCEKS/PKCS12/BKS
keystores, CRLs (PEM+DER). Phase-3 RFC2253 hex-encoding fix landed.

## Files

| File | Change |
|---|---|
| `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala` | sealed trait `ClaimedContent` + `SingleCert/Keystore/Bundle/Crl` cases; `classifyAndParse` priority order: bundle → JKS → JCEKS → PKCS12 → BKS-by-extension → CRL → single cert. Per-variant emitters. `dnString` decodes JDK `#hex` runs. `sanList` maps 9 GeneralName tags. BKS unconditionally envelope-only. |
| `src/test/scala/strategies/MaterializePhase4Sidecars.scala` | Scala one-shot for filling `<computed in Phase 4>` placeholders. Run via `sbt "Test/runMain io.spicelabs.goatrodeo.omnibor.strategies.MaterializePhase4Sidecars"`. |
| 18 sidecars under `pem-bundles/` and `crls/` | Placeholders → real pURL lists materialized. |
| `src/test/scala/{DotNetTesting,DockerSuite}.scala` | Narrow `find(_.startsWith("pkg"))` → `pkg:nuget` / `.filter(_.startsWith("pkg:docker"))` (strategy now legitimately emits x509 pURLs from certs inside containers). |

## Claim acceptance

- `pem-bundle` MIME → bundle path
- `jks` / `jceks` / `pkcs12` MIME → keystore path
- `.bks` extension fallback → BKS keystore path (always envelope-only)
- `pkix-crl` MIME → CRL path
- Single-cert MIMEs (Phase 3) → unchanged

## pURL shapes

- Bundle: N×{spki-sha256, cert-sha256} pURLs (one pair per cert, deduped)
- Keystore (readable): one pURL pair per cert + per chain element
- Keystore (encrypted/BKS): zero pURLs (Hard Rule #3)
- CRL: `pkg:x509/crl-sha256@{hex}?sig-alg={...}`

## Metadata namespacing

- Bundle: `Certificates:Cert:{idx}:{field}`
- Keystore entry: `Certificates:Entry:{urlencoded-alias}:{field}`, chain: `:Chain:{i}:{field}`
- CRL: top-level `Certificates:{IssuerDN, ThisUpdate, NextUpdate, SigAlgorithm, CrlSha256, RevokedCount, CrlNumber, RevokedSerials, RevokedTruncated}`

## Hard rules

- #1 (no private-key material): leak-sweep `assertNoLeak` runs before metadata return; zero throws across regression
- #2 (no child Items for keystores): `postChildProcessing` returns `this`
- #3 (no pURL for keystore container / encrypted): empty pURL list on `Keystore(None, ...)`

## Acceptance status

- ✓ all 33 Phase-4 fixtures (8 bundles + 14 keystores + 10 CRLs + 1 trust-bks) flip green
- ✓ `CryptoDetectorSuite` 60/60
- ✓ no new warnings
- ✓ leak sweep zero hits
- ✓ no Phase-4 regressions in unrelated tests (DotNet/Docker tests narrowed per user-approved Option A)

## Phase-3 follow-on (closed in Phase 4)

- `Description` field PQC/composite suffix — `singleCertMetadata` appends `(alg)` for ml-dsa/slh-dsa/falcon/composite; classical algs stay bare per `cert_sidecar.py` contract
- prehash OIDs `2.16.840.1.101.3.4.3.{32,33,34}` added to `pubkeyOidMap` → `("ml-dsa", Some("44"/"65"/"87"))`
- composite hybrid: `spkiBytesFromCert` + `spkiAlgOidFromCert` helpers via BC ASN.1 cert parser; `keyAlgAndQualifier(pub: PublicKey | Null, ...)`; `isSelfSigned` falls back to subject==issuer when pub is null; 5 composite OIDs `1.3.6.1.5.5.7.6.{37,39,40,41,49}` mapped in both pubkeyOidMap and sigAlgOidMap

## Final state

All Phase 1-4 fixtures green: 287/287. Remaining 66 CertificatesSuite failures are exclusively SSH (Phase 5), PGP (Phase 6), private-keys (Phase 7).

## HS-3

5 YES — with caveat (Phase-3 residuals listed; sidecar materialization is strategy-sourced by design).
