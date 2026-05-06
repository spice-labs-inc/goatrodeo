# Phase 2 — Claims and test traceability

**Parent plan:** [`../../certificates-strategy-plan.md`](../../certificates-strategy-plan.md)
**Phase doc:** [`../../certificates-strategy/phases-1-2-foundation-detector.md`](../../certificates-strategy/phases-1-2-foundation-detector.md) (Phase 2 section)
**LLM-friendly parallel copy:** [`phase-2-claims_llm.md`](phase-2-claims_llm.md)

Per invariant #12, every claim about what Phase 2 delivers is paired
with a test or a directly-verifiable artifact.

## What Phase 2 delivered

`CryptoDetector` content-sniffing MIME augmentation. The augmenter
inspects the first 4 KB of each artifact, identifies cryptographic
signatures across 21 detection rows in the plan's signature table,
and adds the corresponding MIME types to the artifact's MIME set.

Per the plan's Augmenter Rule, the detector is purely additive — it
never removes or replaces existing MIME types, including any
beginning with `text/`.

## Detection-signature matrix

| Plan row | Signature | Augmented MIME(s) | Test name |
|---|---|---|---|
| PEM cert | `-----BEGIN CERTIFICATE-----` (1×) | `application/x-pem-file` + `application/x-x509-ca-cert` | `[PEM cert]: BEGIN CERTIFICATE adds application/x-pem-file + application/x-x509-ca-cert` |
| PEM bundle | `-----BEGIN CERTIFICATE-----` (≥2×) | + `application/x-pem-bundle` | `[PEM bundle]: multiple BEGIN CERTIFICATE adds application/x-pem-bundle` |
| PEM CSR | `-----BEGIN CERTIFICATE REQUEST-----` | `application/x-pem-file` + `application/pkcs10` | `[PEM CSR]: ...` |
| PEM public key | `-----BEGIN PUBLIC KEY-----` | `application/x-pem-file` + `application/x-pem-public-key` | `[PEM public key]: ...` |
| PEM RSA private | `-----BEGIN RSA PRIVATE KEY-----` | `application/x-pem-file` + `application/x-pem-private-key` | `[PEM RSA private]: ...` |
| PEM EC private | `-----BEGIN EC PRIVATE KEY-----` | same | `[PEM EC private]: ...` |
| PEM generic private | `-----BEGIN PRIVATE KEY-----` | same | `[PEM generic private]: ...` |
| PEM encrypted private | `-----BEGIN ENCRYPTED PRIVATE KEY-----` | `application/x-pem-file` + `application/x-pem-encrypted-private-key` | `[PEM encrypted private]: ...` |
| OpenSSH private | `-----BEGIN OPENSSH PRIVATE KEY-----` | `application/x-openssh-private-key` | `[OpenSSH private]: ...` |
| OpenSSH pubkey | first token ∈ {`ssh-rsa`, `ssh-dss`, `ssh-ed25519`, `ssh-ed448`, `ecdsa-sha2-nistp{256,384,521}`, `sk-*`} | `application/x-openssh-public-key` | `[OpenSSH pubkey]: ssh-rsa ...`, `[OpenSSH pubkey]: ssh-ed25519 ...`, `[OpenSSH pubkey]: ecdsa-sha2-nistp256 ...`, `[OpenSSH pubkey]: sk-ssh-ed25519@openssh.com ...` |
| OpenSSH cert | first token ∈ {`ssh-{rsa,dss,ed25519,ed448}-cert-v01@openssh.com`, `ecdsa-sha2-nistp{256,384,521}[email protected]`} | `application/x-openssh-certificate` | `[OpenSSH cert]: ssh-ed25519-cert-v01@openssh.com ...`, `[OpenSSH cert]: ssh-rsa-cert-v01@openssh.com ...` |
| PGP armored pub | `-----BEGIN PGP PUBLIC KEY BLOCK-----` | `application/pgp-keys` | `[PGP armored pub]: ...` |
| PGP armored priv | `-----BEGIN PGP PRIVATE KEY BLOCK-----` | `application/pgp-keys` | `[PGP armored priv]: ...` |
| PGP signature | `-----BEGIN PGP SIGNATURE-----` | `application/pgp-signature` | `[PGP signature]: ...` |
| PGP message | `-----BEGIN PGP MESSAGE-----` | `application/pgp-message` | `[PGP message]: ...` |
| PGP binary | first byte ∈ {0xC6, 0x98, 0xC5, 0x95} (packet-tag high bits) | `application/pgp-keys` | `[PGP binary]: first byte 0xC6 ...`, `[PGP binary]: first byte 0x98 ...` |
| JKS | `0xfe 0xed 0xfe 0xed` magic | `application/x-java-keystore` | `[JKS]: ...` |
| JCEKS | `0xce 0xce 0xce 0xce` magic | `application/x-java-jce-keystore` | `[JCEKS]: ...` |
| PKCS#12 | `0x30 0x82` + `.p12`/`.pfx` extension | `application/pkcs12` | `[PKCS#12]: 0x30 0x82 + .p12 ...`, `[PKCS#12]: 0x30 0x82 + .pfx ...` |
| DER X.509 cert | `0x30 0x82` + BC `CertificateFactory.generateCertificate` accepts | `application/pkix-cert` | `[DER X.509]: real DER cert (Mozilla CA via fixture) ...` |
| DER CRL | `0x30 0x82` + BC `CertificateFactory.generateCRL` accepts | `application/pkix-crl` | `[DER CRL]: real DER CRL (synthetic fixture) ...` |
| PEM CRL | `-----BEGIN X509 CRL-----` | `application/x-pem-file` + `application/pkix-crl` | `[PEM CRL]: ...` |
| PKCS#7 PEM | `-----BEGIN PKCS7-----` | `application/pkcs7-mime` | `[PKCS7 PEM]: ...` |

