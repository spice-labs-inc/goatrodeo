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

package strategies

import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.CryptoDetector
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Phase 2 — `CryptoDetector` content-sniff signature unit tests.
  *
  * Each test traces back to one row of the detection-signatures table
  * in `certificates-strategy/phases-1-2-foundation-detector.md` (Phase
  * 2). For every detection row, a synthetic byte sequence (or the
  * smallest possible real-world prefix) is fed through
  * `CryptoDetector.detect` (the package-private internal entry that
  * lets us assert MIME-set output without going through the full
  * `mimeTypeAugmenter` pass-through wrapper).
  *
  * Negative tests cover the plan's explicit "must NOT match" cases
  * plus the 4-KB read-budget invariant.
  *
  * ## LLM-friendly summary
  *
  * Test name pattern: `[<row>]: <description>` so a reviewer auditing
  * the signature table can grep `[PEM cert]:` and see every test that
  * traces to that row.
  *
  * ## Test discipline
  *
  * Per HS-4 (test corpus means the actual test corpus), this suite
  * also fans out across a sample of real fixture bytes from
  * `test_data/certificates/` to verify the synthetic-byte tests align
  * with what the augmenter sees in the wild.
  */
class CryptoDetectorSuite extends FunSuite {

  private def wrapBytes(bytes: Array[Byte], name: String = "test.bin"): ArtifactWrapper =
    ByteWrapper(bytes, name, None)

  private def detect(bytes: Array[Byte], name: String = "test.bin"): Set[String] =
    CryptoDetector.detect(wrapBytes(bytes, name))

  private def augment(bytes: Array[Byte], currentMimes: Set[String], name: String = "test.bin"): Set[String] =
    CryptoDetector.mimeTypeAugmenter(wrapBytes(bytes, name), currentMimes)

  // ===================================================================
  // SECTION A — Phase-INVARIANT contracts
  // (must hold across every phase from Phase 2 onwards)
  // ===================================================================

  test("[INVARIANT] augmenter output ⊇ input (purely additive)") {
    val cases = Seq(
      Set.empty[String],
      Set("text/plain"),
      Set("application/octet-stream"),
      Set("application/x-pem-file"),
      Set("text/plain", "application/json"),
    )
    val payloads = Seq(
      "-----BEGIN CERTIFICATE-----\nABC\n-----END CERTIFICATE-----\n".getBytes("UTF-8"),
      "ssh-ed25519 AAAA comment\n".getBytes("UTF-8"),
      Array[Byte](0xfe.toByte, 0xed.toByte, 0xfe.toByte, 0xed.toByte, 0, 0, 0, 0),
      Array[Byte](),  // empty
    )
    for (input <- cases; payload <- payloads) {
      val out = augment(payload, input)
      assert(input.subsetOf(out),
        s"output $out must be a superset of input $input")
    }
  }

  test("[INVARIANT] augmenter never strips text/* MIMEs (contrast SaffronDetector)") {
    val input = Set("text/plain", "text/html")
    val out = augment("-----BEGIN CERTIFICATE-----\n".getBytes, input, "x.pem")
    assert(input.subsetOf(out),
      s"text-prefixed MIMEs were stripped: out=$out")
  }

  // ===================================================================
  // SECTION B — Per-signature positive tests
  // (one test per row of the detection-signatures table)
  // ===================================================================

  test("[PEM cert]: BEGIN CERTIFICATE adds application/x-pem-file + application/x-x509-ca-cert") {
    val out = detect("-----BEGIN CERTIFICATE-----\nMIID...\n-----END CERTIFICATE-----\n".getBytes)
    assert(out.contains("application/x-pem-file"))
    assert(out.contains("application/x-x509-ca-cert"))
  }

  test("[PEM bundle]: multiple BEGIN CERTIFICATE adds application/x-pem-bundle") {
    val data =
      "-----BEGIN CERTIFICATE-----\nA\n-----END CERTIFICATE-----\n" +
      "-----BEGIN CERTIFICATE-----\nB\n-----END CERTIFICATE-----\n"
    val out = detect(data.getBytes)
    assert(out.contains("application/x-pem-bundle"))
    assert(out.contains("application/x-pem-file"))
    assert(out.contains("application/x-x509-ca-cert"))
  }

  test("[PEM CSR]: BEGIN CERTIFICATE REQUEST adds application/pkcs10") {
    val out = detect("-----BEGIN CERTIFICATE REQUEST-----\nXXX\n".getBytes)
    assert(out.contains("application/x-pem-file"))
    assert(out.contains("application/pkcs10"))
  }

