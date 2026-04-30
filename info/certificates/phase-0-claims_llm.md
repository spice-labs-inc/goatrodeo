# Phase 0 — Claims (LLM-friendly)

Parallel copy of [`phase-0-claims.md`](phase-0-claims.md).

## Scope

Phase 0 split (HS-1 approved): 0a = infrastructure, 0b = corpus population.
**Both complete + PQC additions.** Corpus contains 222 paired fixtures.

## File inventory

### Test-side Scala sources

| File | Role |
|---|---|
| `src/test/scala/strategies/CertificatesSidecar.scala` | Sidecar data model + JSON parser |
| `src/test/scala/strategies/CertificatesFixtureInventory.scala` | Class + singleton for pairing + orphan detection |
| `src/test/scala/strategies/CertificatesAssertions.scala` | Assertion helpers reused across suites |
| `src/test/scala/strategies/CertificatesPipelineRunner.scala` | `runGoatRodeoOnSingleFile` — runs pipeline on one artifact |
| `src/test/scala/strategies/CertificatesSuite.scala` | Per-fixture parameterized suite |
| `src/test/scala/strategies/CertificatesCorpusIntegritySuite.scala` | Corpus-shape integrity (200 floor, orphans, parse-validity) |
| `src/test/scala/strategies/CertificatesSidecarTests.scala` | Unit tests for the sidecar parser |
| `src/test/scala/strategies/CertificatesFixtureInventoryTests.scala` | Unit tests for pairing + orphan logic |
| `src/test/scala/strategies/CertificatesAssertionsTests.scala` | Unit tests for assertion helpers |

### Fixture corpus layout

| Path | Role |
|---|---|
| `test_data/certificates/README.md` | Corpus README (human) |
| `test_data/certificates/README_llm.md` | Corpus README (LLM) |
| `test_data/certificates/{category}/SOURCES.md` | Per-category provenance ledger |
| `test_data/certificates/{category}/.gitkeep` | Empty-dir marker |
| `test_data/certificates/tools/Dockerfile` | Invariant-#13 Docker wrapper for tools |
| `test_data/certificates/tools/compute-expected.sh` | Draft-sidecar emitter |
| `test_data/certificates/tools/README.md` | Tools usage doc |

Categories: `x509`, `keystores`, `pem-bundles`, `crls`, `ssh`, `pgp`,
`private-keys`, `edge-cases`.

## Test counts (post-Phase-0b + remediation)

| Suite | Count |
|---|---|
| CertificatesSidecarTests | 12 green |
| CertificatesFixtureInventoryTests | 11 green |
| CertificatesAssertionsTests | 27 green (+5 from leak-pattern extension) |
| CertificatesPipelineRunnerTests | 7 green (**new**) |
| CertificatesSidecarGroundTruthTests | 4 green (**new**) |
| CertificatesCorpusIntegritySuite | 5 green |
| CertificatesSuite | 222 skipped (pending until `Certificates` strategy class exists) |
| **Total** | **66 green infrastructure + 222 uniformly pending** |

**No partial-mix.** All per-fixture tests are in the same state.

## Corpus breakdown

| Category | Count |
|---|---|
| x509/mozilla | 145 |
| x509/canonical | 6 |
| x509/synthetic | 6 |
| x509/pqc/ml-dsa | 6 |
| x509/pqc/slh-dsa | 8 |
| x509/pqc/falcon | 2 |
| x509/pqc/composite | 5 |
| pem-bundles | 2 |
| keystores/synthetic | 4 (incl. trust-only-null-password) |
| crls/synthetic | 4 |
| ssh/synthetic | 6 |
| ssh/github | 7 |
| pgp/synthetic | 2 |
| private-keys/synthetic | 8 |
| edge-cases | 10 |
| **Total** | **221** ((+1 for the Mozilla bundle's own sidecar = 222 paired)) |

Actual paired-pair count: 222 as measured by `CertificatesFixtureInventory.totalCount`.

## PQC additions (2026-04-28)

21 trust-anchor certs from IETF Hackathon BC r5:
- ml-dsa: ML-DSA-44/65/87 pure + each pre-hash-sha512 variant
- slh-dsa: SHA-2 × {128,192,256} × {f,s} (all six) + SHAKE-128s + SHAKE-256f
- falcon: Falcon-512, Falcon-1024
- composite: ML-DSA hybrids — mldsa44+rsa2048-pss, mldsa44+ed25519, mldsa44+ecdsa-p256, mldsa65+rsa3072-pss, mldsa87+ecdsa-p384

Sidecar SPKI hashes computed via hand-walked DER navigator
(`cert_sidecar.extract_spki_der`) since `cryptography` v41 cannot
construct PQC PublicKey objects. Independently verified by JDK 21's
permissive X509 parser through `CertificatesSidecarGroundTruthTests`.

## Bootstrap tooling

| Script | Purpose |
|---|---|
| `tools/cert_sidecar.py` | Core sidecar-computation library for X.509 |
| `tools/bootstrap_mozilla.py` | Fan out Mozilla bundle → ~145 x509 fixtures + 1 bundle |
| `tools/bootstrap_canonical_roots.py` | Download 6 pinned real-world roots |
| `tools/bootstrap_synthetic.py` | Generate synthetic SSH, PGP, keystores, CRLs, private keys, edge cases |
| `tools/compute-expected.sh` | Per-fixture draft-sidecar shell tool (reviewer workflow) |

All scripts are idempotent and re-runnable. Docker wrapper ships at `tools/Dockerfile`.

## Claim-to-test map

See `phase-0-claims.md` — 40 claims, each with a test name. All 39
non-Phase-0b claims verified green; claim #40 (200+ fixtures) is the
single intentional red.

## Invariant compliance

| Invariant | Compliance |
|---|---|
| #1 test-first, TDD | 45 unit tests cover helpers before any strategy code exists |
| #3 test ↔ requirement traceability | `phase-0-claims.md` table |
| #6 unimplemented code fails loud | N/A at Phase 0 (no strategy code yet) |
| #8 pessimistic assessment | HS-3 self-check in claims doc |
| #9 ≤20k tokens per doc | All Phase 0 docs well under — confirmed |
| #11 end-of-phase adversarial review | ADR `info/adrs/adr-001-certificates-phase-0-harness.md` plus HS-3 self-check |
| #12 human + LLM docs + claim-test refs | Both README copies, both claims copies, ADR with both sections |
| #13 Docker-wrap scripts needing more than git/docker/JVM | `tools/Dockerfile` wraps `compute-expected.sh`; README instructs `--user "$(id -u):$(id -g)"` |
| #14 ADRs under `info/adrs/` | ADR-001 created |

## HS-2 exit-review state

| Step | Status |
|---|---|
| 1 Gap Review | See ADR and README; corpus-population gap explicit |
| 2 Claims Verification | `phase-0-claims.md` claim table; tests executed |
| 3 Hostile Reviewer | HS-3 self-check in claims doc |
| 4 Full Suite Regression | Run `sbt test` — see tail of session transcript |
