# Certificates strategy (LLM)

Parallel copy of [`certificates_strategy.md`](certificates_strategy.md).

## State

Phases 1-5 done. Strategy claims X.509 single-cert (PEM/DER), PEM bundles,
JKS/JCEKS/PKCS12/BKS keystores, X.509 CRLs (PEM/DER), and OpenSSH plain
pubkeys + CA-issued certificates. Phase 6+ adds PGP (Phase 6), private
keys (Phase 7).

## Files (Phases 1-4)

| Path | Role |
|---|---|
| `src/main/scala/io/spicelabs/goatrodeo/util/CryptoDetector.scala` | MIME augmenter (Phase 2 — 21 detection signatures, 1 MB DER probe) |
| `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala` | Strategy with sealed `ClaimedContent` ADT (`SingleCert/Bundle/Keystore/Crl`); per-variant emitters (Phase 3-4) |
| `src/test/scala/strategies/MaterializePhase4Sidecars.scala` | One-shot helper for filling Phase-4 sidecar pURL placeholders |
| `src/main/scala/io/spicelabs/goatrodeo/util/ArtifactWrapper.scala` (line ~230) | CryptoDetector registered after Saffron |
| `src/main/scala/io/spicelabs/goatrodeo/omnibor/ToProcess.scala` (lines 512-521 + 552-563) | Certificates registered in dynamicToProcess + resetComputeToProcess |
| `build.sbt` | -release 21; bcprov/bcpkix/bcpg/bcutil 1.80 |
| `info/certificates_strategy.md` | Public-facing doc (this file's parent) |

## Hard rules (enforced from Phase 3 onward)

1. Never emit private-key material in Items / metadata / logs.
2. Keystores → 1 Item, no child Items, no contains/containedBy edges.
3. No pURL for keystore containers or encrypted private keys.
4. No new EdgeType values.
5. Qualifier values: lowercase, hyphens (not underscores).
6. Ad-hoc metadata keys use `:` separator at every level.

## Pointers

- Plan: `../certificates-strategy-plan.md`
- Phase docs: `../certificates-strategy/phases-{1-2,3-4,5-7,8-9}-*.md`
- Appendices: `../certificates-strategy/appendices.md`
- ADR-001 (harness): `adrs/adr-001-certificates-phase-0-harness.md`
- ADR-002 (determinism): `adrs/adr-002-certificates-corpus-determinism.md`
