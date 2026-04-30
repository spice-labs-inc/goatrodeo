# ADR-001 (LLM): Certificates Phase 0 harness

Parallel copy of [`adr-001-certificates-phase-0-harness.md`](adr-001-certificates-phase-0-harness.md).

**Status:** Accepted · **Date:** 2026-04-24

## Decisions (quick table)

| ID | Decision |
|---|---|
| D1 | Sidecar JSON alongside each fixture; schema in appendices.md Appendix B |
| D2 | Metadata key separator is `:` everywhere (matches `MKC.adHoc`) |
| D3 | Keystores/bundles/CRLs produce 1 Item each; no child Items |
| D4 | Encrypted material: null-password only; no decryption, no guessing; envelope metadata only |
| D5 | Tools (openssl/ssh-keygen/gpg/jq) run in Docker per invariant #13 |
| D6 | Phase 0 split (HS-1): 0a infrastructure done; 0b corpus population deferred |

## Trade-offs

- Sidecars decouple expected values from the engine — catches
  implementation bugs.
- Unit tests cover parser/inventory/assertions directly — hostile
  reviewer can challenge each with a named test.
- Until 0b lands, `CertificatesCorpusIntegritySuite.corpus contains at
  least 200 fixtures` stays red (intentional, red-to-green).

## Rejected alternatives

- **A1 Generate expected in-engine** — self-referential, catches no bugs.
- **A2 Inline test vectors** — HS-4 forbids; use real byte streams.
- **A3 Lock-in via strategy output only** — bootstrap placeholder
  `<computed>` tokens allowed, manual verification required.
- **A4 Skip Docker** — invariant #13 violation without an ADR; no
  motivation to override.
- **A5 Download 200 fixtures in one session** — HS-1: bigger than a
  session reliably supports.

## Follow-ups

1. Phase 0b — populate corpus to 200+ paired fixtures.
2. Tool round-trip smoke test once first real X.509 fixtures land.
3. `.gitattributes` LFS config for binary fixture types.

## Pointers

- Plan index: `certificates-strategy-plan.md`
- Phase-0 plan: `certificates-strategy/phase-0-corpus.md`
- Claim table: `info/certificates/phase-0-claims.md`
- Schema: `certificates-strategy/appendices.md` Appendix B
- Leak-guard patterns: `certificates-strategy/appendices.md` Appendix C
