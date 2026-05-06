# Phase 3 — Claims (LLM)

Parallel of [`phase-3-claims.md`](phase-3-claims.md).

## State

Phase 3 done. Strategy claims X.509 single-cert artifacts (PEM + DER),
emits SPKI + cert-sha256 pURLs, full metadata table, runs defensive
leak sweep before Item write.

## Files

| File | Change |
|---|---|
| `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala` | Phase-1 stub → Phase-3 production: claim logic + parse (PEM via BC PEMParser, DER via JcaX509CertificateConverter) + canonical-mapping tables (RSA/DSA/EC/Ed25519/Ed448/ML-DSA/SLH-DSA/Falcon, sig-alg OID map, EC-curve map, EKU OID map) + pURL emission + metadata emission + leak sweep |
| `test_data/certificates/**/*.expected.json` | 245 sidecars re-canonicalized to alphabetical qualifier order (matches `PackageURL.canonicalize()`); Phase-0b correction |
| `info/certificates_strategy.md` | Phase 3: `pending` → `done` |

## Claim acceptance

- `pkix-cert` / `x-x509-ca-cert` MIME → claim
- `x-pem-file` ∧ ¬`x-pem-bundle` → claim
- Other MIMEs (keystore / bundle / SSH / PGP / private-key / CRL) → reject (Phase 4–7)
- Parse failure → don't claim, fall through to Generic

## pURL shape

- `pkg:x509/spki-sha256@{hex}?alg={alg}&{companion}&version={n}`
- `pkg:x509/cert-sha256@{hex}?alg={alg}&{companion}&self-signed={bool}&sig-alg={sig-alg}&version={n}`

(qualifiers alphabetical per PackageURL canonical form)

## Metadata keys (all under `Certificates:` prefix)

`SubjectDN, IssuerDN, Serial, NotBefore, NotAfter, KeyAlgorithm,
KeySize, Curve, Params, SigAlgorithm, SpkiSha256, CertSha256, IsCA,
SelfSigned, KeyUsage, ExtendedKeyUsage, Version, SAN`

Plus standard `Name`, `Publisher`, `Description`.

All values String / StringOrPair. Dates ISO-8601 UTC. Lists comma-
separated.

## Leak sweep (Hard rule #1)

8 forbidden regex patterns from Appendix C. Throws on match. Verified
in regression: 0 throws across all X.509 fixtures.

## Acceptance status

- ✓ `CryptoDetectorSuite` still 60/60
- ✓ no new warnings
- ✓ leak sweep doesn't trigger
- ✗ LOC budget — total ~830 across both files, plan asked < 300; surfaced as known-deviation in claims doc
- ✓ sidecar fixtures flip green for X.509-claimed artifacts (count in regression result)
