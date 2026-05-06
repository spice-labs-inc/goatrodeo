# Phase 5 — Claims (LLM)

Parallel of [`phase-5-claims.md`](phase-5-claims.md).

## State

Phase 5 done. Strategy claims OpenSSH plain pubkeys + CA certs.

## Files

| File | Change |
|---|---|
| `src/main/scala/io/spicelabs/goatrodeo/util/SshWireFormat.scala` | new — RFC-4251 reader (`SshWireReader`) with uint32/uint64/string/mpint/string-list/name-data-list helpers, `mpintBitLength`, `parseFirstKeyLine` (BOM-strip + comment-line skip) |
| `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala` | new ADT cases `SshPubkey`, `SshCert`; parsers `parseSshPubkey`, `parseSshCert`, `parseSshCertBlob`; emitters `purlForSshPubkey`, `purlsForSshCert`, `sshPubkeyMetadata`, `sshCertMetadata`; `sshAlgMap`; `signedKeyAlgFromCertName`; `sshFingerprintB64`; `sshKeyQualifiers`; SSH MIMEs added to dispatcher |
| `src/main/scala/io/spicelabs/goatrodeo/util/CryptoDetector.scala` | bug fix — `sshCertTokens` had 4 placeholder `[email protected]` strings; replaced with real `ssh-{rsa,dss,ed25519}-cert-v01@openssh.com` and `ecdsa-sha2-nistp{256,384,521}-cert-v01@openssh.com` |
| `src/test/scala/strategies/MaterializePhase4Sidecars.scala` | extended for SSH (pubkey vs cert via first token); metadata-placeholder transcription |
| `src/test/scala/strategies/SshWireFormatTests.scala` | new — 17 unit tests |
| `src/test/scala/strategies/SshStrategyParserTests.scala` | new — 6 parser tests with `ssh-keygen -lf` cross-check |
| 34 sidecars under `test_data/certificates/ssh/` | placeholders materialized |

## Wire format

RFC-4251: `byte`, `uint32` (BE), `uint64` (BE), `string` (4-byte length + bytes), `mpint` (string-of-twos-complement), `string-of-strings`, `name-data-list`.

Plain pubkey wire = `string(algo) | <alg-fields>`:
- `ssh-rsa`: `mpint(e) | mpint(n)` → size from n bit-length
- `ssh-ed25519`: `string(pk)`
- `ecdsa-sha2-nistp{256,384,521}`: `string(curve) | string(Q)`

OpenSSH cert wire = `string(certTypeName) | string(nonce) | <key-fields> | uint64(serial) | uint32(certType) | string(keyId) | string(principals) | uint64(validAfter) | uint64(validBefore) | string(criticalOpts) | string(extensions) | string(reserved) | string(caKeyWire) | string(signature)` where `string(signature)` itself contains `string(caSigAlg) | string(sigBytes)`.

## pURL shapes

- Plain: `pkg:ssh/sha256@{b64-no-pad}?alg={canonical}&{companion}`
- Cert (2 pURLs): `pkg:ssh/cert-sha256@{hex}?{key-quals}&cert-type={user|host}&sig-alg={ca-sig-alg}` AND `pkg:ssh/sha256@{signed-key-fp}?{key-quals}`

(Qualifiers alphabetical.)

## Algorithm map

| wire name | alg | companion | sk |
|---|---|---|---|
| ssh-rsa | rsa | size=N (from modulus) | — |
| ssh-dss | dsa | size=1024 | — |
| ssh-ed25519 | ed25519 | — | — |
| ssh-ed448 | ed448 | — | — |
| ecdsa-sha2-nistp256 | ec | curve=p-256 | — |
| ecdsa-sha2-nistp384 | ec | curve=p-384 | — |
| ecdsa-sha2-nistp521 | ec | curve=p-521 | — |
| sk-ssh-ed25519@openssh.com | ed25519 | — | sk=true |
| sk-ecdsa-sha2-nistp256@openssh.com | ec | curve=p-256 | sk=true |

## Acceptance

- ✓ 34/34 SSH fixtures green
- ✓ 17 wire-format unit tests
- ✓ 6 strategy parser tests with ssh-keygen ground truth
- ✓ Phase 1-4 fixtures (287) still green
- ✓ Phase 2 cert-token placeholder bug fixed
- ✓ HS-2 4-step exit gate executed

## Final state (after Phase 5)

CertificatesSuite total: 353. Pass: 321. Fail: 32 (15 PGP Phase 6 + 17 private-keys Phase 7).
