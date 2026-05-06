# Phase 2 — Claims (LLM-friendly)

Parallel copy of [`phase-2-claims.md`](phase-2-claims.md).

## State

Phase 2 done. `CryptoDetector` content-sniffing MIME augmentation
landed. 21 detection signatures + 5 negative-test categories + 4 KB
read budget + 8 real-fixture cross-checks (HS-4).

## Files (Phase 2)

| File | Change |
|---|---|
| `src/main/scala/io/spicelabs/goatrodeo/util/CryptoDetector.scala` | Body replaced — pass-through stub → 21-signature content sniffer |
| `src/test/scala/strategies/CryptoDetectorSuite.scala` | NEW — 47 tests |

## Test counts

| Suite | Count | All green |
|---|---|---|
| CryptoDetectorSuite | 47 | yes |

## Detection signatures (1:1 with plan table)

PEM cert / bundle / CSR / public key / RSA private / EC private /
generic private / encrypted private / OpenSSH private / OpenSSH
pubkey (8 token variants) / OpenSSH cert (6 token variants) / PGP
armored pub|priv|signature|message / PGP binary (4 packet-tag
variants) / JKS / JCEKS / PKCS#12 / DER X.509 / DER CRL / PEM CRL /
PKCS#7 PEM = **21 distinct rows.**

## Negative tests

- plain text → unchanged
- random binary → unchanged
- PEM typo `BEGIN CERTIFICAT` → no match
- `0x30 0x82` not valid X.509 / not `.p12`/`.pfx` → unchanged
- 0xC6 first byte → DOES match (acceptable false positive,
  plan-permitted; strategy-time parser will fail and file falls
  through)

## 4 KB read-budget invariant

`MAX_READ_BYTES == 4096`. Behavioral tests:
- header at offset 4096+ → invisible (proves we stop reading)
- header at offset 4095 (last byte of budget) → found (proves we
  read all of the first 4 KB, not less)

## HS-4 real-fixture cross-checks

Mozilla PEM cert sample / Mozilla bundle / GitHub SSH pubkey /
synthetic v4 PGP / JKS / PKCS#12 / edge-cases empty.pem /
edge-cases pem-typo-header.pem.

## Phase-INVARIANT contracts

- Augmenter output ⊇ input (additive)
- Never strips text/-prefixed MIMEs

## Pointers

- `src/main/scala/io/spicelabs/goatrodeo/util/CryptoDetector.scala` —
  implementation
- `src/test/scala/strategies/CryptoDetectorSuite.scala` — tests
- `certificates-strategy/phases-1-2-foundation-detector.md` Phase 2 — plan
- `info/certificates/phase-2-claims.md` — full claim matrix
