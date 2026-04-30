# Certificates strategy

**Status:** under active development. Phase 1 (foundation + wiring)
landed; the strategy claims nothing yet. Phase 3 onward fills in
the real claim/parse/emit logic.

**Plan:** [`../certificates-strategy-plan.md`](../certificates-strategy-plan.md)
and the per-phase files under
[`../certificates-strategy/`](../certificates-strategy/).

LLM-friendly parallel copy: [`certificates_strategy_llm.md`](certificates_strategy_llm.md).

## What it will handle

The strategy will inspect files containing cryptographic material and
emit pURLs and metadata suitable for cryptographic inventory and
post-quantum-crypto (PQC) readiness queries through BigTent:

- X.509 certificates (PEM and DER)
- X.509 Certificate Revocation Lists (CRLs), PEM and DER
- Java keystores: JKS, JCEKS, PKCS#12, BKS
- PEM bundles (multiple concatenated PEM blocks)
- SSH public keys (OpenSSH wire format)
- OpenSSH CA-issued certificates (user and host certs)
- PGP public keys (armored `.asc` and binary) — v4 and v6 (incl.
  subkeys); v5 deferred (no fixture in current corpus, no contributor
  request observed)
- Private keys — unencrypted: parse, derive public key, emit full
  pURL and metadata (never the private material itself)
- Private keys — encrypted (PKCS#8 encrypted, OpenSSH encrypted,
  legacy PEM encrypted, PGP encrypted secret keys): envelope metadata
  only, no decryption, no password guessing

## Hard rules

1. **Never emit raw private-key material** in any Item body, metadata
   value, log message, or debug output.
2. **Never process a keystore by creating child Items.** Keystores
   produce one Item with all contained pURLs and metadata flat.
3. **Never emit a pURL for a keystore container or for an encrypted
   private key or keystore.** Encrypted material stays opaque:
   envelope metadata only.
4. **Never add a new `EdgeType`** as part of this work.
5. **All qualifier values are lowercase** with hyphens (not
   underscores) as separators.
6. **All ad-hoc metadata keys use `:` as the separator** at every
   nesting level (matches `MKC.adHoc`'s `prefix:key` output).

## Phase status

| Phase | Status | What lands |
|---|---|---|
| Phase 0 (test corpus + harness) | ✓ done | 353 paired fixtures + sidecar parser + integrity suite + ground-truth cross-check |
| **Phase 1 (foundation + wiring)** | **✓ done** | Bouncy Castle deps, Java 21 release target, [[CryptoDetector]] stub, [[Certificates]] strategy skeleton, both registered |
| **Phase 2 (CryptoDetector MIME augmentation)** | **✓ done** | Content-sniffing for PEM/DER/SSH/PGP/JKS — 21 detection signatures, 4 KB read budget, BC X.509+CRL parser probe |
| **Phase 3 (X.509 core)** | **✓ done** | Single-cert claim/parse/emit; SPKI + cert-sha256 pURLs; full metadata table; defensive leak sweep |
| **Phase 4 (keystores, PEM bundles, CRLs)** | **✓ done** | Container types, flat-Item shape; PEM bundles (8 fixtures), JKS/JCEKS/PKCS#12/BKS keystores (14 fixtures), X.509 CRLs (10 fixtures); RFC2253 hex-decoding follow-on |
| **Phase 5 (SSH + OpenSSH certs)** | **✓ done** | OpenSSH plain pubkeys (28 fixtures: 24 GitHub + 4 synthetic) + OpenSSH user/host certs (6 synthetic); RFC-4251 wire-format reader; ssh-keygen-cross-checked fingerprints; Phase-2 cert-token placeholder bug fix |
| Phase 6 (PGP) | pending | v4 + v6 + subkeys (v5 deferred) |
| Phase 7 (private keys) | pending | unencrypted derive-pubkey path + encrypted envelope path |
| Phase 8 (property tests + leak sweep + coverage) | pending | invariant guards |
| Phase 9 (documentation consolidation) | pending | `info/architecture.md` + `info/mime_types.md` updates |

## Pointers (for current Phase 1 state)

- Strategy entry: `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala`
- Augmenter stub: `src/main/scala/io/spicelabs/goatrodeo/util/CryptoDetector.scala`
- Plan index: `../certificates-strategy-plan.md`
- ADRs: `adrs/adr-001-certificates-phase-0-harness.md`,
  `adrs/adr-002-certificates-corpus-determinism.md`
- Phase 0 claims: `certificates/phase-0-claims.md`
