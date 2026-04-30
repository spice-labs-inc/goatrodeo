# Phase 1 — Claims (LLM-friendly)

Parallel copy of [`phase-1-claims.md`](phase-1-claims.md).

## State

Phase 1 done. Foundation + wiring landed. Strategy registered in both
`dynamicToProcess` slots; `CryptoDetector` registered in
`ArtifactWrapper`. No behavior change.

## Files (Phase 1)

| File | Change |
|---|---|
| `build.sbt` | `-release 17 → 21`; +bcprov/bcpkix/bcpg/bcutil 1.80 |
| `src/main/scala/io/spicelabs/goatrodeo/util/CryptoDetector.scala` | new — pass-through stub |
| `src/main/scala/io/spicelabs/goatrodeo/util/ArtifactWrapper.scala` (line ~230) | register `CryptoDetector` after `Saffron` |
| `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala` | new — Annatto-shaped skeleton |
| `src/main/scala/io/spicelabs/goatrodeo/omnibor/ToProcess.scala` (lines 512–521 + 552–563) | register `Certificates.computeCertificateFiles` in BOTH places |
| `info/certificates_strategy.md` + `_llm` | new public docs |

## Test-state choice

Plan permits "passes" OR "fail loudly". This phase chose **fail
loudly** — `CertificatesSuite` per-fixture tests run live now that
the `Class.forName(...Certificates)` gate flips. Most fail red until
Phase 2/3+ implements claim logic. A handful of edge-case fixtures
pass trivially because their sidecars are no-claim contracts.

## Phase 1 guarantees

1. `sbt compile` clean.
2. Phase 0 infrastructure tests stay green.
3. `Certificates` class resolves at runtime.
4. Strategy registered in dispatch chain (constructor + reset).
5. `CryptoDetector` registered after `Saffron`.

## Phase 2 next (separate exec)

Content sniffing for PEM/DER/SSH/PGP/JKS/etc. headers per the
detection-signatures table in `certificates-strategy/phases-1-2-
foundation-detector.md`.