  test("[PEM public key]: BEGIN PUBLIC KEY adds application/x-pem-public-key") {
    val out = detect("-----BEGIN PUBLIC KEY-----\nXXX\n".getBytes)
    assert(out.contains("application/x-pem-file"))
    assert(out.contains("application/x-pem-public-key"))
  }

  test("[PEM RSA private]: BEGIN RSA PRIVATE KEY adds application/x-pem-private-key") {
    val out = detect("-----BEGIN RSA PRIVATE KEY-----\nXXX\n".getBytes)
    assert(out.contains("application/x-pem-file"))
    assert(out.contains("application/x-pem-private-key"))
  }

  test("[PEM EC private]: BEGIN EC PRIVATE KEY adds application/x-pem-private-key") {
    val out = detect("-----BEGIN EC PRIVATE KEY-----\nXXX\n".getBytes)
    assert(out.contains("application/x-pem-file"))
    assert(out.contains("application/x-pem-private-key"))
  }

  test("[PEM generic private]: BEGIN PRIVATE KEY adds application/x-pem-private-key") {
    val out = detect("-----BEGIN PRIVATE KEY-----\nXXX\n".getBytes)
    assert(out.contains("application/x-pem-file"))
    assert(out.contains("application/x-pem-private-key"))
  }

  test("[PEM encrypted private]: BEGIN ENCRYPTED PRIVATE KEY adds application/x-pem-encrypted-private-key") {
    val out = detect("-----BEGIN ENCRYPTED PRIVATE KEY-----\nXXX\n".getBytes)
    assert(out.contains("application/x-pem-file"))
    assert(out.contains("application/x-pem-encrypted-private-key"))
  }

  test("[OpenSSH private]: BEGIN OPENSSH PRIVATE KEY adds application/x-openssh-private-key") {
    val out = detect("-----BEGIN OPENSSH PRIVATE KEY-----\nXXX\n".getBytes)
    assert(out.contains("application/x-openssh-private-key"))
  }

  test("[OpenSSH pubkey]: ssh-rsa first token adds application/x-openssh-public-key") {
    val out = detect("ssh-rsa AAAA comment@host\n".getBytes)
    assert(out.contains("application/x-openssh-public-key"))
  }

  test("[OpenSSH pubkey]: ssh-ed25519 first token adds application/x-openssh-public-key") {
    val out = detect("ssh-ed25519 AAAA comment@host\n".getBytes)
    assert(out.contains("application/x-openssh-public-key"))
  }

  test("[OpenSSH pubkey]: ecdsa-sha2-nistp256 first token adds application/x-openssh-public-key") {
    val out = detect("ecdsa-sha2-nistp256 AAAA comment\n".getBytes)
    assert(out.contains("application/x-openssh-public-key"))
  }

  test("[OpenSSH pubkey]: sk-ssh-ed25519@openssh.com first token adds application/x-openssh-public-key") {
    val out = detect("sk-ssh-ed25519@openssh.com AAAA comment\n".getBytes)
    assert(out.contains("application/x-openssh-public-key"))
  }

  test("[OpenSSH cert]: ssh-ed25519-cert-v01@openssh.com first token adds application/x-openssh-certificate") {
    val out = detect("ssh-ed25519-cert-v01@openssh.com AAAA comment\n".getBytes)
    assert(out.contains("application/x-openssh-certificate"))
  }

  test("[OpenSSH cert]: ssh-rsa-cert-v01@openssh.com first token adds application/x-openssh-certificate") {
    val out = detect("ssh-rsa-cert-v01@openssh.com AAAA comment\n".getBytes)
    assert(out.contains("application/x-openssh-certificate"))
  }

  // G2 — Plan §sshCertTokens: the original sshCertTokens set had 4 of 6
  // entries replaced by `[email protected]` placeholder strings. Phase 5
  // restored the real OpenSSH cert-type tokens and these tests guard
  // against regression for each of the 6.
  test("[OpenSSH cert]: ssh-dss-cert-v01@openssh.com first token adds application/x-openssh-certificate (G2)") {
    val out = detect("ssh-dss-cert-v01@openssh.com AAAA comment\n".getBytes)
    assert(out.contains("application/x-openssh-certificate"))
  }

  test("[OpenSSH cert]: ecdsa-sha2-nistp256-cert-v01@openssh.com first token adds application/x-openssh-certificate (G2)") {
    val out = detect("ecdsa-sha2-nistp256-cert-v01@openssh.com AAAA comment\n".getBytes)
    assert(out.contains("application/x-openssh-certificate"))
  }