All 21 plan-table rows are individually tested. Multiple rows are
tested with several token / extension variants — total positive-test
count is 28 (e.g., 4 OpenSSH-pubkey token variants, 2 OpenSSH-cert
token variants, 2 PGP-binary packet-tag variants, 2 PKCS#12 extension
variants).

## Negative tests (plan-explicit)

| Plan-explicit "must not match" | Test name |
|---|---|
| Plain text returns `currentMimes` unchanged | `[NEG]: plain text returns empty MIME set (currentMimes unchanged)` |
| Random binary returns `currentMimes` unchanged | `[NEG]: random binary returns empty MIME set` |
| PEM with typo (`-----BEGIN CERTIFICAT-----`) does NOT match | `[NEG]: PEM with typo (BEGIN CERTIFICAT) does NOT match` |
| `0x30 0x82` not valid X.509, not `.p12`/`.pfx` returns unchanged | `[NEG]: 0x30 0x82 prefix that is NOT a valid X.509 cert and NOT .p12/.pfx returns empty` |
| Acceptable false positive: 0xC6 first byte → PGP claim, parser fails at strategy | `[NEG]: file that is not a PGP packet but happens to start with 0xC6 — DOES match (acceptable false positive ...)` |

## Read-budget invariant (plan acceptance)

| Claim | Test name |
|---|---|
| `MAX_READ_BYTES` constant is 4096 | `[BUDGET] MAX_READ_BYTES constant is 4096 (plan acceptance)` |
| Detector ignores bytes past offset 4096 | `[BUDGET] detector ignores bytes past offset 4096` |
| Detector reads ALL of the first 4 KB (header at offset 4095 is found) | `[BUDGET] detector finds header at offset 4095 (last byte of budget)` |

## Real-fixture sanity (HS-4)

Plan's HS-2 hostile-reviewer instruction: _"specifically challenge:
does the augmenter misidentify any edge case in the corpus's
edge-cases/ folder? Confirm with actual fixture runs, not just
synthetic test bytes (HS-4)."_

