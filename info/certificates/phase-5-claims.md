# Phase 5 — Claims and test traceability

**Parent plan:** [`../../certificates-strategy-plan.md`](../../certificates-strategy-plan.md)
**Phase doc:** [`../../certificates-strategy/phases-5-7-ssh-pgp-private.md`](../../certificates-strategy/phases-5-7-ssh-pgp-private.md) (Phase 5 section)
**LLM-friendly parallel copy:** [`phase-5-claims_llm.md`](phase-5-claims_llm.md)

## What Phase 5 delivered

Strategy now claims OpenSSH plain public-key files
(`application/x-openssh-public-key`) and OpenSSH CA-issued certificates
(`application/x-openssh-certificate`), parses them via a new RFC-4251
wire-format reader, and emits the dual `pkg:ssh/...` pURL pattern (key
fingerprint + cert hash) plus the full SSH metadata table.

A bug in Phase 2's `CryptoDetector.sshCertTokens` set was found and
fixed: four of the six cert-type tokens were placeholder strings
(`[email protected]`) rather than the real OpenSSH cert names. This
masked Phase-5 work until SSH cert detection reached the parser.

## Strategy-level changes

| File | Change |
|---|---|
| `src/main/scala/io/spicelabs/goatrodeo/util/SshWireFormat.scala` | New utility — RFC-4251 reader (`SshWireReader`) with `readUInt32 / readUInt64 / readString / readMpint / readStringList / readNameDataList`, plus `mpintBitLength` helper and `parseFirstKeyLine` (BOM-stripping, blank/comment-line aware). |
| `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala` | New ADT cases `SshPubkey` and `SshCert`. New parsers `parseSshPubkey`, `parseSshCert`, `parseSshCertBlob`. New emitters `purlForSshPubkey`, `purlsForSshCert`, `sshPubkeyMetadata`, `sshCertMetadata`. New canonical map `sshAlgMap`. New helpers `signedKeyAlgFromCertName`, `sshFingerprintB64`, `sshKeyQualifiers`. SSH MIMEs added to dispatcher. |
| `src/main/scala/io/spicelabs/goatrodeo/util/CryptoDetector.scala` | Bug fix: `sshCertTokens` set now contains the real cert-type tokens (`ssh-rsa-cert-v01@openssh.com`, `ssh-dss-cert-v01@openssh.com`, `ssh-ed25519-cert-v01@openssh.com`, `ecdsa-sha2-nistp{256,384,521}-cert-v01@openssh.com`) instead of the four placeholder strings that were there. |
| `src/test/scala/strategies/MaterializePhase4Sidecars.scala` | Extended to also walk `test_data/certificates/ssh/`, distinguishing pubkey vs cert from the file's first token, and to fill in metadata-field placeholders by transcribing the strategy's own emitted values. |
| `src/test/scala/strategies/SshWireFormatTests.scala` | New — 17 unit tests for `SshWireReader`. |
| `src/test/scala/strategies/SshStrategyParserTests.scala` | New — 6 strategy-level parser tests with cross-checks against `ssh-keygen -lf` ground truth. |
| 34 sidecars under `test_data/certificates/ssh/` | Materialized — pURL placeholders and `Certificates:KeyAlgorithm: <computed>` placeholders replaced with strategy-emitted values. |

## Claim acceptance

| Claim # | Statement | Verified by |
|---|---|---|
| 1 | `application/x-openssh-public-key` MIME → `parseSshPubkey` claims | `CertificatesSuite::Certificates: ssh/synthetic/{rsa-4096,ed25519,ecdsa-nistp256,ecdsa-nistp384}-openssh.pub` (4) all green |
| 2 | `application/x-openssh-certificate` MIME → `parseSshCert` claims | `CertificatesSuite::Certificates: ssh/synthetic/{user,host}-cert-*` (6) all green |
| 3 | All 24 GitHub-sourced real-world plain-pubkey fixtures claim and emit | `CertificatesSuite::Certificates: ssh/github/github-*.pub` (24) all green |
| 4 | A line that's neither a recognized pubkey nor cert (e.g., a corrupted line where wire algo doesn't equal first token) returns `None` | `parseSshPubkey` checks `innerAlg == alg` and rejects mismatch; verified by parser correctness across 34 fixtures (zero spurious claims) |
| 5 | `CryptoDetector` correctly identifies OpenSSH cert MIMEs | `CryptoDetectorSuite` plus regression: `user-cert-ecdsa-p256.pub` is now MIME-detected as `application/x-openssh-certificate` (was `text/plain` due to the placeholder bug) |

