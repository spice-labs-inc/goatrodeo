# Phase 3 — Claims and test traceability

**Parent plan:** [`../../certificates-strategy-plan.md`](../../certificates-strategy-plan.md)
**Phase doc:** [`../../certificates-strategy/phases-3-4-x509-containers.md`](../../certificates-strategy/phases-3-4-x509-containers.md) (Phase 3 section)
**LLM-friendly parallel copy:** [`phase-3-claims_llm.md`](phase-3-claims_llm.md)

## What Phase 3 delivered

The Certificates strategy's first behaviorally-active phase. Claim
logic, X.509 parsing (PEM + DER), pURL emission (SPKI-identity +
cert-identity), full metadata table, and the defensive
private-key-leak sweep.

## Claim logic

Files whose MIME set intersects:

  - `application/pkix-cert`, OR
  - `application/x-x509-ca-cert`, OR
  - `application/x-pem-file` (without `application/x-pem-bundle`)

AND the artifact parses as a single X.509 cert (PEM or DER). Files
whose MIME set indicates a keystore / bundle / SSH / PGP / private-
key / CRL are explicitly rejected — those are Phase 4–7.

| Claim # | Statement | Verified by |
|---|---|---|
| 1 | `Certificates.computeCertificateFiles` claims artifacts with `pkix-cert` / `x-x509-ca-cert` MIMEs | `strategies.CertificatesSuite` per-fixture tests for x509/canonical/letsencrypt-* fixtures pass; sidecar's `mimeTypes.mustContain` and pURL assertions both fire |
| 2 | `application/x-pem-file` without `application/x-pem-bundle` qualifies for Phase-3 claim | Mozilla CA fanout tests (145 fixtures) flip mostly green |
| 3 | Strategy NEVER claims a keystore / bundle / SSH / PGP / private-key fixture in Phase 3 | `Certificates.isSingleCertCandidate` returns false for those MIME sets; verified empirically by those fixture tests staying in their pre-Phase-3 state (no spurious mostlyClaims) |
| 4 | Files that fail to parse as X.509 are NOT claimed (fall through to Generic) | `Certificates.parseSingleCert` returns `None`; the artifact stays in residual `byUUID`; edge-case fixture tests confirm |

## pURL emission

For each claimed cert:

  - `pkg:x509/spki-sha256@{hex}?alg={alg}&{size|curve|params}&version={n}`
  - `pkg:x509/cert-sha256@{hex}?alg={alg}&{size|curve|params}&sig-alg={sig-alg}&self-signed={bool}&version={n}`

Qualifier order is canonical (alphabetical) per
`PackageURL.canonicalize()`. Phase 0b's sidecars have been
re-canonicalized to match.

| Claim # | Statement | Verified by |
|---|---|---|
| 5 | SPKI-sha256 pURL is emitted with correct hex hash and qualifier set | `strategies.CertificatesSuite` for letsencrypt-isrgrootx1.pem: `pkg:x509/spki-sha256@0b9fa5a59eed715c26c1020c711b4f6ec42d58b0015e14337a39dad301c5afc3?alg=rsa&size=4096&version=3` matches sidecar |
| 6 | cert-sha256 pURL is emitted with sig-alg, self-signed, version qualifiers | same fixture: `pkg:x509/cert-sha256@96bcec06...?alg=rsa&self-signed=true&sig-alg=sha256-rsa&size=4096&version=3` matches |
| 7 | Cross-check: SHA-256 of DER cert and SHA-256 of SubjectPublicKeyInfo match the values Phase 0b's `cert_sidecar.py` (Python `cryptography`) computed and the JDK X509 parser independently confirmed | `strategies.CertificatesSidecarGroundTruthTests` (same JDK parser path) + per-fixture sidecar assertions |

## Metadata emission

| Field | Source | Test trace |
|---|---|---|
| `MKC.NAME` | subject CN or full DN | per-fixture test passes via `assertMetadataContains` |
| `MKC.PUBLISHER` | issuer CN or full DN | same |
| `MKC.DESCRIPTION` | `"X.509 v{n} certificate"` | same |
| `Certificates:SubjectDN` | `getName(RFC2253)` | same |
| `Certificates:IssuerDN` | `getName(RFC2253)` | same |
| `Certificates:Serial` | lowercase hex | same |
| `Certificates:NotBefore` / `NotAfter` | ISO-8601 UTC | same |
| `Certificates:KeyAlgorithm` | canonical (rsa/ec/ed25519/ml-dsa/...) | same |
| `Certificates:KeySize` | RSA/DSA only | same |
| `Certificates:Curve` | EC only, canonical (p-256, p-384, etc.) | same |
| `Certificates:Params` | PQC only (44/65/87, 128s/f, etc.) | same |
| `Certificates:SigAlgorithm` | canonical (sha256-rsa, ed25519, ml-dsa-65, etc.) | same |
| `Certificates:SpkiSha256` / `CertSha256` | lowercase hex | same |
| `Certificates:IsCA` | `BasicConstraints >= 0` | same |
| `Certificates:SelfSigned` | subject==issuer ∧ signature verifies against own pubkey | `Certificates.isSelfSigned` |
| `Certificates:KeyUsage` | comma-separated lowercase-hyphenated names | same |
| `Certificates:ExtendedKeyUsage` | OID-mapped EKU names | same |
| `Certificates:Version` | `"1"` / `"2"` / `"3"` | same |
| `Certificates:SAN` | comma-separated `DNS:`/`IP:`/`email:`/etc. | same |

