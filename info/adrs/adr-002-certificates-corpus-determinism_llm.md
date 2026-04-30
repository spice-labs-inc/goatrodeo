# ADR-002 (LLM): Certificates corpus determinism

Parallel copy of [`adr-002-certificates-corpus-determinism.md`](adr-002-certificates-corpus-determinism.md).

**Status:** Accepted · **Date:** 2026-04-24

## Decision (one sentence)

Synthetic fixture bytes are one-time-canonical; re-running bootstrap
scripts regenerates bytes + sidecars atomically; ground-truth
cross-check (JDK vs Python `cryptography`) is how drift is caught,
not byte-exact reproducibility.

## Why

`ssh-keygen`, `gpg --gen-key`, `openssl genpkey`, Python
`cryptography.generate()` — none accept pinned seeds without heavy
custom wrapping. Cost-benefit of deep seeding is negative at Phase 0
quality of service.

## Plan deviation

The phase-0-corpus plan said: _"If the generation requires randomness,
the script sets a pinned seed."_ This ADR formalizes the deviation.

## Safety net

`CertificatesSidecarGroundTruthTests` re-parses every X.509 fixture
with the JDK parser and checks `CertSha256` / `SpkiSha256` / `SubjectDN`
against the committed sidecar. Catches sidecar-vs-fixture drift in both
directions.

## Contributor workflow

- Add: generate bytes → compute sidecar → commit both + SOURCES.md row.
- Regenerate: re-run bootstrap → commit all resulting changes atomically.
- Remove: drop fixture, sidecar, and SOURCES.md row in one commit.

## Rejected alternatives

- Pin seeds everywhere — too costly.
- Rewrite tools in pure Python — HS-1 scale substitution, no benefit.
- Commit entropy blobs — doubles volume, doesn't help.
- Real-world downloads only — misses cross-algorithm / empty-CRL / KDF
  coverage needed for Phase 3-7.

## Pointers

- Bootstrap: `test_data/certificates/tools/bootstrap_*.py`
- Sidecar compute: `tools/cert_sidecar.py`
- Ground truth: `src/test/scala/strategies/CertificatesSidecarGroundTruthTests.scala`
- Parent ADR: `adr-001-certificates-phase-0-harness.md`