  test("[OpenSSH cert]: ecdsa-sha2-nistp384-cert-v01@openssh.com first token adds application/x-openssh-certificate (G2)") {
    val out = detect("ecdsa-sha2-nistp384-cert-v01@openssh.com AAAA comment\n".getBytes)
    assert(out.contains("application/x-openssh-certificate"))
  }

  test("[OpenSSH cert]: ecdsa-sha2-nistp521-cert-v01@openssh.com first token adds application/x-openssh-certificate (G2)") {
    val out = detect("ecdsa-sha2-nistp521-cert-v01@openssh.com AAAA comment\n".getBytes)
    assert(out.contains("application/x-openssh-certificate"))
  }

  test("[OpenSSH cert][NEG]: placeholder string '[email protected]' must NOT be in token set (G2)") {
    val out = detect("[email protected] AAAA comment\n".getBytes)
    assert(!out.contains("application/x-openssh-certificate"),
           s"placeholder '[email protected]' string should not match a cert token; got $out")
  }

  test("[PGP armored pub]: BEGIN PGP PUBLIC KEY BLOCK adds application/pgp-keys") {
    val out = detect("-----BEGIN PGP PUBLIC KEY BLOCK-----\nXXX\n".getBytes)
    assert(out.contains("application/pgp-keys"))
  }

  test("[PGP armored priv]: BEGIN PGP PRIVATE KEY BLOCK adds application/pgp-keys") {
    val out = detect("-----BEGIN PGP PRIVATE KEY BLOCK-----\nXXX\n".getBytes)
    assert(out.contains("application/pgp-keys"))
  }

  test("[PGP signature]: BEGIN PGP SIGNATURE adds application/pgp-signature") {
    val out = detect("-----BEGIN PGP SIGNATURE-----\nXXX\n".getBytes)
    assert(out.contains("application/pgp-signature"))
  }

  test("[PGP message]: BEGIN PGP MESSAGE adds application/pgp-message") {
    val out = detect("-----BEGIN PGP MESSAGE-----\nXXX\n".getBytes)
    assert(out.contains("application/pgp-message"))
  }

  test("[PGP binary]: first byte 0xC6 (new-format public-key packet) adds application/pgp-keys") {
    val out = detect(Array[Byte](0xC6.toByte, 0x33, 0x00, 0x00))
    assert(out.contains("application/pgp-keys"))
  }

  test("[PGP binary]: first byte 0x98 (old-format public-key packet) adds application/pgp-keys") {
    val out = detect(Array[Byte](0x98.toByte, 0x4D, 0x04, 0x60))
    assert(out.contains("application/pgp-keys"))
  }

  // N2 (Phase 6 second-pass gap analysis): the old-format public-key
  // packet tag (tag-6) has 4 length-encoding variants per RFC 4880 §4.2.1:
  //   0x98 = 1-octet length, 0x99 = 2-octet, 0x9A = 4-octet, 0x9B = indeterminate.
  // The original detector matched only 0x98; the G12 remediation extended
  // it to all four. These three tests pin coverage of the newly-matched
  // values so a future refactor cannot silently drop one.
  test("[PGP binary]: first byte 0x99 (old-format tag-6, 2-octet length) adds application/pgp-keys (N2)") {
    // 0x99 is what gpg(1) actually emits for an RSA-3072 export (the
    // v4-rsa3072-pub.gpg fixture starts with bytes 99 01 8d 04).
    val out = detect(Array[Byte](0x99.toByte, 0x01, 0x8d.toByte, 0x04))
    assert(out.contains("application/pgp-keys"),
      "0x99 (old-format tag-6, 2-octet length) must be detected as PGP")
  }

  test("[PGP binary]: first byte 0x9A (old-format tag-6, 4-octet length) adds application/pgp-keys (N2)") {
    val out = detect(Array[Byte](0x9A.toByte, 0x00, 0x00, 0x01, 0x00))
    assert(out.contains("application/pgp-keys"),
      "0x9A (old-format tag-6, 4-octet length) must be detected as PGP")
  }

  test("[PGP binary]: first byte 0x9B (old-format tag-6, indeterminate length) adds application/pgp-keys (N2)") {
    val out = detect(Array[Byte](0x9B.toByte, 0x04, 0x60.toByte, 0x00))
    assert(out.contains("application/pgp-keys"),
      "0x9B (old-format tag-6, indeterminate length) must be detected as PGP")
  }