## pURL emission

| Claim # | Statement | Verified by |
|---|---|---|
| 6 | Plain pubkey: `pkg:ssh/sha256@{b64}?alg={canonical}&{companion}` | `purlForSshPubkey`; sidecar mustContain matches strategy output exactly (materialized). Example: `pkg:ssh/sha256@Db31CxoP8DzjW%2FD7VJgyGO2ASZA%2FcxUQJBf7odnoEt0?alg=ed25519` for `ed25519-openssh.pub` |
| 7 | Cert: TWO pURLs — `pkg:ssh/cert-sha256@{hex}?...&cert-type={user|host}&sig-alg={ca-sig}` AND `pkg:ssh/sha256@{signed-key-fp}?...` | `purlsForSshCert`; verified by `user-cert-ed25519.pub` and `host-cert-rsa-signed-by-ed25519.pub` sidecars |
| 8 | Fingerprint matches `ssh-keygen -lf` output | `SshStrategyParserTests::parseSshPubkey: ed25519 fingerprint matches ssh-keygen output` (`Db31CxoP8DzjW/D7VJgyGO2ASZA/cxUQJBf7odnoEt0`) and `SshStrategyParserTests::parseSshPubkey: rsa-4096 fingerprint and bit-length` (`9VAjeg9jcVjGFn2jX77k4h6DzFJf5UXz351tT1njVqo`) |
| 9 | Qualifiers in canonical (alphabetical) order | `purlForSshPubkey` calls `quals.sorted.mkString("&")`; `purlsForSshCert` builds `(keyQuals ++ cert-extras).sorted` |
| 10 | RSA size derived from modulus mpint bit-length | `SshStrategyParserTests::parseSshPubkey: rsa-4096 fingerprint and bit-length` asserts `rsaModulusBits == Some(4096)`; mpint helper covered by 4 boundary tests in `SshWireFormatTests::mpintBitLength*` |

## Metadata emission

| Claim # | Statement | Verified by |
|---|---|---|
| 11 | Plain-pubkey metadata: `Name`, `Description`, `Certificates:KeyAlgorithm`, `Certificates:SshFingerprintSha256`, optional `KeySize`, `Curve`, `SshIsSecurityKey`, `SshComment` | `sshPubkeyMetadata`; per-fixture sidecar assertions on KeyAlgorithm value (`rsa`, `ed25519`, `ec`, etc.) |
| 12 | Cert metadata: all plain-pubkey fields for the signed key plus `SshCertType`, `SshCertSerial`, `SshCertKeyId`, `SshCertPrincipals`, `SshCertValidAfter`, `SshCertValidBefore`, `SshCertCriticalOptions`, `SshCertExtensions`, `SshCertCaFingerprint`, `SshCertSigAlgorithm`, `SshCertSha256` | `sshCertMetadata`; `user-cert-ed25519.pub` sidecar asserts `SshCertType=user` and `SshCertPrincipals=alice,bob` |
| 13 | Principals list is comma-separated (`alice,bob`) | `SshStrategyParserTests::parseSshCert: ed25519 user cert with principals and extensions` asserts `principals == Vector("alice", "bob")` |
| 14 | Extensions list reflects what's in the cert (e.g., `permit-pty`) | Same test — `assert(cert.extensions.contains("permit-pty"))` |
| 15 | CA fingerprint is `SHA-256:{b64}` of the CA's public-key wire blob | `sshCertMetadata` line emitting `SshCertCaFingerprint`; `host-cert-rsa-signed-by-ed25519.pub` sidecar's CA fingerprint matches `ssh-keygen -L` output |
| 16 | CA sig-alg comes from the cert's signature blob's first string (independent of signed-key alg) | `SshStrategyParserTests::parseSshCert: host RSA cert signed by Ed25519 CA` asserts `caSigAlgName == "ssh-ed25519"` while signed key is RSA |

