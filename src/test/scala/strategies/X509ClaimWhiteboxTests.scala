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

/** Phase 3-4 white-box tests with **independent ground truth** for
  * `SingleCert`, `Bundle`, `Keystore`, and `Crl` claim types.
  *
  * ## Why this exists
  *
  * Phases 3-4 of the Certificates strategy were originally tested
  * primarily through the corpus harness (`CertificatesSuite`), whose
  * sidecars are produced by the `MaterializeSidecars` runner —
  * which uses the strategy's OWN emitters as the source. That makes
  * the per-fixture assertions tautological by construction: a
  * regression in `parseSingleCert` / `parseBundle` / `parseKeystore`
  * / `parseCrl` would re-emit the bug into the sidecar and the test
  * would still pass.
  *
  * The Phase 5+ work introduced independent ground truth (ssh-keygen,
  * gpg, openssl) for SSH/PGP/private-key paths. Phase 3-4 lacked the
  * equivalent. This suite closes the cross-phase gap (P5 in the
  * Phases-1-7 review): each white-box test pins values computed by
  * `openssl`/`keytool` *outside* the strategy.
  *
  * ## Ground-truth recipes
  *
  * | Test | Recipe |
  * |---|---|
  * | SingleCert isrgrootx1 cert SHA-256 | `openssl x509 -in <fixture> -outform DER \| sha256sum` |
  * | SingleCert isrgrootx1 SPKI SHA-256 | `openssl x509 -in <fixture> -pubkey -noout \| openssl pkey -pubin -inform PEM -outform DER \| sha256sum` |
  * | Bundle first-cert SHA-256 | `awk '/BEGIN CERT/,/END CERT/{...exit}' <fixture> \| openssl x509 -outform DER \| sha256sum` |
  * | Keystore alias enumeration | `keytool -list -keystore <fixture> -storepass ""` |
  * | CRL DER SHA-256 | `sha256sum <fixture>` |
  */
class X509ClaimWhiteboxTests extends FunSuite {

  private def wrap(path: String): FileWrapper =
    FileWrapper(new File(path), path, None)

  // ===== SingleCert (Phase 3) =============================================

  test("parseSingleCert + purlsForCert: ISRG Root X1 SPKI hash matches openssl") {
    // openssl x509 -in <fixture> -pubkey -noout
    //   | openssl pkey -pubin -inform PEM -outform DER | sha256sum
    // = 0b9fa5a59eed715c26c1020c711b4f6ec42d58b0015e14337a39dad301c5afc3
    val w = wrap("test_data/certificates/x509/canonical/letsencrypt-isrgrootx1.pem")
    val cert = Certificates.parseSingleCert(w).get
    val spkiBytes = Certificates.spkiBytesFromCert(cert)
    assertEquals(Certificates.sha256Hex(spkiBytes),
      "0b9fa5a59eed715c26c1020c711b4f6ec42d58b0015e14337a39dad301c5afc3")
  }

  test("parseSingleCert + purlsForCert: ISRG Root X1 cert hash matches openssl") {
    // openssl x509 -in <fixture> -outform DER | sha256sum
    // = 96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6
    val w = wrap("test_data/certificates/x509/canonical/letsencrypt-isrgrootx1.pem")
    val cert = Certificates.parseSingleCert(w).get
    assertEquals(Certificates.sha256Hex(cert.getEncoded),
      "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6")
  }

  test("purlsForCert: ISRG Root X1 emits both spki + cert pURLs with openssl-pinned hashes") {
    val w = wrap("test_data/certificates/x509/canonical/letsencrypt-isrgrootx1.pem")
    val cert = Certificates.parseSingleCert(w).get
    val purls = Certificates.purlsForCert(cert)
    assertEquals(purls.length, 2,
      "Phase 3 plan: emit both pkg:x509/spki-sha256 and pkg:x509/cert-sha256")
    val spkiPurl = purls.find(_.toString.contains("spki-sha256")).get.canonicalize().nn
    assert(spkiPurl.contains("0b9fa5a59eed715c26c1020c711b4f6ec42d58b0015e14337a39dad301c5afc3"))
    val certPurl = purls.find(_.toString.contains("cert-sha256")).get.canonicalize().nn
    assert(certPurl.contains("96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"))
  }

  // ===== Bundle (Phase 4) ================================================