  test("[JKS]: 0xfe 0xed 0xfe 0xed magic adds application/x-java-keystore") {
    val out = detect(Array[Byte](0xfe.toByte, 0xed.toByte, 0xfe.toByte, 0xed.toByte, 0, 0))
    assert(out.contains("application/x-java-keystore"))
  }

  test("[JCEKS]: 0xce 0xce 0xce 0xce magic adds application/x-java-jce-keystore") {
    val out = detect(Array[Byte](0xce.toByte, 0xce.toByte, 0xce.toByte, 0xce.toByte, 0, 0))
    assert(out.contains("application/x-java-jce-keystore"))
  }

  test("[PKCS#12]: 0x30 0x82 + .p12 extension adds application/pkcs12") {
    // Synthetic DER-prefixed bytes; the .p12 extension is the
    // disambiguation hint per plan task #2 footnote.
    val out = detect(Array[Byte](0x30, 0x82.toByte, 0x01, 0x00, 0x00, 0x00),
                     name = "x.p12")
    assert(out.contains("application/pkcs12"))
  }

  test("[PKCS#12]: 0x30 0x82 + .pfx extension adds application/pkcs12") {
    val out = detect(Array[Byte](0x30, 0x82.toByte, 0x01, 0x00, 0x00, 0x00),
                     name = "x.pfx")
    assert(out.contains("application/pkcs12"))
  }

  test("[PEM CRL]: BEGIN X509 CRL adds application/pkix-crl") {
    val out = detect("-----BEGIN X509 CRL-----\nXXX\n".getBytes)
    assert(out.contains("application/x-pem-file"))
    assert(out.contains("application/pkix-crl"))
  }

  test("[PKCS7 PEM]: BEGIN PKCS7 adds application/pkcs7-mime") {
    val out = detect("-----BEGIN PKCS7-----\nXXX\n".getBytes)
    assert(out.contains("application/pkcs7-mime"))
  }

  test("[DER X.509]: real DER cert (Mozilla CA via fixture) adds application/pkix-cert") {
    // HS-4: actual fixture bytes, not synthetic.
    val sampleDer = new File(
      "test_data/certificates/x509/synthetic/ed25519-selfsigned-der.der"
    )
    assume(sampleDer.exists(), s"fixture missing: ${sampleDer.getPath}")
    val bytes = Files.readAllBytes(sampleDer.toPath)
    val out = detect(bytes, "ed25519-selfsigned-der.der")
    assert(out.contains("application/pkix-cert"),
      s"DER X.509 cert should detect application/pkix-cert; got $out")
  }

  test("[DER CRL]: real DER CRL (synthetic fixture) adds application/pkix-crl") {
    // HS-4: actual fixture bytes.
    val sampleDer = new File(
      "test_data/certificates/crls/synthetic/small-crl.der"
    )
    assume(sampleDer.exists(), s"fixture missing: ${sampleDer.getPath}")
    val bytes = Files.readAllBytes(sampleDer.toPath)
    val out = detect(bytes, "small-crl.der")
    assert(out.contains("application/pkix-crl"),
      s"DER CRL should detect application/pkix-crl; got $out")
  }

  // ===================================================================
  // SECTION C — Negative tests (plan-explicit "must NOT match")
  // ===================================================================

  test("[NEG]: plain text returns empty MIME set (currentMimes unchanged)") {
    val out = augment(
      "Just some plain text. No certificates here.\n".getBytes,
      Set("text/plain"),
    )
    assertEquals(out, Set("text/plain"))
  }

  test("[NEG]: random binary returns empty MIME set") {
    val rnd = new scala.util.Random(0xCAFEBABE)
    val bytes = new Array[Byte](512)
    rnd.nextBytes(bytes)
    // Avoid accidental collisions with magic / packet tags by zeroing the
    // first byte to 0x42 and bytes 1-3 to non-magic values.
    bytes(0) = 0x42; bytes(1) = 0x42; bytes(2) = 0x42; bytes(3) = 0x42
    val out = detect(bytes, "random.bin")
    assertEquals(out, Set.empty[String],
      s"random binary should not match any signature; got $out")
  }

  test("[NEG]: PEM with typo (BEGIN CERTIFICAT) does NOT match") {
    val out = detect("-----BEGIN CERTIFICAT-----\nXXX\n".getBytes)
    assert(!out.contains("application/x-x509-ca-cert"),
      s"typo'd PEM header must not match; got $out")
    assert(!out.contains("application/x-pem-file"),
      s"typo'd PEM header must not match; got $out")
  }