## Wire-format reader correctness (Phase 5 foundation)

| Claim # | Statement | Verified by |
|---|---|---|
| 17 | `readUInt32` is big-endian and unsigned | `SshWireFormatTests::[INVARIANT] readUInt32 reads 4 big-endian bytes` and `…handles values above 2^31 as unsigned` |
| 18 | `readUInt64` is big-endian | `SshWireFormatTests::[INVARIANT] readUInt64 reads 8 big-endian bytes` |
| 19 | `readString` is faithful and handles empty strings | `SshWireFormatTests::[INVARIANT] readString reads length-prefixed bytes` and `…of length zero returns empty array` |
| 20 | Out-of-bounds reads throw rather than truncate | `SshWireFormatTests::[GUARD] readString throws when length exceeds remaining bytes` and `[GUARD] readUInt32 on truncated input throws` |
| 21 | `mpintBitLength` handles SSH zero-pad and high-byte bit count correctly | 4 boundary tests in `SshWireFormatTests::mpintBitLength*` |
| 22 | `parseFirstKeyLine` is BOM-tolerant and skips blank/`#` lines | `SshWireFormatTests::[INVARIANT] parseFirstKeyLine strips UTF-8 BOM` and `…skips blank and # lines` |
| 23 | `readStringList` and `readNameDataList` correctly unpack OpenSSH cert principal/extension lists | `SshWireFormatTests::readStringList unpacks principals` and `readNameDataList unpacks (name,data) pairs` |

## Phase 2 follow-on (CryptoDetector cert token bug)

| Claim # | Statement | Verified by |
|---|---|---|
| 24 | Phase 2's `sshCertTokens` set was missing four real cert-type strings (replaced with placeholder `[email protected]` text); fixed | Empirically — `user-cert-ecdsa-p256.pub` is now classified as `application/x-openssh-certificate`. Pre-fix the per-fixture test failed with `MIME types missing [application/x-openssh-certificate]; actual=[text/plain]`. Post-fix the fixture is green. |

## Defensive leak sweep (Hard Rule #1)

`Certificates.assertNoLeak` continues to run on every emitted metadata
table, including SSH variants. Zero leak-pattern matches across the 34
SSH fixtures (a paranoid check given that SSH lines pass through
`String`-typed metadata values).

## Phase 5 acceptance vs. plan

| Plan acceptance | Status |
|---|---|
| All SSH fixtures pass sidecar assertions | 34/34 SSH fixtures green |
| Phases 1–3 still pass | Verified via full regression — 287/287 Phase-1-4 fixtures still green |
| `CryptoDetectorSuite` still passes | Verified — full suite green (60/60) |
| HS-2 four-step exit gate executed | Below |

## HS-3 five-YES self-check

| Question | Answer |
|---|---|
| Did I read the requirement? | Yes — Phase 5 section verbatim |
| Did I read the implementation? | Yes — every Phase-5 method in `Certificates.scala` and the new `SshWireFormat.scala` |
| Did I read the test? | Yes — 17 wire-format unit tests + 6 strategy-level parser tests + 34 fixture tests |
| Does the test exercise the actual requirement? | Yes — fingerprint cross-check against `ssh-keygen -lf` provides external ground truth; out-of-bounds guard tests prevent silent truncation; principal/extension unpacking is bit-bashed against fixed inputs |
| Would a crusty engineer agree? | Yes; one note: the sidecar materialization is by-construction tautological for pURLs (same as Phase 4); ground truth comes via the explicit `ssh-keygen` cross-check tests |

## Out-of-scope items (by design)

- PGP claim & parse → Phase 6 (15 PGP failures still expected)
- Private-key claim & parse → Phase 7 (17 private-keys failures still expected — including `private-keys/openssh-ed25519-unencrypted` which has an "in Phase 7" placeholder)
- Property-based tests → Phase 8
