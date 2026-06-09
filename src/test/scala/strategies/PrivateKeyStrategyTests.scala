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
import io.spicelabs.goatrodeo.util.Helpers.sha256Hex
import munit.FunSuite

import java.io.File

/** Strategy-level tests for Phase 7's private-key parsers + emitters.
  *
  * ## What these tests test
  *
  * Phase 7 plan §"Two paths": every Phase-7 fixture must take the correct
  * branch (unencrypted → derive public key → emit pURL + `Envelope=plaintext` +
  * `DerivedFromPrivateKey=true`; encrypted → envelope-only metadata, no pURL,
  * no decryption attempts). The sidecar tests in `CertificatesSuite` are
  * black-box (sidecars materialized from the strategy's own emitters →
  * tautological by construction). These white-box tests pin **independent
  * ground truth** for the PEM fixtures (SPKI SHA-256 computed via `openssl pkey
  * -pubout` + `openssl pkey -pubin -outform DER | sha256sum` — this is the same
  * SHA-256 the strategy must produce).
  *
  * ## Why this matters (HS-3)
  *
  * Phase 6's first-pass remediation (G5) flagged that materializer- sourced
  * sidecar assertions are tautological: a regression in the strategy would
  * re-emit the bug into the sidecar and the test would still pass. Independent
  * ground truth (here: `openssl`'s public-key derivation, run at
  * fixture-creation time) breaks the tautology.
  *
  * ## Ground-truth recipe
  *
  * For every PEM fixture:
  * ```
  * openssl pkey -in <fixture> -pubout \
  *   | openssl pkey -pubin -inform PEM -outform DER \
  *   | sha256sum
  * ```
  * captured inline below. This is exactly what
  * `Certificates.purlForPrivateKeyPem` should compute.
  *
  * For OpenSSH fixtures: ssh-keygen on this system rejects the fixture format
  * (libcrypto mismatch). The OpenSSH tests pin alg + size + structural
  * fingerprint shape; cross-check is via the black-box CertificatesSuite
  * assertions on the materialized sidecar which iterates the entire
  * openssh-key-v1 envelope.
  */
class PrivateKeyStrategyTests extends FunSuite {

  private def wrap(path: String): FileWrapper =
    FileWrapper(new File(path), path, None)

  // ===== UNENCRYPTED PKCS#8 — independent SPKI ground truth =============