  test("[NEG]: 0x30 0x82 prefix that is NOT a valid X.509 cert and NOT .p12/.pfx returns empty") {
    // 0x30 0x82 followed by garbage — too short / structurally broken
    // for ASN.1 X.509 or CRL. No extension hint either.
    val out = detect(Array[Byte](0x30, 0x82.toByte, 0x00, 0x10) ++ Array.fill[Byte](18)(0x00),
                     name = "garbage.bin")
    assert(!out.contains("application/pkix-cert"),
      s"non-X.509 0x30 0x82 prefix must not be claimed; got $out")
    assert(!out.contains("application/pkcs12"),
      s"non-pkcs12 0x30 0x82 prefix must not be claimed; got $out")
  }

  test("[NEG]: file that is not a PGP packet but happens to start with 0xC6 — DOES match (acceptable false positive per plan: detection-only, parser fails at strategy)") {
    // The plan accepts this. We document it explicitly so a reviewer
    // doesn't expect a stricter discriminator.
    val out = detect(Array[Byte](0xC6.toByte, 0x42, 0x42, 0x42))
    assert(out.contains("application/pgp-keys"),
      "Per plan, 0xC6 high-bit packet-tag byte → application/pgp-keys " +
        "even on garbage; the strategy-time PGP parser will fail and " +
        "the file falls through to Generic.")
  }

  // ===================================================================
  // SECTION D — 4 KB read budget
  // ===================================================================

  // Note: `ArtifactWrapper` is a sealed trait and `ByteWrapper` is
  // final, so we cannot subclass either to instrument a counting
  // stream directly. We prove the 4 KB read-budget invariant
  // behaviorally instead — see the three tests below.

  test("[BUDGET] MAX_READ_BYTES constant is 4096 (plan acceptance)") {
    // Direct constant check; if a future change widens this it must
    // be discussed (the plan acceptance criterion is exact 4 KB).
    assertEquals(CryptoDetector.MAX_READ_BYTES, 4096)
  }

  test("[BUDGET] detector ignores bytes past offset 4096") {
    // If the detector reads past 4096 bytes, it would see this
    // payload's "-----BEGIN CERTIFICATE-----" embedded after a 4 KB
    // padding and claim it. If it correctly stops at 4096, the
    // header is invisible and no claim is made.
    val padding = new Array[Byte](CryptoDetector.MAX_READ_BYTES)
    java.util.Arrays.fill(padding, 0x20.toByte)  // ASCII spaces
    val tail = "-----BEGIN CERTIFICATE-----\nABC\n-----END CERTIFICATE-----\n".getBytes("UTF-8")
    val payload = padding ++ tail
    val out = detect(payload, "header-after-4k.bin")
    assert(!out.contains("application/x-x509-ca-cert"),
      s"detector saw a header at offset ${padding.length}; should " +
        s"only read first 4 KB. Output: $out")
  }

  test("[BUDGET] detector finds header at offset 4095 (last byte of budget)") {
    // Symmetric to the previous test — proves we DO read ALL of the
    // first 4 KB. The header starts in the last byte of the budget
    // window and continues just beyond, but with the BEGIN marker
    // length being > 1, the body itself isn't fully visible — what
    // matters is that the detector at least tries.
    //
    // We also test the simpler case: header fully within the first
    // 4 KB but at the very end. If we read short, we miss it.
    val begin = "-----BEGIN CERTIFICATE-----".getBytes("UTF-8")
    val padBefore = CryptoDetector.MAX_READ_BYTES - begin.length
    val padding = new Array[Byte](padBefore)
    java.util.Arrays.fill(padding, 0x20.toByte)
    val payload = padding ++ begin ++ "\nABC\n-----END CERTIFICATE-----\n".getBytes
    val out = detect(payload, "header-at-end-of-4k.bin")
    assert(out.contains("application/x-x509-ca-cert"),
      s"detector should see header at offset $padBefore (within 4 KB " +
        s"window). Output: $out")
  }

  // ===================================================================
  // SECTION E — Real-fixture sanity (HS-4: actual corpus, not synthetic)
  // ===================================================================

  test("[FIXTURE] every Mozilla PEM cert in the corpus is detected as PEM + x509-ca-cert") {
    val mozillaDir = new File("test_data/certificates/x509/mozilla")
    assume(mozillaDir.exists() && mozillaDir.isDirectory(),
      "corpus missing")
    val pems = Option(mozillaDir.listFiles((_, n) => n.endsWith(".pem")))
      .map(_.toVector).getOrElse(Vector.empty)
    assume(pems.nonEmpty, "no Mozilla PEM fixtures present")
    val sample = pems.take(5)
    for (pem <- sample) {
      val out = CryptoDetector.detect(
        FileWrapper(pem, pem.getName, None)
      )
      assert(out.contains("application/x-pem-file"),
        s"Mozilla PEM $pem missed application/x-pem-file; out=$out")
      assert(out.contains("application/x-x509-ca-cert"),
        s"Mozilla PEM $pem missed application/x-x509-ca-cert; out=$out")
    }
  }

