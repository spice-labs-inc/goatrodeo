# ADR-001: Certificates strategy Phase 0 — harness design

**Status:** Accepted
**Date:** 2026-04-24
**Decider:** dpp@spicelabs.io (maintainer) via session transcript approval
**Parallel LLM copy:** [`adr-001-certificates-phase-0-harness_llm.md`](adr-001-certificates-phase-0-harness_llm.md)

## Context

The Certificates strategy plan (see `certificates-strategy-plan.md`)
mandates test-first, red-to-green discipline (CLAUDE.md invariant #1) and
requires Phase 0 to ship the test corpus and harness before any
production code is written. Phase 0 calls for ≥200 real-world fixtures,
each paired with a ground-truth sidecar JSON.

Two shaping decisions had to be made at Phase 0 entry:

1. How should the harness represent "expected pipeline output" for each
   fixture?
2. How should we integrate ~200 real-world fixtures into CI when many
   require cryptographic tooling (openssl, ssh-keygen, gpg) not normally
   present in the minimal Scala/sbt/JDK build environment?

## Decision

### D1 — Sidecar JSON as the ground-truth format

Every fixture file `foo.ext` is paired with a sibling
`foo.ext.expected.json`. The sidecar declares:

- provenance (`description`, `source`, `retrievedAt`)
- exact item count (`itemCount`)
- subset / absence assertions on MIME types (`mimeTypes.mustContain` /
  `mustNotContain`)
- subset / absence assertions on pURLs (`purls.mustContain` /
  `mustNotContain`)
- subset assertions on metadata (`metadata.mustContain`) plus integer
  ranges (`metadata.mustContainRanges`) for noisy values such as large
  keystore entry counts
- forbidden metadata keys (`forbiddenMetadataKeys`) and the
  private-key leak-guard regex list (`forbiddenMetadataPatterns`)

The full schema is in
`certificates-strategy/appendices.md` Appendix B.

### D2 — Metadata key separator is `:`

All keys inside `metadata.mustContain` use `:` as the separator at every
level of nesting, matching what `MKC.adHoc(prefix)(subkey)` produces at
runtime. This is consistent with the existing `Annatto:Ecosystem`,
`Baharat:Arch`, etc. conventions already shipped by the engine.

### D3 — Keystores produce a single Item (no child Items)

Keystores, PEM bundles, and CRLs emit exactly one Item each, with all
contained pURLs and metadata attached flat. No `contains` /
`containedBy` edges, no `FileWalker` modifications. This preserves the
"no new graph topology" constraint and keeps the Certificates work
focused on inventory queries rather than chain resolution.

### D4 — Encrypted private keys and keystores stay opaque

The strategy tries `null` password only. Anything that fails the null-
password load is treated as encrypted: envelope metadata only, no pURL,
no SPKI derivation, no decryption attempts, no password guessing of any
kind. The tooling and harness enforce this contract via the forbidden-
pattern leak guard and the absence-of-pURL sidecar assertion for
encrypted fixtures.

### D5 — Corpus tooling runs in Docker

Per CLAUDE.md invariant #13, `compute-expected.sh` requires openssl /
ssh-keygen / gpg / jq — tools beyond the baseline (git, docker, JVM).
The tool ships with a `Dockerfile` pinning a Debian-bookworm base plus
those tools. Usage instructions specify `--user "$(id -u):$(id -g)"` so
generated sidecars are owned by the invoker, not root.

### D6 — Phase 0 split (HS-1)

Phase 0 was split, with maintainer approval, into:

- **0a — infrastructure:** harness, schema, tools, documentation,
  directory scaffold. Shipped.
- **0b — corpus population:** 200+ real-world fixtures with ground-
  truth sidecars. Shipped. Corpus contains 201 paired fixtures as of
  2026-04-24.

### D7 — Per-fixture tests are uniformly pending until the strategy
class exists

`CertificatesSuite` detects the presence of the
`io.spicelabs.goatrodeo.omnibor.strategies.Certificates` class via
`Class.forName`. If the class is absent (pre-Phase-1 state), every
per-fixture test is marked `.ignore` with a uniform "pending Phase 1+"
semantic. This resolves the plan's "no partial mix" requirement — all
per-fixture tests are in the same state at any given time. When
Phase 1 wires the strategy skeleton, the detection flips and the tests
become live; Phase 3+ then drives them from red to green as each
capability lands.

### D8 — Independent ground-truth cross-check via JDK X509 parser

`CertificatesSidecarGroundTruthTests` re-parses every X.509 fixture
with the JDK's built-in `java.security.cert.CertificateFactory`
(completely separate from the Python `cryptography` library that
authored the sidecars). It verifies `Certificates:CertSha256`,
`Certificates:SpkiSha256`, and `Certificates:SubjectDN` against the
JDK's own rendering. A hostile reviewer challenging "are the committed
sidecars actually correct?" can read this test and the assertion count
of 157+ X.509 fixtures checked, rather than relying on a single n=1
cross-check done at bootstrap time.

## Consequences

### Positive

- **Decoupling.** Sidecars are ground truth; a buggy strategy
  implementation cannot accidentally pass because the expected values
  are computed by external tools (openssl, ssh-keygen) not the engine
  under test.
- **Extensibility.** Adding a new fixture is a two-file operation
  (`foo.ext` + `foo.ext.expected.json`) that the harness auto-
  discovers. No harness changes required for corpus growth.
- **Hostile-reviewer resilience.** Unit tests cover the sidecar
  parser, fixture inventory, and assertion helpers directly. A
  reviewer challenging "does the pairing logic actually pair?" has a
  named test to read.
- **Deferrable corpus population.** The 200-fixture download can
  proceed in a follow-up without blocking infrastructure review.

### Negative

- **Sidecar authoring cost.** Each fixture requires manual sidecar
  authoring with external verification. `compute-expected.sh` reduces
  this to an edit-and-verify workflow but does not eliminate it. For
  bulk X.509 bootstrap the authoring happens in
  `tools/cert_sidecar.py` + `tools/bootstrap_mozilla.py`.
- **Git LFS dependency.** Binary fixtures (keystores, DER certs, CRLs)
  flow through LFS. Contributors without LFS configured hit the sbt
  pre-test LFS-presence check. Accepted trade-off; LFS is already in
  use elsewhere in the project.
- **Determinism gap** — see [ADR-002](adr-002-certificates-corpus-determinism.md).
  Synthetic fixture generation uses live entropy
  (`ssh-keygen`, `gpg --gen-key`, `openssl genpkey`, cryptography lib
  `generate()` calls). Bytes are one-time-canonical; re-running
  bootstrap regenerates both bytes and sidecars atomically. Deviates
  from the plan's "pin a seed" directive; see ADR-002 for reasoning.

### Neutral

- **Sidecar format is JSON, not TOML / YAML.** JSON parse is already a
  project dependency (`json4s`). Adding another format would create a
  gratuitous choice point with no benefit.

## Alternatives considered

### A1 — Generate expected values in-strategy at test time

Rejected. Ground truth generated by the same code under test cannot
catch the code's own bugs — a bug that produces output X makes "expected
X" match trivially. The external-tool approach catches this.

### A2 — Use embedded test vectors instead of on-disk fixtures

Rejected (HS-4). "Test corpus means the actual test corpus" — the
Certificates strategy must be exercised against the actual DER, PEM,
keystore, and armored-PGP byte streams seen in the wild, not synthetic
snippets inlined into Scala strings.

### A3 — Build sidecars purely via the strategy's own eventual output

Rejected for ground-truth fields; accepted for bootstrap-only pURL
hashes where pre-computing via external tool is impractical. The plan
allows this via `"<computed>"` placeholders, locked in after manual
verification.

### A4 — Skip Docker for tooling

Rejected. Invariant #13 requires Docker for tools beyond git/docker/
JVM. Compliance is the default, and there is no compelling reason to
override here (would require an ADR of its own).

### A5 — Download the 200+ fixtures inside this session

Rejected under HS-1: download and hand-sidecar of 200 fixtures from ~15
different origins with SHA-256 verification is outside what one session
can reliably produce. Maintainer approved the infrastructure-first
split.

## Follow-ups

1. ~~**Phase 0b:** populate `test_data/certificates/` to ≥200 paired
   fixtures.~~ **Done** (201 fixtures as of 2026-04-24).
2. ~~**Tool round-trip / ground-truth cross-check:**~~ **Done** via
   `CertificatesSidecarGroundTruthTests` — JDK X509 parser vs Python
   `cryptography` across all X.509 fixtures.
3. ~~**LFS tracking config:**~~ **Done** — `test_data/certificates/
   .gitattributes` tracks `*.der`, `*.jks`, `*.jceks`, `*.bks`,
   `*.p12`, `*.pfx`, `*.gpg`, `*.pgp`, `*.bin`, `*.crl`.

Remaining follow-ups (Phase 3+ or later):

4. **Coverage-matrix gaps** for Phase 8's `CertificatesCoverageSuite`:
   - ~~PQC fixtures (ml-dsa / slh-dsa / falcon / composite)~~
     **Done** via `tools/bootstrap_pqc.py` against the IETF
     Hackathon BC r5 artifact set; 21 PQC trust-anchor certs across
     4 families (`ml-dsa`, `slh-dsa`, `falcon`, `composite`) now in
     `test_data/certificates/x509/pqc/`.
   - ~~PGP v6 keys~~ **Done** via `tools/GeneratePgpV6.java` using
     BC 1.80's `OpenPGPV6KeyGenerator`. Fixture at
     `test_data/certificates/pgp/synthetic/v6-ed25519-pub.asc`
     (Ed25519 primary + X25519 + Ed25519 subkeys, version=6 verified
     by BC).
   - ~~BKS keystores~~ **Done** via `tools/GenerateBks.java` using
     BC 1.79 provider. Fixture at
     `test_data/certificates/keystores/synthetic/trust-bks.bks`.
   - ML-KEM (post-quantum KEM, key-only — encryption, not signing)
     deferred to Phase 7 private-key path.
5. **Deterministic synthetic fixtures** (if adopted) — see ADR-002.
   Would let us re-run bootstrap in CI to verify fixture integrity
   without relying on committed bytes.
6. **Invariant #10 review prompts** against the plan — QA engineer,
   principal engineer, red-team security reviews. Not done during
   Phase 0 plan authorship; to be run before Phase 3 begins.
