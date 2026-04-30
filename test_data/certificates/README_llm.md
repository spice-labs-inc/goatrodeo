# Certificates Fixture Corpus — LLM-friendly reference

Parallel copy of [`README.md`](README.md) organized for quick LLM ingestion.

## What lives here

Path: `test_data/certificates/`
Purpose: ground-truth test corpus for the Certificates strategy.
Minimum size: 200 paired `(fixture, sidecar)` pairs.
Enforcement: `CertificatesCorpusIntegritySuite` in
`src/test/scala/strategies/`.

## Pairing rule

`foo.pem` ↔ `foo.pem.expected.json` in the same directory.
`openssh-ed25519-unencrypted` ↔ `openssh-ed25519-unencrypted.expected.json`.

## Category subdirectories

| Dir | Plan phase | Artifact types |
|---|---|---|
| `x509/` | 3 | PEM/DER X.509 certs |
| `keystores/` | 4 | JKS, JCEKS, PKCS#12, BKS |
| `pem-bundles/` | 4 | multi-PEM bundles |
| `crls/` | 4 | PEM/DER CRLs |
| `ssh/` | 5 | OpenSSH pubkeys + CA certs |
| `pgp/` | 6 | armored + binary PGP keys |
| `private-keys/` | 7 | unencrypted + encrypted |
| `edge-cases/` | 0 | truncated/malformed/probes |

## Infrastructure files (not fixtures)

- `README.md`, `README_llm.md` — this doc
- per-category `SOURCES.md` — provenance ledger (URL, date, SHA-256)
- per-category `generate.sh` — optional reproducible synthesizer
- `tools/` — corpus-authoring utilities (Docker-wrapped)
- `.gitkeep` — empty-dir markers

## Sidecar schema

See `certificates-strategy/appendices.md` Appendix B.
Required fields: `description`, `source`, `retrievedAt`, `itemCount`,
`mimeTypes.mustContain`, `purls.mustContain`, `metadata.mustContain`,
`forbiddenMetadataPatterns`.
Metadata keys use `:` separator
(Hard rule #6 in `certificates-strategy-plan.md`).

## Private-key policy

No production private keys. Test private keys carry:
`# GOAT RODEO TEST KEY - NOT A SECRET - DO NOT USE ANYWHERE ELSE`.
Leak guard: `CertificatesAssertions.assertNoForbiddenPatterns`.
Forbidden patterns: `certificates-strategy/appendices.md` Appendix C.

## Claim → test traceability

| Claim | Test |
|---|---|
| Corpus ≥ 200 pairs | `CertificatesCorpusIntegritySuite.corpus contains at least 200 fixtures` |
| No orphan sidecars | `CertificatesCorpusIntegritySuite.no orphan sidecars` |
| No orphan fixtures | `CertificatesCorpusIntegritySuite.no orphan fixtures` |
| Every sidecar structurally valid | `CertificatesCorpusIntegritySuite.every sidecar parses and declares required fields` |
| Pipeline output matches sidecar | `CertificatesSuite` (parameterized — one test per pair) |

## Current Phase 0 state

Infrastructure: implemented.
Corpus population: deferred to follow-up (HS-1 approved by maintainer).
Until populated, `corpus contains at least 200 fixtures` intentionally
fails red.