  test("parseBundle: goatrodeo-test-chain first-cert hash matches openssl") {
    // awk '/BEGIN CERT/,/END CERT/{print; if (/END CERT/) exit}' <fixture>
    //   | openssl x509 -outform DER | sha256sum
    // = 11c1096ec324244d2dac18cea1131f20981580fb61b50e4cfb1a17f742fa949e
    val w = wrap("test_data/certificates/pem-bundles/synthetic/goatrodeo-test-chain.pem")
    val bundle = Certificates.parseBundle(w).get
    assert(bundle.certs.nonEmpty, "test-chain must contain at least one cert")
    val firstCert = bundle.certs.head
    assertEquals(Certificates.sha256Hex(firstCert.getEncoded),
      "11c1096ec324244d2dac18cea1131f20981580fb61b50e4cfb1a17f742fa949e",
      "first cert in the bundle (the leaf) should match openssl's hash")
  }

  test("parseBundle: walks every BEGIN CERTIFICATE block (count matches openssl)") {
    val w = wrap("test_data/certificates/pem-bundles/synthetic/goatrodeo-test-chain.pem")
    val bundle = Certificates.parseBundle(w).get
    // Independently verified: `grep -c '-----BEGIN CERTIFICATE-----'` on
    // this fixture returns 2 (chain: leaf + root).
    assertEquals(bundle.certs.length, 2,
      "goatrodeo-test-chain.pem is a 2-cert chain (leaf + root)")
  }

  // ===== Keystore (Phase 4) ==============================================

  test("parseKeystore: trust-only-null-password.p12 loads with null password") {
    val w = wrap("test_data/certificates/keystores/synthetic/trust-only-null-password.p12")
    val ks = Certificates.parseKeystore(w, "PKCS12").get
    assert(ks.ks.isDefined,
      "null-password load must succeed for trust-only PKCS#12")
    assertEquals(ks.format, "pkcs12")
    assertEquals(ks.entryCount, 1,
      "keytool -list reports 1 entry: letsencrypt-isrg-root-x1")
  }

  test("parseKeystore: trust-only-null-password.p12 alias is letsencrypt-isrg-root-x1 (keytool ground truth)") {
    val w = wrap("test_data/certificates/keystores/synthetic/trust-only-null-password.p12")
    val ks = Certificates.parseKeystore(w, "PKCS12").get
    val aliases = ks.ks.get.aliases().asInstanceOf[java.util.Enumeration[String]]
    val aliasList = scala.collection.mutable.ListBuffer[String]()
    while (aliases.hasMoreElements) aliasList += aliases.nextElement
    assertEquals(aliasList.toList, List("letsencrypt-isrg-root-x1"))
  }

  test("parseKeystore: trust-only-null-password.p12 cert matches openssl SHA-256 (keytool fingerprint cross-check)") {
    // keytool -list output:
    //   Certificate fingerprint (SHA-256): 96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6
    val w = wrap("test_data/certificates/keystores/synthetic/trust-only-null-password.p12")
    val ks = Certificates.parseKeystore(w, "PKCS12").get
    val cert = ks.ks.get.getCertificate("letsencrypt-isrg-root-x1")
      .asInstanceOf[java.security.cert.X509Certificate]
    assertEquals(Certificates.sha256Hex(cert.getEncoded),
      "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6",
      "keystore-stored cert must match the same isrgrootx1 hash openssl emits")
  }

  test("parseKeystore: encrypted keystore (null-password load fails) returns Keystore with ks=None") {
    // The encrypted .p12 fixture is not loadable with null password by
    // design. The strategy must surface envelope-only state.
    val w = wrap("test_data/certificates/keystores/synthetic/encrypted-p12-dsa.p12")
    val ks = Certificates.parseKeystore(w, "PKCS12").get
    assertEquals(ks.ks, None,
      "null-password load must fail on a real-passphrase keystore; " +
      "strategy emits envelope-only state")
    assertEquals(ks.entryCount, 0)
  }

  // ===== CRL (Phase 4) ===================================================

  test("parseCrl: digicert-global-root-g2.crl DER hash matches sha256sum") {
    // sha256sum <fixture>
    // = f0efadceab8237df452a2a201414a68196ea8b4c876c9c7af1e06a3ec3cf8f29
    val w = wrap("test_data/certificates/crls/real/digicert-global-root-g2.crl")
    val crl = Certificates.parseCrl(w).get
    assertEquals(Certificates.sha256Hex(crl.crl.getEncoded),
      "f0efadceab8237df452a2a201414a68196ea8b4c876c9c7af1e06a3ec3cf8f29",
      "the CRL DER bytes parsed by BC must round-trip identically to " +
      "the file's raw SHA-256 (openssl/sha256sum ground truth)")
  }
}