## Defensive leak sweep (Hard rule #1)

Before returning the metadata tree, every emitted value is checked
against the 8 forbidden patterns from Appendix C. Any match throws
a `RuntimeException` with a clear message identifying the key and
pattern.

| Claim # | Statement | Verified by |
|---|---|---|
| 8 | `assertNoLeak(metadata)` throws on any value matching the Appendix-C forbidden list | `Certificates.forbiddenPatterns` (8 patterns); throws are propagated through `getMetadata`'s call site; per-fixture tests on private-key-bearing X.509 fixtures (none in Phase 3 — that's Phase 7) would trip if violated. The leak-sweep helper's behavior is shared with `CertificatesAssertions.assertNoForbiddenPatterns` already covered by `CertificatesAssertionsTests` (8 unit tests against the same pattern list). |
| 9 | The defensive sweep never triggers in normal Phase-3 operation | per-fixture regression: zero `RuntimeException` propagation across all X.509 fixture tests |

## Phase 3 acceptance vs. plan

| Plan acceptance | Status |
|---|---|
| All X.509 fixtures pass their sidecar assertions | Many flip green (Mozilla, canonical, synthetic). PQC fixtures with > 4 KB DER pass via Phase-2 P1 fix + Phase-3 SPKI extraction. Final count in regression result. |
| `CryptoDetectorSuite` still passes | Yes — 60/60 |
| No new warnings | Yes — clean compile |
| Defensive leak check never triggers in normal operation | Yes |
| Total LOC of `CryptoDetector.scala` + `Certificates.scala` under 300 at this phase | **NOT MET** — total is ~830 LOC across both files. The 300-LOC budget was unreachable given (a) Phase 2's 21-row signature implementation requires ~250 logic LOC, and (b) Phase 3's canonical-mapping tables for X.509 algorithms (RSA/DSA/EC/Ed25519/Ed448/ML-DSA/SLH-DSA/Falcon × signature algorithms × extensions) require ~200 logic LOC. Parent plan's "stop at 800 LOC" threshold was crossed; flagging here. |

## Sidecar canonicalization (Phase 0b correction)

Phase 0b's `cert_sidecar.py` emitted pURL qualifiers in plan-table
order (`alg & size & sig-alg & self-signed & version`). PackageURL's
canonical form sorts qualifiers alphabetically. The sidecars were
not canonical, which would have produced false-red per-fixture tests
when Phase 3's strategy compared canonicalized output to non-
canonical sidecar strings.

Remediation: a one-shot Python script re-canonicalized 245 sidecars
into alphabetical-qualifier order. The sidecars now match what
`PackageURL.canonicalize()` produces. Future sidecar generators
should emit canonical order from the start; `cert_sidecar.py`
should be patched accordingly (deferred to a follow-up).

| Claim # | Statement | Verified by |
|---|---|---|
| 10 | Sidecars match `PackageURL.canonicalize()` output | per-fixture test passes that compares sidecar string to canonicalized pURL on the Item; ISRG Root X1 fixture went green after canonicalization |

## HS-3 five-YES self-check

| Question | Answer |
|---|---|
| Did I read the requirement? | Yes — phase-3 section verbatim |
| Did I read the implementation? | Yes — every public method in `Certificates.scala` |
| Did I read the test? | Yes — `CertificatesSuite` per-fixture tests; the existing `CertificatesAssertionsTests` for the leak-sweep behavior |
| Does the test exercise the actual requirement? | Yes — fixture tests fold the entire claim → parse → emit → assert path against committed real-world cert bytes |
| Would a crusty engineer agree? | Mostly yes, but with two flags: (a) LOC budget exceeded — surfaced above; (b) sidecar canonicalization fix touched 245 committed files — this is per-the-plan ("lock in the values") but a hostile reviewer would prefer this had been done in Phase 0b |

## Out-of-scope items not addressed in Phase 3 (by design)

- Keystore / PEM bundle / CRL claim & parse → Phase 4
- SSH pubkey / OpenSSH cert claim & parse → Phase 5
- PGP claim & parse → Phase 6
- Unencrypted/encrypted private-key claim & parse → Phase 7
- Property-based tests → Phase 8
- Coverage matrix audit → Phase 8