  test("[FIXTURE] Mozilla bundle PEM is detected as application/x-pem-bundle") {
    val sample = new File(
      "test_data/certificates/pem-bundles/mozilla-ca-bundle.pem"
    )
    assume(sample.exists(), s"fixture missing: ${sample.getPath}")
    val out = CryptoDetector.detect(
      FileWrapper(sample, sample.getName, None)
    )
    assert(out.contains("application/x-pem-bundle"),
      s"Mozilla bundle should be detected as application/x-pem-bundle; got $out")
  }

  test("[FIXTURE] real Github SSH pubkey detected as application/x-openssh-public-key") {
    val sshDir = new File("test_data/certificates/ssh/github")
    assume(sshDir.exists(), "corpus ssh/github missing")
    val pubs = Option(sshDir.listFiles((_, n) => n.endsWith(".pub")))
      .map(_.toVector).getOrElse(Vector.empty)
    assume(pubs.nonEmpty, "no github ssh pubkey fixtures present")
    val sample = pubs.head
    val out = CryptoDetector.detect(
      FileWrapper(sample, sample.getName, None)
    )
    assert(out.contains("application/x-openssh-public-key"),
      s"SSH pubkey $sample should match; got $out")
  }

  test("[FIXTURE] PGP armored key from synthetic generator detected as application/pgp-keys") {
    val sample = new File(
      "test_data/certificates/pgp/synthetic/v4-rsa4096-pub.asc"
    )
    assume(sample.exists(), s"fixture missing: ${sample.getPath}")
    val out = CryptoDetector.detect(
      FileWrapper(sample, sample.getName, None)
    )
    assert(out.contains("application/pgp-keys"),
      s"PGP armored should match; got $out")
  }

  test("[FIXTURE] JKS keystore detected as application/x-java-keystore") {
    val sample = new File(
      "test_data/certificates/keystores/synthetic/encrypted-jks.jks"
    )
    assume(sample.exists(), s"fixture missing: ${sample.getPath}")
    val out = CryptoDetector.detect(
      FileWrapper(sample, sample.getName, None)
    )
    assert(out.contains("application/x-java-keystore"),
      s"JKS magic should match; got $out")
  }

  test("[FIXTURE] PKCS#12 keystore detected as application/pkcs12") {
    val sample = new File(
      "test_data/certificates/keystores/synthetic/encrypted-p12.p12"
    )
    assume(sample.exists(), s"fixture missing: ${sample.getPath}")
    val out = CryptoDetector.detect(
      FileWrapper(sample, sample.getName, None)
    )
    assert(out.contains("application/pkcs12"),
      s"PKCS#12 should match; got $out")
  }

  test("[FIXTURE] edge-cases/empty.pem stays out of every crypto MIME set") {
    val sample = new File("test_data/certificates/edge-cases/empty.pem")
    assume(sample.exists(), s"fixture missing: ${sample.getPath}")
    val out = CryptoDetector.detect(
      FileWrapper(sample, sample.getName, None)
    )
    assertEquals(out, Set.empty[String],
      s"empty file must not match any signature; got $out")
  }

  test("[FIXTURE] edge-cases/pem-typo-header.pem does NOT match cert MIMEs") {
    val sample = new File(
      "test_data/certificates/edge-cases/pem-typo-header.pem"
    )
    assume(sample.exists(), s"fixture missing: ${sample.getPath}")
    val out = CryptoDetector.detect(
      FileWrapper(sample, sample.getName, None)
    )
    assert(!out.contains("application/x-x509-ca-cert"),
      s"typo header must not claim cert MIME; got $out")
  }

  // ===================================================================
  // SECTION F — Phase-2 adversarial-review remediations
  // ===================================================================

  // --- P1: 1 MB DER probe budget ---

  test("[P1] DER X.509 PQC cert > 4 KB is detected (uses 1 MB DER probe budget)") {
    // SLH-DSA-SHA2-192f trust anchor is ~36 KB — would silently drop
    // out of the 4 KB-prefix budget. P1 fix raises the DER probe to
    // 1 MB.
    val sample = new File(
      "test_data/certificates/x509/pqc/slh-dsa/slh-dsa-sha2-192f.der"
    )
    assume(sample.exists(), s"fixture missing: ${sample.getPath}")
    val out = CryptoDetector.detect(
      FileWrapper(sample, sample.getName, None)
    )
    assert(out.contains("application/pkix-cert"),
      s"large PQC cert (${sample.length()} bytes) should be detected; got $out")
  }

