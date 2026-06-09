/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.File

/** Strategy-level PGP parser tests with `gpg --show-keys --with-fingerprint`
  * ground truth.
  *
  * ## What these tests test
  *
  * Phase 6 plan §"For each PGPPublicKey": fingerprint, version, alg,
  * size/curve, subkey enumeration. The tests pin a representative fixture for
  * each major algorithm + version combination and assert the strategy's parsed
  * values against the canonical `gpg(1)` output captured at fixture-creation
  * time.
  *
  * ## Why this matters
  *
  * Sidecars are materialized from the strategy's own emitters (tautological by
  * construction, same pattern as Phase 4/5). These tests provide independent
  * ground truth: the expected fingerprints were lifted from `gpg --show-keys
  * --with-fingerprint --with-subkey-fingerprint` output and committed inline
  * below. A bug that flipped, say, `getFingerprint` to `getKeyID` (an 8-byte
  * truncation) would fail here.
  *
  * v6 keys cannot be cross-checked against system `gpg` — current GnuPG
  * releases reject the v6 packet format. The BC parser handles them; the v6
  * fixture is asserted against values BC reports.
  */
class PgpStrategyParserTests extends FunSuite {

  private def wrap(path: String): FileWrapper =
    FileWrapper(new File(path), path, None)

  test("parsePgpKeyRing: v4 RSA-3072 single primary") {
    // gpg --show-keys --with-fingerprint:
    //   pub   rsa3072 2026-04-28 [SC]
    //         3800 518C E65F A1B2 8E54  0B3C D242 0907 93BA 9DC6
    //   uid   GoatRodeo Test rsa3072 <goatrodeo-rsa3072@test.invalid>
    val w = wrap("test_data/certificates/pgp/synthetic/v4-rsa3072-pub.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    assertEquals(r.keys.length, 1)
    val k = r.keys.head
    assertEquals(k.fingerprintHex, "3800518ce65fa1b28e540b3cd242090793ba9dc6")
    assertEquals(k.version, 4)
    assertEquals(k.canonicalAlg, "rsa")
    assertEquals(k.keySize, Some(3072))
    assertEquals(k.curve, None)
    assert(k.isPrimary)
    assert(k.userIds.exists(_.contains("rsa3072")))
  }

  test(
    "parsePgpKeyRing: v4 ed25519 primary + ECDH cv25519 encryption subkey (G3)"
  ) {
    // Plan §125-135 fixture table: "ed25519, v4, subkey encryption".
    // Regenerated 2026-05-01 to add the encryption subkey the plan
    // requires; gpg --list-keys --with-fingerprint --with-subkey-fingerprint:
    //   pub   ed25519 2026-05-01 [SC] [expires: 2028-04-30]
    //         6046 C53C 8DF8 C522 076F  8CD7 6D7F AAE7 96AB C62E
    //   uid   GoatRodeo Test ed25519 with subkey <goatrodeo-ed25519-sub@test.invalid>
    //   sub   cv25519 2026-05-01 [E] [expires: 2028-04-30]
    //         5991 5A0A 243D 30D5 002D  6AA5 8ED8 1EA8 ADFB 3A65
    val w = wrap("test_data/certificates/pgp/synthetic/v4-ed25519-pub.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    assertEquals(r.keys.length, 2, "expected primary + 1 ECDH subkey")
    val primary = r.keys.find(_.isPrimary).get
    val sub = r.keys.find(!_.isPrimary).get
    assertEquals(
      primary.fingerprintHex,
      "6046c53c8df8c522076f8cd76d7faae796abc62e"
    )
    assertEquals(primary.version, 4)
    assertEquals(primary.canonicalAlg, "ed25519")
    assertEquals(sub.fingerprintHex, "59915a0a243d30d5002d6aa58ed81ea8adfb3a65")
    // ECDH on cv25519 → alg=ec, curve=curve25519
    assertEquals(sub.canonicalAlg, "ec")
    assertEquals(sub.curve, Some("curve25519"))
  }

  // G7 — Phase 6 plan §Metadata: "ExpirationTime — ISO-8601 UTC or
  // omitted if never expires". For PGP subkeys, expiration is encoded
  // in the binding-signature subpacket (type 9), not in the key
  // packet itself. This test pins that BC's `getValidSeconds()` reads
  // the binding-signature value correctly so subkey expiration is
  // preserved through the strategy's metadata path.
  test("parsePgpKeyRing: subkey expiration is preserved (G7)") {
    val w = wrap("test_data/certificates/pgp/synthetic/v4-ed25519-pub.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    val primary = r.keys.find(_.isPrimary).get
    val sub = r.keys.find(!_.isPrimary).get
    // Both primary and subkey have 2-year expiration per Expire-Date: 2y.
    // BC propagates from binding signature for the subkey.
    assert(
      primary.expirationTime.isDefined,
      s"primary expirationTime must be set; got ${primary.expirationTime}"
    )
    assert(
      sub.expirationTime.isDefined,
      s"subkey expirationTime must be set; got ${sub.expirationTime}"
    )
    // Expiration is creation + 2 years (63072000 seconds).
    val primaryYears =
      (primary.expirationTime.get.getTime - primary.creationTime.getTime) / 1000L
    val subYears =
      (sub.expirationTime.get.getTime - sub.creationTime.getTime) / 1000L
    assertEquals(
      primaryYears,
      63072000L,
      "primary should expire 2y after creation"
    )
    assertEquals(
      subYears,
      63072000L,
      "subkey should expire 2y after creation (read from binding signature)"
    )
  }

  test("parsePgpKeyRing: v4 DSA primary + ElGamal subkey") {
    // gpg --show-keys --with-fingerprint --with-subkey-fingerprint:
    //   pub   dsa2048 2026-04-28 [SC]
    //         266C B770 BDB0 894C 43CE  29AF 568D 1479 2D37 57E9
    //   sub   elg2048 2026-04-28 [E]
    //         2A60 B99A 059B 5AC5 5DAC  99F4 E75B E054 8B58 DE20
    val w = wrap("test_data/certificates/pgp/synthetic/v4-dsa-elgamal-pub.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    assertEquals(r.keys.length, 2, "expected primary + 1 subkey")
    val primary = r.keys.find(_.isPrimary).get
    val sub = r.keys.find(!_.isPrimary).get
    assertEquals(
      primary.fingerprintHex,
      "266cb770bdb0894c43ce29af568d14792d3757e9"
    )
    assertEquals(primary.canonicalAlg, "dsa")
    assertEquals(primary.keySize, Some(2048))
    assertEquals(sub.fingerprintHex, "2a60b99a059b5ac55dac99f4e75be0548b58de20")
    assertEquals(sub.canonicalAlg, "elgamal")
    assertEquals(sub.keySize, Some(2048))
  }

  // G5 — Phase 6 gap analysis flagged that 8 of 9 real-world fixtures
  // are asserted only via materializer-tautological values. These three
  // tests pin gpg(1) ground-truth fingerprints for additional fixtures
  // covering different organizational keys.
  test(
    "parsePgpKeyRing: real-world docker-ce signing key (rsa4096 + S subkey) (G5)"
  ) {
    // gpg --show-keys --with-fingerprint --with-subkey-fingerprint:
    //   pub   rsa4096 2017-02-22 [SCEAR]
    //         9DC8 5822 9FC7 DD38 854A  E2D8 8D81 803C 0EBF CD88
    //   sub   rsa4096 2017-02-22 [S]
    //         D330 6A01 8370 199E 527A  E799 7EA0 A9C3 F273 FCD8
    val w = wrap("test_data/certificates/pgp/real/docker-ce.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    val primary = r.keys.find(_.isPrimary).get
    assertEquals(
      primary.fingerprintHex,
      "9dc858229fc7dd38854ae2d88d81803c0ebfcd88"
    )
    assertEquals(primary.canonicalAlg, "rsa")
    assertEquals(primary.keySize, Some(4096))
    val sub = r.keys.find(!_.isPrimary).get
    assertEquals(sub.fingerprintHex, "d3306a018370199e527ae7997ea0a9c3f273fcd8")
  }

  test("parsePgpKeyRing: real-world debian-cdimage signing key (G5)") {
    // gpg --show-keys --with-fingerprint --with-subkey-fingerprint:
    //   pub   rsa4096 2011-01-05 [SCEAR]
    //         DF9B 9C49 EAA9 2984 3258  9D76 DA87 E80D 6294 BE9B
    //   sub   rsa4096 2011-01-05 [E]
    //         47A8 EA16 451B F5C9 B691  5C64 642A 5AC3 11CD 9819
    val w = wrap("test_data/certificates/pgp/real/debian-cdimage.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    val primary = r.keys.find(_.isPrimary).get
    assertEquals(
      primary.fingerprintHex,
      "df9b9c49eaa9298432589d76da87e80d6294be9b"
    )
    val sub = r.keys.find(!_.isPrimary).get
    assertEquals(sub.fingerprintHex, "47a8ea16451bf5c9b6915c64642a5ac311cd9819")
  }

  test("parsePgpKeyRing: real-world kernel-konstantin (rsa2048) (G5)") {
    // gpg --show-keys --with-fingerprint --with-subkey-fingerprint:
    //   pub   rsa2048 2011-09-20 [SCEAR]
    //         ABAF 11C6 5A29 70B1 30AB  E3C4 79BE 3E43 0041 1886
    //   sub   rsa2048 2011-09-20 [E]
    //         AEE4 16F7 DCCB 753B B3D5  609D 88BC E80F 012F 54CA
    val w = wrap("test_data/certificates/pgp/real/kernel-konstantin.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    val primary = r.keys.find(_.isPrimary).get
    assertEquals(
      primary.fingerprintHex,
      "abaf11c65a2970b130abe3c479be3e4300411886"
    )
    assertEquals(primary.keySize, Some(2048))
    val sub = r.keys.find(!_.isPrimary).get
    assertEquals(sub.fingerprintHex, "aee416f7dccb753bb3d5609d88bce80f012f54ca")
  }

  test(
    "parsePgpKeyRing: real-world Linux kernel maintainer (rsa4096 + subkey)"
  ) {
    // gpg --show-keys --with-fingerprint --with-subkey-fingerprint:
    //   pub   rsa4096 2011-09-23 [SCEAR]
    //         647F 2865 4894 E3BD 4571  99BE 38DB BDC8 6092 693E
    //   sub   rsa4096 2011-09-23 [E]
    //         F41B DF16 F35C D80D 9E56  735B F381 53E2 76D5 4749
    val w = wrap("test_data/certificates/pgp/real/kernel-greg-kh.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    assertEquals(r.keys.length, 2)
    val primary = r.keys.find(_.isPrimary).get
    val sub = r.keys.find(!_.isPrimary).get
    assertEquals(
      primary.fingerprintHex,
      "647f28654894e3bd457199be38dbbdc86092693e"
    )
    assertEquals(primary.canonicalAlg, "rsa")
    assertEquals(primary.keySize, Some(4096))
    assertEquals(sub.fingerprintHex, "f41bdf16f35cd80d9e56735bf38153e276d54749")
    assertEquals(sub.canonicalAlg, "rsa")
    assertEquals(sub.keySize, Some(4096))
  }

  test("parsePgpKeyRing: v6 ed25519 (BC parser, no GPG cross-check possible)") {
    // GnuPG (current) rejects v6 packet format. BC handles it. The v6
    // fixture has 3 keys: primary + 2 subkeys per the materialized
    // sidecar (3 pURLs). All should report version=6.
    val w = wrap("test_data/certificates/pgp/synthetic/v6-ed25519-pub.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    assertEquals(r.keys.length, 3)
    assert(
      r.keys.forall(_.version == 6),
      s"expected all keys at v6, got ${r.keys.map(_.version)}"
    )
    val fpLengths = r.keys.map(_.fingerprintHex.length).distinct
    assertEquals(
      fpLengths,
      Vector(64),
      "v6 fingerprints must be 64 hex chars (32 bytes SHA-256)"
    )
  }

  test("purlForPgpKey: shape and ordering") {
    val w = wrap("test_data/certificates/pgp/synthetic/v4-rsa3072-pub.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    val purl = Certificates.purlForPgpKey(r.keys.head).toCanonical().nn
    assertEquals(
      purl,
      "pkg:generic/pgp/fingerprint@3800518ce65fa1b28e540b3cd242090793ba9dc6?alg=rsa&size=3072&version=4"
    )
  }

  test("pgpFp8: matches PGP short-id convention") {
    val w = wrap("test_data/certificates/pgp/synthetic/v4-rsa3072-pub.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    assertEquals(Certificates.pgpFp8(r.keys.head), "3800518c")
  }

  test("parsePgpKeyRing: garbage input returns None") {
    val tmp = java.io.File.createTempFile("garbage", ".asc")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, "not a pgp file\n".getBytes("UTF-8"))
    val w = FileWrapper(tmp, tmp.getName, None)
    assertEquals(Certificates.parsePgpKeyRing(w), None)
  }

  test("parsePgpKeyRing: empty file returns None") {
    val tmp = java.io.File.createTempFile("empty", ".asc")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, "".getBytes("UTF-8"))
    val w = FileWrapper(tmp, tmp.getName, None)
    assertEquals(Certificates.parsePgpKeyRing(w), None)
  }

  // N1 — second-pass gap analysis: the new fixtures landed by the first
  // pass (G1/G2/G8/G9/G12) rely on materialized sidecars (tautological).
  // The tests below pin gpg(1) ground-truth fingerprints + structural
  // claims white-box, so a regression in `pgpAlgIdMap` (e.g. ECDH→unknown)
  // or `pgpCurveOidMap` (e.g. NIST P-256 OID typo) fails here, not just
  // in the sidecars (which would have re-emitted the bug).
  test(
    "parsePgpKeyRing: v4 ECDSA NIST P-256 primary + ECDH NIST P-256 subkey (G1+G2 / N1)"
  ) {
    // gpg --show-keys --with-fingerprint --with-subkey-fingerprint:
    //   pub   nistp256 2026-05-01 [SCA]
    //         266A 16A9 2E5F 70A9 303C  3AC2 E345 85E3 B7DD 707A
    //   uid   GoatRodeo Test ECDSA P-256 <goatrodeo-ecdsa-p256@test.invalid>
    //   sub   nistp256 2026-05-01 [E]
    //         4E1B C8CD BE7A C3D0 CF83  72E9 B439 4101 605B 32AE
    val w = wrap("test_data/certificates/pgp/synthetic/v4-ecdsa-p256-pub.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    assertEquals(r.keys.length, 2, "expected ECDSA primary + ECDH subkey")
    val primary = r.keys.find(_.isPrimary).get
    val sub = r.keys.find(!_.isPrimary).get
    assertEquals(
      primary.fingerprintHex,
      "266a16a92e5f70a9303c3ac2e34585e3b7dd707a"
    )
    assertEquals(
      primary.canonicalAlg,
      "ec",
      "ECDSA (alg-id 19) maps to canonical 'ec'"
    )
    assertEquals(
      primary.curve,
      Some("p-256"),
      "OID 1.2.840.10045.3.1.7 maps to 'p-256' via pgpCurveOidMap"
    )
    assertEquals(primary.keySize, None, "EC keys have no keySize")
    assertEquals(primary.pgpAlgId, 19, "PGP algorithm ID for ECDSA")
    assertEquals(sub.fingerprintHex, "4e1bc8cdbe7ac3d0cf8372e9b4394101605b32ae")
    assertEquals(
      sub.canonicalAlg,
      "ec",
      "ECDH (alg-id 18) maps to canonical 'ec'"
    )
    assertEquals(sub.curve, Some("p-256"))
    assertEquals(sub.pgpAlgId, 18, "PGP algorithm ID for ECDH")
  }

  test(
    "parsePgpKeyRing: v4 ECDSA brainpoolP256r1 primary + ECDH subkey (G1+G2 / N1)"
  ) {
    // gpg --show-keys --with-fingerprint --with-subkey-fingerprint:
    //   pub   brainpoolP256r1 2026-05-01 [SCA]
    //         7C17 C305 167E F3AE FFAE  DFF3 5EB1 7155 E617 6DDD
    //   sub   brainpoolP256r1 2026-05-01 [E]
    //         EF1D 71CD 7C60 9251 485F  3D07 1FF7 EC16 D8C4 D8DF
    val w =
      wrap("test_data/certificates/pgp/synthetic/v4-ecdsa-brainpool256-pub.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    assertEquals(r.keys.length, 2)
    val primary = r.keys.find(_.isPrimary).get
    val sub = r.keys.find(!_.isPrimary).get
    assertEquals(
      primary.fingerprintHex,
      "7c17c305167ef3aeffaedff35eb17155e6176ddd"
    )
    assertEquals(primary.canonicalAlg, "ec")
    assertEquals(
      primary.curve,
      Some("brainpoolp256r1"),
      "OID 1.3.36.3.3.2.8.1.1.7 maps to 'brainpoolp256r1' via pgpCurveOidMap"
    )
    assertEquals(sub.fingerprintHex, "ef1d71cd7c609251485f3d071ff7ec16d8c4d8df")
    assertEquals(sub.canonicalAlg, "ec")
    assertEquals(sub.curve, Some("brainpoolp256r1"))
  }

  // G8 — multi-ring file (concatenated 2 primary keys w/ subkeys).
  // White-box assertion that splitArmoredBlocks + per-segment parse
  // produces the union of all keys across rings, not just the first ring.
  test("parsePgpKeyRing: multi-ring file yields union of all keys (G8 / N1)") {
    val w = wrap("test_data/certificates/pgp/synthetic/v4-multi-ring.asc")
    val r = Certificates.parsePgpKeyRing(w).get
    // Source: cat v4-rsa3072-pub.asc v4-ed25519-pub.asc
    //   ring 1: 1 RSA-3072 primary
    //   ring 2: 1 Ed25519 primary + 1 ECDH cv25519 subkey
    assertEquals(
      r.keys.length,
      3,
      "multi-ring concatenation must yield all keys from all rings"
    )
    val fps = r.keys.map(_.fingerprintHex).toSet
    assert(
      fps.contains("3800518ce65fa1b28e540b3cd242090793ba9dc6"),
      "ring 1 RSA primary must be present"
    )
    assert(
      fps.contains("6046c53c8df8c522076f8cd76d7faae796abc62e"),
      "ring 2 Ed25519 primary must be present"
    )
    assert(
      fps.contains("59915a0a243d30d5002d6aa58ed81ea8adfb3a65"),
      "ring 2 ECDH cv25519 subkey must be present"
    )
    // Documented design choice (N7 / ADR): top-level primaryUserId is the
    // first ring's primary uid, not a concat. Pin this contract.
    assert(
      r.primaryUserId.exists(_.contains("rsa3072")),
      s"primaryUserId must be the FIRST ring's primary uid; got ${r.primaryUserId}"
    )
  }

  // G12 — binary (unarmored) PGP file. The .gpg fixture is the same key
  // as v4-rsa3072-pub.asc but stored as raw binary OpenPGP packets.
  // PGPUtil.getDecoderStream handles both forms transparently; this test
  // confirms the strategy emits the identical fingerprint on the binary
  // path as on the armored path.
  test(
    "parsePgpKeyRing: binary .gpg file yields same identity as armored .asc (G12 / N1)"
  ) {
    val wBinary =
      wrap("test_data/certificates/pgp/synthetic/v4-rsa3072-pub.gpg")
    val wArmored =
      wrap("test_data/certificates/pgp/synthetic/v4-rsa3072-pub.asc")
    val rBinary = Certificates.parsePgpKeyRing(wBinary).get
    val rArmored = Certificates.parsePgpKeyRing(wArmored).get
    assertEquals(rBinary.keys.length, 1)
    assertEquals(
      rBinary.keys.head.fingerprintHex,
      rArmored.keys.head.fingerprintHex,
      "binary and armored fixtures of the same key must " +
        "produce identical fingerprints"
    )
    assertEquals(rBinary.keys.head.canonicalAlg, "rsa")
    assertEquals(rBinary.keys.head.keySize, Some(3072))
    assertEquals(rBinary.keys.head.version, 4)
  }

  // G9 — Phase 6 contract: the PUBLIC-key parser `parsePgpKeyRing`
  // must reject a `BEGIN PGP PRIVATE KEY BLOCK` file (BC's object
  // factory yields `PGPSecretKeyRing`, which `parsePgpKeyRing` does
  // not match). Phase 7 wires a separate `parsePgpSecretKeyRing` and
  // dispatches via `parsePgpKeyOrSecretKeyRing`; the public-key
  // parser's None-returning contract on secret-key input is preserved.
  // Fixture relocated from edge-cases/pgp/ to private-keys/synthetic/
  // when Phase 7 began claiming it.
  test(
    "parsePgpKeyRing: PGP PRIVATE KEY BLOCK returns None from PUBLIC-key parser (G9)"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pgp-secret-ed25519-unencrypted.asc"
    )
    assertEquals(
      Certificates.parsePgpKeyRing(w),
      None,
      "parsePgpKeyRing (the Phase-6 public-key parser) must return " +
        "None on secret-key input. Phase 7's parsePgpSecretKeyRing " +
        "handles the actual claim; dispatch is via parsePgpKeyOrSecretKeyRing."
    )
  }

  // N3 — `pgpAlgIdMap` size claim. The doc previously said size==10
  // (wrong); the literal map has 13 entries (RSA has 3 ids: 1, 2, 3;
  // ElGamal has 2: 16, 20). The plan TABLE shows 10 rows (one per
  // canonical (alg-id-group, canonical-alg) tuple); the distinct
  // canonical-alg name set has 8 elements because `ec` covers both
  // ECDH(18) and ECDSA(19), and `ed25519` covers both EdDSA-Legacy(22)
  // and Ed25519(27). Pin all three numbers.
  test(
    "pgpAlgIdMap: 13 alg-id entries / 10 plan-table rows / 8 distinct canonical alg names (N3)"
  ) {
    assertEquals(
      Certificates.pgpAlgIdMap.size,
      13,
      "13 alg-id entries: RSA(1,2,3) + ElGamal(16,20) + DSA(17) + " +
        "ECDH(18) + ECDSA(19) + EdDSA-Legacy(22) + X25519(25) + X448(26) " +
        "+ Ed25519(27) + Ed448(28) = 3+2+1+1+1+1+1+1+1+1 = 13"
    )
    assertEquals(
      Certificates.pgpAlgIdMap.values.toSet,
      Set("rsa", "elgamal", "dsa", "ec", "ed25519", "x25519", "x448", "ed448"),
      "8 distinct canonical-alg values; plan-table 10 rows collapse " +
        "to 8 because (ECDH, ECDSA) both → 'ec' and (EdDSA-Legacy, " +
        "Ed25519) both → 'ed25519'"
    )
    // Spot-check the unified-EC mapping (alg=ec for both ECDH and ECDSA).
    assertEquals(Certificates.pgpAlgIdMap(18), "ec")
    assertEquals(Certificates.pgpAlgIdMap(19), "ec")
    // Spot-check both Ed25519 paths (legacy + native v6).
    assertEquals(Certificates.pgpAlgIdMap(22), "ed25519")
    assertEquals(Certificates.pgpAlgIdMap(27), "ed25519")
  }

  // N3 — `pgpCurveOidMap` size sanity. 8 entries covering NIST P-256/
  // 384/521, Brainpool 256/384/512, Curve25519, Ed25519Legacy.
  test("pgpCurveOidMap: 8 curve-OID entries (N3)") {
    assertEquals(Certificates.pgpCurveOidMap.size, 8)
    // Spot-check: the two curves the new ECDSA fixtures exercise.
    assertEquals(Certificates.pgpCurveOidMap("1.2.840.10045.3.1.7"), "p-256")
    assertEquals(
      Certificates.pgpCurveOidMap("1.3.36.3.3.2.8.1.1.7"),
      "brainpoolp256r1"
    )
  }
}