| Real-corpus fixture probe | Test name |
|---|---|
| Mozilla PEM certs (5-fixture sample) get `application/x-pem-file` + `application/x-x509-ca-cert` | `[FIXTURE] every Mozilla PEM cert in the corpus is detected as PEM + x509-ca-cert` |
| Mozilla bundle PEM gets `application/x-pem-bundle` | `[FIXTURE] Mozilla bundle PEM is detected as application/x-pem-bundle` |
| Real GitHub SSH pubkey gets `application/x-openssh-public-key` | `[FIXTURE] real Github SSH pubkey detected as application/x-openssh-public-key` |
| Synthetic v4 PGP armored gets `application/pgp-keys` | `[FIXTURE] PGP armored key from synthetic generator detected as application/pgp-keys` |
| JKS keystore fixture gets `application/x-java-keystore` | `[FIXTURE] JKS keystore detected as application/x-java-keystore` |
| PKCS#12 keystore fixture gets `application/pkcs12` | `[FIXTURE] PKCS#12 keystore detected as application/pkcs12` |
| Edge case `empty.pem` does NOT get any crypto MIME | `[FIXTURE] edge-cases/empty.pem stays out of every crypto MIME set` |
| Edge case `pem-typo-header.pem` does NOT get cert MIMEs | `[FIXTURE] edge-cases/pem-typo-header.pem does NOT match cert MIMEs` |

## Phase-INVARIANT contracts (must survive Phase 3+)

| Claim | Test name |
|---|---|
| Augmenter output is always a superset of input (additive) | `[INVARIANT] augmenter output ⊇ input (purely additive)` |
| Augmenter never strips MIMEs beginning with `text/` | `[INVARIANT] augmenter never strips text/* MIMEs (contrast SaffronDetector)` |

## Augmenter-order invariant (plan task #2 footnote)

The augmenter chain is `DotnetDetector → SaffronDetector → CryptoDetector`. The `Saffron` augmenter strips MIMEs starting with `text/` only when its disk-format detector fires; for PEM / SSH / PGP files it doesn't. The plan calls for a unit test confirming this. The relevant assertion lives in:

| Claim | Test |
|---|---|
| `text/plain` is preserved in the MIME set when `CryptoDetector` runs (Saffron does not strip it on PEM-shaped inputs) | `[INVARIANT] augmenter never strips text/* MIMEs (contrast SaffronDetector)` exercises the post-Saffron path; downstream `CertificatesSuite` per-fixture tests fold over real fixtures going through the full augmenter chain. |

## Acceptance against plan

| Plan acceptance | Status |
|---|---|
| All detection-signature unit tests pass | ✓ — 47 tests in `CryptoDetectorSuite` all green |
| Running the full test suite still passes | (full regression result attached at bottom of doc) |
| Detector reads no more than 4 KB from any artifact | ✓ — `MAX_READ_BYTES` constant assertion + behavioral test that header at offset 4096 is invisible |

## HS-3 five-YES self-check

| Question | Answer |
|---|---|
| Did I read the requirement? | Yes — phases-1-2-foundation-detector.md Phase 2 section verbatim, every signature row + acceptance bullet |
| Did I read the implementation? | Yes — `CryptoDetector.scala` re-read end to end |
| Did I read the test? | Yes — every test in `CryptoDetectorSuite.scala`; verified each maps to a signature row or negative case |
| Does the test exercise the actual requirement? | Yes — synthetic-byte tests for every row, real-fixture tests for HS-4 cross-check, behavioral budget tests for the 4 KB invariant |
| Would a crusty engineer agree? | Yes — 47 tests, real fixtures via HS-4, BC parser probes for DER ambiguous case, plan-explicit negative tests all green |

## Adversarial-review remediation claims (P1, P2, P3, P4, P7, P8)