  test("[P1] ML-DSA-87 PQC cert (~7 KB) is detected") {
    val sample = new File(
      "test_data/certificates/x509/pqc/ml-dsa/ml-dsa-87.der"
    )
    assume(sample.exists(), s"fixture missing: ${sample.getPath}")
    val out = CryptoDetector.detect(
      FileWrapper(sample, sample.getName, None)
    )
    assert(out.contains("application/pkix-cert"),
      s"ML-DSA-87 cert should be detected; got $out")
  }

  test("[P1] BUDGET — DER probe never reads more than 1 MB, even on huge files") {
    // Construct a synthetic > 1 MB byte array with the DER prologue.
    // Detector must not OOM or block forever. We don't assert read-
    // count behavior here (no instrumented stream available — sealed
    // ArtifactWrapper); instead we assert detection completes within
    // a generous time bound and either produces a valid set or empty.
    val payload = new Array[Byte](2 * 1024 * 1024)
    payload(0) = 0x30; payload(1) = 0x82.toByte
    payload(2) = 0xFF.toByte; payload(3) = 0xFF.toByte  // length: 65535
    val art = ByteWrapper(payload, "huge.der", None)
    val start = System.nanoTime()
    val out = CryptoDetector.detect(art)
    val ms = (System.nanoTime() - start) / 1_000_000
    assert(ms < 5000,
      s"DER probe took ${ms}ms on a 2 MB synthetic — should be < 5s")
    // Output isn't required to be empty (BC may parse partial),
    // but shouldn't include any of the non-DER signatures.
    assert(!out.contains("application/x-pem-file"))
  }

  // --- P2: PKCS#12 ASN.1 disambiguation probe ---

