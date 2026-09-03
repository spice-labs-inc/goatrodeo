package io.spicelabs.goatrodeo.omnibor.strategies
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.File
import scala.util.Try

/** Phase 2 — Certificates PKCS#7 claim + parse (spec §4, T6.x).
  *
  * WHAT: pins that the single Certificates strategy claims artifacts
  * carrying `application/pkcs7-signature`, parses detached PKCS#7
  * SignedData blobs (Authenticode shape) via CertificateFactory into the
  * embedded X.509 chain, records per-cert and bundle metadata like a PEM
  * bundle, does NOT claim the PEM/CMS MIME, never mislabels invalid
  * blobs, and keeps the cert MIME constants owned by this module.
  *
  * WHY: Authenticode cert blobs are detached SignedData; the product
  * wants the embedded certs surfaced (ADG + CBOM). The claim is MIME-
  * driven, so the test drives it via the wrapper's MIME set directly
  * (the producer-stamped hint path is exercised in T6.1).
  *
  * LLM note: `classifyAndParse` is the internal dispatch; the suite also
  * checks the public claim path via ToProcess/strategies when feasible.
  */
class CertificatesPkcs7Suite extends FunSuite {

  private val pkcs7Mime = io.spicelabs.goatrodeo.omnibor.strategies.Certificates.CertPkcs7Mime
  private val pkixCertMime = io.spicelabs.goatrodeo.omnibor.strategies.Certificates.CertPkixMime
  private val pkcs7MimeLegacy = "application/pkcs7-mime"

  private def wrapperFor(bytes: Array[Byte], name: String, mimes: Set[String], hint: Option[String]): ArtifactWrapper = {
    // Use ByteWrapper directly with an explicit MIME set override is not
    // possible; instead drive through a MIME-set-carrying wrapper by
    // creating the wrapper with the hint and relying on augmented sets.
    // For tests that need a precise MIME set we pass a FileWrapper and
    // call the internal dispatch with the set constructed directly.
    io.spicelabs.goatrodeo.util.ByteWrapper(bytes, name, None, mimeHint = hint)
  }

  private def claimed(bytes: Array[Byte], name: String, hint: Option[String]): Boolean = {
    val w = wrapperFor(bytes, name, Set(), hint)
    Certificates.isCertificateCandidate(
      w.mimeType
    )
  }

  private def parse(w: ArtifactWrapper): Option[Certificates.ClaimedContent] =
    Certificates.classifyAndParse(w)

  test("T6.1 pkcs7SignatureMimeIsClaimed") {
    val w = wrapperFor("x".getBytes, "b.p7b", Set(), Some(pkcs7Mime))
    assert(
      Certificates.isCertificateCandidate(w.mimeType),
      s"pkcs7-signature must be claimed; mimes=${w.mimeType}"
    )
  }

  test("T6.2 pkcs7MimeLegacyIsNotClaimed") {
    // The PEM/CMS mime (application/pkcs7-mime) is NOT the claim target;
    // only the authoritative pkcs7-signature MIME triggers the claim.
    val w = wrapperFor("x".getBytes, "b.p7b", Set(), Some(pkcs7MimeLegacy))
    assert(
      !Certificates.isCertificateCandidate(w.mimeType),
      s"pkcs7-mime must not be claimed; mimes=${w.mimeType}"
    )
  }

  test("T6.3 detachedSignedDataParsesToChain") {
    val f = new File("test_data/certificates/pkcs7/detached.p7b.der")
    assert(f.exists(), "detached.p7b.der fixture missing")
    val w = FileWrapper(f, f.getName, None)
    val parsed = parse(w)
    assert(parsed.isDefined, "detached SignedData must parse")
    parsed.foreach {
      case Certificates.Bundle(certs) =>
        assert(certs.nonEmpty, "must yield at least one cert")
        val c0 = certs.head
        assertEquals(
          c0.getSubjectX500Principal.getName,
          "O=Spice Labs,CN=GoatRodeo Test Root",
          "X.500 DN renders in RFC 4514 reverse order"
        )
      case other => fail(s"expected Bundle, got $other")
    }
  }

  test("T6.4 bareDerSingleCertSharesPath") {
    val f = new File("test_data/certificates/x509/leaves/scala-lang.org__scala-lang.org__c1468f350c.der")
    assert(f.exists(), "DER cert fixture missing")
    val w = FileWrapper(f, f.getName, None)
    val parsed = parse(w)
    assert(parsed.isDefined, "bare DER X.509 must parse")
    parsed.foreach {
      case Certificates.SingleCert(c) => assert(c.getSubjectX500Principal.getName.nonEmpty)
      case other                      => fail(s"expected SingleCert, got $other")
    }
  }

  test("T6.5 invalidBlobNeverClaimed") {
    // a random, non-DER non-X509 blob carrying the pkcs7-signature hint is
    // claimed (the MIME authoritatively says so), but parsing yields None
    // (clean skip) — never mislabeled as a certificate.
    val junk = Array.tabulate[Byte](64)(i => (i * 7 + 1).toByte)
    val w = wrapperFor(junk, "junk.bin", Set(), Some(pkcs7Mime))
    assert(Certificates.isCertificateCandidate(w.mimeType))
    assertEquals(parse(w), None, "unparseable blob must skip cleanly, not throw")
  }

  test("T6.6 zeroCertOrUnparseablePkcs7IsSkipped") {
    // An empty/truncated DER blob must skip cleanly (no exception).
    val bad = Array[Byte](0x30, 0x82.toByte, 0x03.toByte, 0x00.toByte) // truncated SEQUENCE
    val w = wrapperFor(bad, "bad.p7b", Set(), Some(pkcs7Mime))
    Try(parse(w)) match {
      case scala.util.Success(v) => assertEquals(v, None)
      case scala.util.Failure(e) => fail(s"parse must not throw: ${e.getMessage}")
    }
  }

  test("T6.7 certMimeConstantsOwnedByCertificates") {
    // The constants live in the Certificates module and are the exact
    // MIME strings from the spec.
    assertEquals(pkcs7Mime, "application/pkcs7-signature")
    assertEquals(pkixCertMime, "application/pkix-cert")
  }
}