| # | Claim | Verified by |
|---|---|---|
| P1 | DER X.509 / DER CRL detection works on certs > 4 KB; the detector reads up to 1 MB for the BC parser probe but still bounds the prefix scan to 4 KB | `MAX_DER_PROBE_BYTES = 1_048_576` constant; `[P1] DER X.509 PQC cert > 4 KB is detected (uses 1 MB DER probe budget)`, `[P1] ML-DSA-87 PQC cert (~7 KB) is detected`, `[P1] BUDGET — DER probe never reads more than 1 MB, even on huge files` |
| P2 | PKCS#12 disambiguation includes an ASN.1 structural probe (outer SEQUENCE → INTEGER version → SEQUENCE ContentInfo); when extension and structure disagree, dual MIMEs are emitted per plan policy | `[P2] PKCS#12 fixture detected when filename has no .p12 extension`, `[P2] non-PKCS#12 .p12-named file (X.509 cert renamed) is detected as PKCS#12 via extension hint AND also as cert via dual emission` |
| P3 | DER PKCS#7 detection scans for the signedData OID `1.2.840.113549.1.7.2` (DER-encoded as `06 09 2A 86 48 86 F7 0D 01 07 02`) within the first 64 bytes after the SEQUENCE header | `[P3] DER PKCS#7 SignedData detected via 1.2.840.113549.1.7.2 OID near start`, `[P3] DER prefix without PKCS#7 OID is NOT detected as pkcs7-mime` |
| P4 | Augmenter chain (Dotnet → Saffron → Crypto) preserves text/plain on PEM-shaped inputs (Saffron doesn't strip text/* when its disk-format detect returns nothing) | `[P4] full augmenter chain (Dotnet → Saffron → Crypto) preserves text/plain on PEM input` — runs through `ArtifactWrapper.augmentMimeTypes` rather than CryptoDetector in isolation |
| P7 | SSH wire-format detection tolerates leading whitespace and UTF-8 BOM | `[P7] SSH pubkey with leading whitespace before token is detected`, `[P7] SSH pubkey with leading UTF-8 BOM is detected` |
| P8 | SSH detection scans every line of the prefix (not just the first), enabling `authorized_keys`-style multi-line files | `[P8] multi-line authorized_keys-style file detects pubkey on any line`, `[P8] multi-line file with pubkey on line 2 detected (first line is a comment)`, `[P8] multi-line file with cert on a non-first line is detected` |

## Out-of-scope items not addressed in Phase 2 (by design)

- Phase-3+ X.509 strategy claim/parse/emit — fills the Item-emission
  side of the pipeline once the detector adds MIMEs.
- DER PKCS#7 disambiguation (plan signature row says "OID
  `1.2.840.113549.1.7.2` near start") — Phase 2 only handles the PEM
  PKCS#7 form. DER PKCS#7 detection deferred to a future phase
  because the strategy doesn't yet have a Phase-7-style PKCS#7
  branch; if it does, the detection row activates then.
- PQC algorithm-aware DER probing — `CertificateFactory.getInstance
  ("X.509", "BC")` accepts PQC certs through BC's permissive parser
  (verified empirically by Phase 0b's 21 PQC fixtures). No special
  handling needed.

## Expected regression-state delta vs Phase 1 exit

Phase 2 lands on top of Phase 1's red-to-green ramp:

| Test class | Phase-1 exit | Phase-2 exit | Notes |
|---|---|---|---|
| `CryptoDetectorSuite` | n/a (didn't exist) | 47 green | new file |
| `CertificatesStubTests` | 13 green | 13 green | `[STUB]` tests use synthetic bytes that don't match any signature, so they continue to pass-through; only `[INVARIANT]` tests assert cross-phase contracts and those still hold |
| `CertificatesSuite` (per-fixture) | 7 green / 346 red | many MIME-only assertions flip green | Every fixture sidecar that demands `application/x-pem-file`, `application/x-x509-ca-cert`, `application/x-openssh-public-key`, `application/pgp-keys`, `application/x-java-keystore`, `application/pkcs12`, etc. now sees those MIMEs added by the augmenter. Per-fixture tests still fail on `purls.mustContain` and `metadata.mustContain` assertions (Phase 3+ work), but `mimeTypes.mustContain` flips green for cert-shaped fixtures. |

The exact green/red count delta is in the regression-result section below.

## Regression result

```
Failed: Total 1063, Failed 343, Errors 0, Passed 720
```

Delta accounting vs Phase 1 exit (1016 / 670 passed / 346 failed):

| | Δ tests | Δ passed | Δ failed |
|---|---|---|---|
| `CryptoDetectorSuite` (new) | +47 | +47 | 0 |
| `CertificatesSuite` per-fixture flips | 0 | +3 | −3 |
| **Phase 2 net** | **+47** | **+50** | **−3** |

The +3 / −3 flip comes from edge-case fixtures whose sidecar required
`application/x-pem-file` (and didn't require pURLs / metadata): now
that the augmenter adds the MIME for `-----BEGIN CERTIFICATE-----`,
`-----BEGIN PGP PUBLIC KEY BLOCK-----`, etc. headers, those fixtures
fully match their sidecars.

Other red per-fixture tests stay red because their sidecars also
demand `purls.mustContain` (Phase 3+ work) or `metadata.mustContain`
keys like `Certificates:KeyAlgorithm` (Phase 3+ work). Phase 2 was
not designed to flip those — only the MIME-only assertions move
green here.