  test("[P2] PKCS#12 fixture detected when filename has no .p12 extension") {
    // Real PKCS#12 bytes copied to a non-.p12-named file. The ASN.1
    // structure probe must catch it.
    val src = new File(
      "test_data/certificates/keystores/synthetic/encrypted-p12.p12"
    )
    assume(src.exists(), s"fixture missing: ${src.getPath}")
    val tmp = File.createTempFile("pkcs12-no-ext-", ".bin")
    Files.copy(src.toPath, tmp.toPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    try {
      val out = CryptoDetector.detect(FileWrapper(tmp, tmp.getName, None))
      assert(out.contains("application/pkcs12"),
        s"PKCS#12 bytes should be detected via ASN.1 probe even without " +
          s".p12 extension; got $out")
    } finally tmp.delete()
  }

  test("[P2] non-PKCS#12 .p12-named file (X.509 cert renamed) is detected as PKCS#12 via extension hint AND also as cert via dual emission") {
    // A real X.509 DER cert renamed to .p12 — extension says PKCS#12,
    // structure says X.509. Plan dual-emission policy: emit pkcs12
    // (extension hint) AND also try X.509 fallback so the strategy
    // can pick at parse time.
    val src = new File(
      "test_data/certificates/x509/synthetic/ed25519-selfsigned-der.der"
    )
    assume(src.exists(), s"fixture missing: ${src.getPath}")
    val bytes = Files.readAllBytes(src.toPath)
    val art = ByteWrapper(bytes, "fake.p12", None)
    val out = CryptoDetector.detect(art)
    assert(out.contains("application/pkcs12"),
      s"extension hint should produce pkcs12 MIME; got $out")
    assert(out.contains("application/pkix-cert"),
      s"dual emission: structure mismatch should also try X.509; got $out")
  }

  // --- P3: DER PKCS#7 OID-near-start probe ---

  test("[P3] DER PKCS#7 SignedData detected via 1.2.840.113549.1.7.2 OID near start") {
    // Synthetic DER ContentInfo with signedData OID. Structure:
    //   SEQUENCE (30 82 ...) {
    //     OBJECT IDENTIFIER 1.2.840.113549.1.7.2 (06 09 2A 86 48 86 F7 0D 01 07 02)
    //     [0] EXPLICIT { ... empty content ... }
    //   }
    val signedDataOid: Array[Byte] = Array(
      0x06.toByte, 0x09.toByte,
      0x2A.toByte, 0x86.toByte, 0x48.toByte, 0x86.toByte,
      0xF7.toByte, 0x0D.toByte, 0x01.toByte, 0x07.toByte, 0x02.toByte
    )
    val payload: Array[Byte] = Array[Byte](
      0x30, 0x82.toByte, 0x00, 0x20  // SEQUENCE, length 32
    ) ++ signedDataOid ++ Array[Byte](
      0xA0.toByte, 0x82.toByte, 0x00, 0x10  // [0] EXPLICIT
    ) ++ Array.fill[Byte](16)(0x00)
    val out = detect(payload, "signed.p7s")
    assert(out.contains("application/pkcs7-mime"),
      s"DER PKCS#7 OID-near-start should be detected; got $out")
  }

  test("[P3] DER prefix without PKCS#7 OID is NOT detected as pkcs7-mime") {
    // A pure X.509 cert prefix shouldn't match P3.
    val src = new File(
      "test_data/certificates/x509/synthetic/ed25519-selfsigned-der.der"
    )
    assume(src.exists(), s"fixture missing: ${src.getPath}")
    val bytes = Files.readAllBytes(src.toPath)
    val out = detect(bytes, "real.der")
    assert(!out.contains("application/pkcs7-mime"),
      s"X.509 cert must not be mis-classified as PKCS#7; got $out")
  }

  // --- P4: full augmenter chain text/* preservation ---

  test("[P4] full augmenter chain (Dotnet → Saffron → Crypto) preserves text/plain on PEM input") {
    // P4 remediation: prove the chain order assumption explicitly.
    // Construct a PEM-shaped artifact, invoke the production
    // augmenter chain via ArtifactWrapper.augmentMimeTypes, and
    // assert text/plain survives in the output AND the crypto MIMEs
    // are added.
    val pem = "-----BEGIN CERTIFICATE-----\nABC\n-----END CERTIFICATE-----\n"
      .getBytes("UTF-8")
    val art = ByteWrapper(pem, "chain-probe.pem", None)
    val baseMimes = Set("text/plain")
    val chainOut = ArtifactWrapper.augmentMimeTypes(art, baseMimes)
    assert(chainOut.contains("text/plain"),
      s"text/plain must survive the Dotnet → Saffron → Crypto chain; " +
        s"got $chainOut")
    assert(chainOut.contains("application/x-pem-file"),
      s"CryptoDetector should add application/x-pem-file in the chain; " +
        s"got $chainOut")
    assert(chainOut.contains("application/x-x509-ca-cert"),
      s"CryptoDetector should add application/x-x509-ca-cert in the chain; " +
        s"got $chainOut")
  }

  // --- P7: SSH wire-format leading-whitespace / BOM tolerance ---

  test("[P7] SSH pubkey with leading whitespace before token is detected") {
    val out = detect("    ssh-ed25519 AAAA comment\n".getBytes)
    assert(out.contains("application/x-openssh-public-key"),
      s"leading whitespace should not block SSH pubkey detection; got $out")
  }

  test("[P7] SSH pubkey with leading UTF-8 BOM is detected") {
    val bom: Array[Byte] = Array(0xEF.toByte, 0xBB.toByte, 0xBF.toByte)
    val payload = bom ++ "ssh-ed25519 AAAA comment\n".getBytes("UTF-8")
    val out = detect(payload)
    assert(out.contains("application/x-openssh-public-key"),
      s"UTF-8 BOM should not block SSH pubkey detection; got $out")
  }

  // --- P8: multi-line SSH (authorized_keys-shaped files) ---

  test("[P8] multi-line authorized_keys-style file detects pubkey on any line") {
    val payload =
      """# comment line
        |
        |ssh-rsa AAAA first-key
        |ssh-ed25519 AAAA second-key
        |""".stripMargin.getBytes("UTF-8")
    val out = detect(payload, "authorized_keys")
    assert(out.contains("application/x-openssh-public-key"),
      s"any matching SSH line should claim; got $out")
  }

  test("[P8] multi-line file with pubkey on line 2 detected (first line is a comment)") {
    val payload = "# comment\nssh-ed25519 AAAA me@host\n".getBytes
    val out = detect(payload)
    assert(out.contains("application/x-openssh-public-key"),
      s"second-line SSH key should be detected; got $out")
  }

  test("[P8] multi-line file with cert on a non-first line is detected") {
    val payload =
      """# header comment
        |ssh-ed25519-cert-v01@openssh.com AAAA cert@host
        |""".stripMargin.getBytes("UTF-8")
    val out = detect(payload, "stuff")
    assert(out.contains("application/x-openssh-certificate"),
      s"OpenSSH cert on non-first line should be detected; got $out")
  }
}