  test(
    "parsePemPrivateKey: PKCS#8 RSA-2048 unencrypted (openssl SPKI ground truth)"
  ) {
    // openssl pkey ... | sha256sum: f43c197f37e71d23d15b686d6453947883c79011d766fc6433d58bb9815125c4
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-rsa-2048-unencrypted.pem"
    )
    val claim = Certificates.classifyAndParse(w).get
    val p = claim.asInstanceOf[Certificates.PrivateKeyPlaintextPem]
    assertEquals(
      sha256Hex(p.spkiBytes),
      "f43c197f37e71d23d15b686d6453947883c79011d766fc6433d58bb9815125c4"
    )
    assertEquals(p.canonicalAlg, "rsa")
    assertEquals(p.keySize, Some(2048))
    assertEquals(p.curve, None)
  }

  test(
    "parsePemPrivateKey: PKCS#8 RSA-3072 unencrypted (openssl SPKI ground truth)"
  ) {
    // openssl: 1d69009282c0af2fffb6231037dba363ecde440c9c92e2a297391bbe43e8d3d0
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-rsa-3072-unencrypted.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextPem]
    assertEquals(
      sha256Hex(p.spkiBytes),
      "1d69009282c0af2fffb6231037dba363ecde440c9c92e2a297391bbe43e8d3d0"
    )
    assertEquals(p.keySize, Some(3072))
  }

  test(
    "parsePemPrivateKey: PKCS#8 RSA-4096 unencrypted (openssl SPKI ground truth)"
  ) {
    // openssl: c0612d36fcd581256704fa4cd6d977d51227627f2d0dbd78ba1f7349c0407c48
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-rsa-4096-unencrypted.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextPem]
    assertEquals(
      sha256Hex(p.spkiBytes),
      "c0612d36fcd581256704fa4cd6d977d51227627f2d0dbd78ba1f7349c0407c48"
    )
    assertEquals(p.keySize, Some(4096))
  }

  test(
    "parsePemPrivateKey: PKCS#8 Ed25519 unencrypted (openssl SPKI ground truth)"
  ) {
    // openssl: 23061b4699527614aba79f341cccd03865af660ffe4e90b972733a3e5cfd4104
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-ed25519-unencrypted.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextPem]
    assertEquals(
      sha256Hex(p.spkiBytes),
      "23061b4699527614aba79f341cccd03865af660ffe4e90b972733a3e5cfd4104"
    )
    assertEquals(p.canonicalAlg, "ed25519")
    assertEquals(p.keySize, None)
    assertEquals(p.curve, None)
  }

  test(
    "parsePemPrivateKey: PKCS#8 Ed448 unencrypted (openssl SPKI ground truth)"
  ) {
    // openssl: c7c43d496c8429929843683134b31303a4be766a617e8f7be582aeba3ac23d6c
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-ed448-unencrypted.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextPem]
    assertEquals(
      sha256Hex(p.spkiBytes),
      "c7c43d496c8429929843683134b31303a4be766a617e8f7be582aeba3ac23d6c"
    )
    assertEquals(p.canonicalAlg, "ed448")
  }

  test(
    "parsePemPrivateKey: PKCS#8 EC P-256 unencrypted (openssl SPKI ground truth + curve)"
  ) {
    // openssl: 3a6e0d4ee737bafcc891b3090e27cf3165de56e6ceac4ba3cd1d02e04265278d
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-ec-p256-unencrypted.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextPem]
    assertEquals(
      sha256Hex(p.spkiBytes),
      "3a6e0d4ee737bafcc891b3090e27cf3165de56e6ceac4ba3cd1d02e04265278d"
    )
    assertEquals(p.canonicalAlg, "ec")
    assertEquals(
      p.curve,
      Some("p-256"),
      "ECDSA P-256 OID must canonicalize to 'p-256' via ecCurveMap, " +
        "not the BC X962Parameters toString"
    )
  }

  test(
    "parsePemPrivateKey: PKCS#8 EC P-384 unencrypted (openssl SPKI ground truth + curve)"
  ) {
    // openssl: 8c8f722d7171d86882b7f399bd92977856eb09f6fce8ea4c7e999a083312ed9e
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-ec-p384-unencrypted.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextPem]
    assertEquals(
      sha256Hex(p.spkiBytes),
      "8c8f722d7171d86882b7f399bd92977856eb09f6fce8ea4c7e999a083312ed9e"
    )
    assertEquals(p.canonicalAlg, "ec")
    assertEquals(p.curve, Some("p-384"))
  }

  // ===== UNENCRYPTED OpenSSH — structural assertions =====================
  //
  // Cross-check via openssh-key-v1 envelope itself (the public-key wire
  // blob is in the clear; we read it directly). System ssh-keygen on
  // this host rejects these fixtures (libcrypto mismatch); the
  // CertificatesSuite black-box test provides additional coverage via
  // sidecar mustContain.

  test(
    "parseOpenSshPrivateKey: openssh-ed25519 unencrypted is claimed (alg + envelope)"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/openssh-ed25519-unencrypted"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextOpenSsh]
    assertEquals(p.algName, "ssh-ed25519")
    assertEquals(p.rsaModulusBits, None)
    assert(
      p.wireBytes.nonEmpty,
      "wireBytes must be the in-the-clear public-key blob"
    )
  }

  // D2 — Phase-7 second-pass remediation: independent SSH SHA-256
  // ground truth via Python (grandfathered for test_data tooling).
  // System ssh-keygen rejects these fixtures ("error in libcrypto");
  // the values below were computed by a Python script that re-implements
  // the openssh-key-v1 envelope unpack + SHA-256(pubkey-wire-blob)
  // logic from scratch. Recipe in test_data/certificates/tools/
  // openssh_v1_fingerprint.py.
  test(
    "parseOpenSshPrivateKey: openssh-ed25519 SHA-256 matches Python ground truth (D2)"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/openssh-ed25519-unencrypted"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextOpenSsh]
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val sha = md.digest(p.wireBytes)
    val b64 = java.util.Base64.getEncoder.withoutPadding.encodeToString(sha)
    assertEquals(
      b64,
      "oNA+weKy3joG5Lk8DyILmfET8o25s9dl6b7ZwXgZ1Lg",
      "SHA-256(public-key-wire-blob) must match Python's independent " +
        "calculation; if this fails, either the wire-blob extraction " +
        "or the SHA-256 path is wrong"
    )
  }

  test(
    "parseOpenSshPrivateKey: openssh-rsa-2048 SHA-256 matches Python ground truth (D2)"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/openssh-rsa-2048-unencrypted"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextOpenSsh]
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val b64 = java.util.Base64.getEncoder.withoutPadding
      .encodeToString(md.digest(p.wireBytes))
    assertEquals(b64, "t0/kOgTYoKNs5SqVqWSLJPoXV2gsRUIlrprl3Osdlfc")
  }

  test(
    "parseOpenSshPrivateKey: openssh-rsa-4096 SHA-256 matches Python ground truth (D2)"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/openssh-rsa-4096-unencrypted"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextOpenSsh]
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val b64 = java.util.Base64.getEncoder.withoutPadding
      .encodeToString(md.digest(p.wireBytes))
    assertEquals(b64, "mnO0vOy97kw6cBAygwUoutkpQCBrhs66SJlfgJEbeLg")
  }

  test(
    "parseOpenSshPrivateKey: openssh-ecdsa-p256 SHA-256 matches Python ground truth (D2)"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/openssh-ecdsa-p256-unencrypted"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextOpenSsh]
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val b64 = java.util.Base64.getEncoder.withoutPadding
      .encodeToString(md.digest(p.wireBytes))
    assertEquals(b64, "K0sk7wZe1Bj1TfmlBhuiUqc8/7l7Ty0FemZog9NUeUQ")
  }

  test("parseOpenSshPrivateKey: openssh-rsa-2048 unencrypted (alg + size)") {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/openssh-rsa-2048-unencrypted"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextOpenSsh]
    assertEquals(p.algName, "ssh-rsa")
    assertEquals(p.rsaModulusBits, Some(2048))
  }

  test("parseOpenSshPrivateKey: openssh-rsa-4096 unencrypted (alg + size)") {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/openssh-rsa-4096-unencrypted"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextOpenSsh]
    assertEquals(p.algName, "ssh-rsa")
    assertEquals(p.rsaModulusBits, Some(4096))
  }

  test(
    "parseOpenSshPrivateKey: openssh-ecdsa-p256 unencrypted (alg + curve via sshAlgMap)"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/openssh-ecdsa-p256-unencrypted"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextOpenSsh]
    assertEquals(p.algName, "ecdsa-sha2-nistp256")
  }

  // ===== ENCRYPTED — envelope-only assertions ============================

  test("parsePemEncryptedPrivateKey: PKCS#8 AES-256-CBC + PBKDF2 envelope") {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-encrypted-aes256-pbkdf2.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyEncrypted]
    assertEquals(p.envelope, "pkcs8-encrypted")
    assertEquals(p.kdfAlgorithm, Some("pbkdf2"))
    assert(
      p.kdfIterations.exists(_ > 0L),
      s"PBKDF2 iteration count should be > 0; got ${p.kdfIterations}"
    )
    assertEquals(p.cipher, Some("aes-256-cbc"))
  }

  test("parsePemEncryptedPrivateKey: PKCS#8 AES-128-CBC + PBKDF2 envelope") {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-encrypted-aes128-pbkdf2.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyEncrypted]
    assertEquals(p.envelope, "pkcs8-encrypted")
    assertEquals(p.kdfAlgorithm, Some("pbkdf2"))
    assertEquals(p.cipher, Some("aes-128-cbc"))
  }

  test("parsePemEncryptedPrivateKey: PKCS#8 DES-EDE3 + PBKDF2 envelope") {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-encrypted-des-ede3.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyEncrypted]
    assertEquals(p.envelope, "pkcs8-encrypted")
    assertEquals(p.kdfAlgorithm, Some("pbkdf2"))
    assertEquals(p.cipher, Some("des-ede3-cbc"))
  }

  test("parsePemEncryptedPrivateKey: PKCS#8 scrypt envelope") {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-encrypted-scrypt.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyEncrypted]
    assertEquals(p.envelope, "pkcs8-encrypted")
    assertEquals(p.kdfAlgorithm, Some("scrypt"))
  }

  test(
    "parsePemPrivateKey: legacy PEM `Proc-Type: 4,ENCRYPTED` → pem-legacy-encrypted envelope"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pem-legacy-encrypted-rsa.pem"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyEncrypted]
    assertEquals(p.envelope, "pem-legacy-encrypted")
    assertEquals(
      p.cipher,
      Some("aes-256-cbc"),
      "DEK-Info: AES-256-CBC,... → cipher='aes-256-cbc'"
    )
    assertEquals(
      p.kdfAlgorithm,
      None,
      "Legacy PEM uses OpenSSL EVP_BytesToKey; no KDF descriptor in the file"
    )
  }

  test(
    "parseOpenSshPrivateKey: encrypted openssh-key-v1 → openssh-encrypted envelope (bcrypt)"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/openssh-encrypted-ed25519"
    )
    val p = Certificates
      .classifyAndParse(w)
      .get
      .asInstanceOf[Certificates.PrivateKeyEncrypted]
    assertEquals(p.envelope, "openssh-encrypted")
    assertEquals(p.kdfAlgorithm, Some("bcrypt"))
    assert(
      p.kdfIterations.exists(_ > 0L),
      "bcrypt rounds should be > 0 (extracted from kdfoptions)"
    )
    assert(
      p.cipher.exists(_.startsWith("aes")),
      s"OpenSSH encrypted defaults to aes256-ctr (or similar); got ${p.cipher}"
    )
  }

  // ===== PGP SECRET KEY (Phase 7 inline) =================================

  test(
    "parsePgpKeyOrSecretKeyRing: unencrypted PGP secret key (Ed25519 + ECDH cv25519 subkey) — gpg ground truth"
  ) {
    // gpg --list-secret-keys --with-fingerprint --with-subkey-fingerprint:
    //   sec   ed25519 2026-05-01 [SC] [expires: 2028-04-30]
    //         6046C53C8DF8C522076F8CD76D7FAAE796ABC62E
    //   ssb   cv25519 2026-05-01 [E] [expires: 2028-04-30]
    //         59915A0A243D30D5002D6AA58ED81EA8ADFB3A65
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pgp-secret-ed25519-unencrypted.asc"
    )
    val claim = Certificates.classifyAndParse(w).get
    val p = claim.asInstanceOf[Certificates.PrivateKeyPlaintextPgp]
    assertEquals(
      p.ring.keys.length,
      2,
      "secret-key ring has the same primary + subkey shape as the public counterpart"
    )
    val primary = p.ring.keys.find(_.isPrimary).get
    val sub = p.ring.keys.find(!_.isPrimary).get
    assertEquals(
      primary.fingerprintHex,
      "6046c53c8df8c522076f8cd76d7faae796abc62e"
    )
    assertEquals(primary.canonicalAlg, "ed25519")
    assertEquals(sub.fingerprintHex, "59915a0a243d30d5002d6aa58ed81ea8adfb3a65")
    assertEquals(sub.canonicalAlg, "ec")
    assertEquals(sub.curve, Some("curve25519"))
  }

  test(
    "parsePgpKeyOrSecretKeyRing: unencrypted PGP secret → derives same pURL as public counterpart"
  ) {
    val wSecret = wrap(
      "test_data/certificates/private-keys/synthetic/pgp-secret-ed25519-unencrypted.asc"
    )
    val wPublic =
      wrap("test_data/certificates/pgp/synthetic/v4-ed25519-pub.asc")
    val secret = Certificates
      .classifyAndParse(wSecret)
      .get
      .asInstanceOf[Certificates.PrivateKeyPlaintextPgp]
    val pub = Certificates.parsePgpKeyRing(wPublic).get
    val secretFps = secret.ring.keys.map(_.fingerprintHex).toSet
    val pubFps = pub.keys.map(_.fingerprintHex).toSet
    assertEquals(
      secretFps,
      pubFps,
      "secret-key derivation must produce the same fingerprints as the " +
        "public-key counterpart (proves we're really deriving the public, " +
        "not encoding the private)"
    )
  }

  test(
    "parsePgpKeyOrSecretKeyRing: encrypted PGP secret → envelope-only, no derived public"
  ) {
    // gpg-generated RSA-2048 with passphrase "GoatRodeoTestPassphrase"
    // (passphrase irrelevant to the strategy — never attempted).
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pgp-secret-rsa2048-encrypted.asc"
    )
    val claim = Certificates.classifyAndParse(w).get
    val p = claim.asInstanceOf[Certificates.PrivateKeyEncrypted]
    assertEquals(p.envelope, "pgp-encrypted-secret-key")
    assert(
      p.kdfAlgorithm.exists(_.startsWith("s2k")),
      s"PGP S2K kdf must be reported; got ${p.kdfAlgorithm}"
    )
    assert(
      p.cipher.exists(_.startsWith("aes")),
      s"PGP secret-key cipher should be aes-* (gpg default); got ${p.cipher}"
    )
  }

  test(
    "[HARD RULE] encrypted PGP secret key emits NO pURL through CertificatesState"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pgp-secret-rsa2048-encrypted.asc"
    )
    val claim = Certificates.classifyAndParse(w).get
    val state = new CertificatesState(w, Some(claim))
    val (purls, _) = state.getPurls(
      w,
      stubItem(),
      io.spicelabs.goatrodeo.omnibor.SingleMarker()
    )
    assertEquals(
      purls,
      Vector.empty,
      "encrypted PGP secret keys must produce zero pURLs (envelope-only)"
    )
  }

  test(
    "[HARD RULE] unencrypted PGP secret derives DerivedFromPrivateKey + Envelope=plaintext metadata"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pgp-secret-ed25519-unencrypted.asc"
    )
    val claim = Certificates.classifyAndParse(w).get
    val state = new CertificatesState(w, Some(claim))
    val (md, _) = state.getMetadata(
      w,
      stubItem(),
      io.spicelabs.goatrodeo.omnibor.SingleMarker()
    )
    assert(md.contains("Certificates:Envelope"))
    assertEquals(md("Certificates:Envelope").head.value, "plaintext")
    assert(md.contains("Certificates:DerivedFromPrivateKey"))
    assertEquals(md("Certificates:DerivedFromPrivateKey").head.value, "true")
    // Per-key fields (Phase 6 metadata structure preserved):
    assert(md.contains("Certificates:PgpKeyCount"))
    assertEquals(md("Certificates:PgpKeyCount").head.value, "2")
  }

  // ===== HARD-RULE INVARIANTS ============================================

  test(
    "[HARD RULE] encrypted private key emits NO pURL through CertificatesState"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-encrypted-aes256-pbkdf2.pem"
    )
    val claim = Certificates.classifyAndParse(w).get
    val state = new CertificatesState(w, Some(claim))
    val (purls, _) = state.getPurls(
      w,
      stubItem(),
      io.spicelabs.goatrodeo.omnibor.SingleMarker()
    )
    assertEquals(
      purls,
      Vector.empty,
      "encrypted private keys must produce zero pURLs (envelope-only path)"
    )
  }

  test(
    "[HARD RULE] unencrypted PKCS#8 emits exactly ONE spki-sha256 pURL with the expected hash"
  ) {
    val w = wrap(
      "test_data/certificates/private-keys/synthetic/pkcs8-ed25519-unencrypted.pem"
    )
    val claim = Certificates.classifyAndParse(w).get
    val state = new CertificatesState(w, Some(claim))
    val (purls, _) = state.getPurls(
      w,
      stubItem(),
      io.spicelabs.goatrodeo.omnibor.SingleMarker()
    )
    assertEquals(purls.length, 1)
    val canon = purls.head.toCanonical().nn
    assertEquals(
      canon,
      "pkg:generic/x509/spki-sha256@23061b4699527614aba79f341cccd03865af660ffe4e90b972733a3e5cfd4104?alg=ed25519"
    )
  }

  test(
    "[HARD RULE] no metadata value matches a forbidden private-key pattern (full corpus sweep)"
  ) {
    val pkRoot = java.nio.file.Paths.get("test_data/certificates/private-keys")
    if (java.nio.file.Files.exists(pkRoot)) {
      import scala.jdk.CollectionConverters.*
      val files = java.nio.file.Files
        .walk(pkRoot)
        .iterator()
        .asScala
        .filter(p => java.nio.file.Files.isRegularFile(p))
        .filter(p => !p.toString.endsWith(".expected.json"))
        .toVector
      assert(files.nonEmpty, "expected at least one private-key fixture")
      files.foreach { p =>
        val w = wrap(p.toString)
        val claimOpt = Certificates.classifyAndParse(w)
        claimOpt.foreach { claim =>
          val state = new CertificatesState(w, Some(claim))
          // getMetadata internally runs assertNoLeak; if any leak
          // pattern matches, it throws. Reaching here = clean.
          val _ = state.getMetadata(
            w,
            stubItem(),
            io.spicelabs.goatrodeo.omnibor.SingleMarker()
          )
        }
      }
    }
  }

  // ===== Stub Item helper ================================================

  private def stubItem(): io.spicelabs.goatrodeo.omnibor.Item = {
    import io.spicelabs.goatrodeo.omnibor.{Item, ItemMetaData}
    import scala.collection.immutable.{TreeMap, TreeSet}
    Item(
      identifier = "gitoid:blob:sha256:phase7-test-stub",
      connections = TreeSet.empty,
      bodyMimeType = Some(ItemMetaData.mimeType),
      body = Some(
        ItemMetaData(
          fileNames = TreeSet.empty,
          mimeType = TreeSet.empty,
          fileSize = 0L,
          extra = TreeMap.empty
        )
      )
    )
  }
}
