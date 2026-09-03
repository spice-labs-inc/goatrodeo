package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.omnibor.strategies.Certificates
import io.spicelabs.goatrodeo.util.{ArtifactWrapper, ByteWrapper, Configuration}
import munit.FunSuite

import java.io.File
import java.nio.file.Files

/** Phase 2 — PKCS#7 certificates surface in CBOM (spec §4, T7.x).
  *
  * WHAT: a pkcs7-signature-stamped artifact processed through the full
  * pipeline produces ADG items whose CBOM output contains a
  * `cryptographic-asset` component with `assetType: certificate`,
  * certificateProperties (subjectName/issuerName/dates/format/refs), and
  * per-cert + bundle metadata. Also: component-equivalence vs a PEM
  * bundle carrying the same certificate, and the negative (an invalid
  * blob never appears as a certificate component).
  *
  * WHY: the spec requires the PKCS#7 claim's certs to surface in the
  * CBOM exactly like a PEM bundle. `hasCertificate` already keys on
  * `Certificates:Cert:` metadata, so the claim routing via Bundle must
  * exercise that path end-to-end.
  *
  * LLM note: uses `ToProcess.buildGraphFromArtifactWrapper` +
  * `CbomEmitter.emitForStorage` — the real pipeline, no mocks.
  */
class Pkcs7CbomSuite extends FunSuite {

  given Configuration = Configuration()

  private def emitFor(bytes: Array[Byte], name: String, hint: Option[String]): String = {
    val wrapper: ArtifactWrapper = ByteWrapper(bytes, name, None, mimeHint = hint)
    val store = ToProcess.buildGraphFromArtifactWrapper(wrapper)
    val outDir = Files.createTempDirectory("p7-cbom").toFile
    val files = CbomEmitter.emitForStorage(store, "1.6", outDir).toOption.getOrElse(fail("emit failed"))
    val f = files.head
    val txt = scala.io.Source.fromFile(f).mkString
    // cleanup
    outDir.listFiles().foreach(_.delete()); outDir.delete()
    txt
  }

  private def readFixture(path: String): Array[Byte] = {
    val f = new File(path)
    assert(f.exists(), s"$path missing")
    java.nio.file.Files.readAllBytes(f.toPath)
  }

  test("T7.1 pkcs7CertAppearsInCbom") {
    val bytes = readFixture("test_data/certificates/pkcs7/detached.p7b.der")
    val cbom = emitFor(bytes, "signed.p7b", Some(Certificates.CertPkcs7Mime))
    assert(cbom.contains("cryptographic-asset"), s"cbom must contain a crypto asset:\n$cbom")
    assert(cbom.contains("certificate"), s"cbom must contain assetType certificate:\n$cbom")
    assert(cbom.contains("GoatRodeo Test Root"), s"cbom must carry the cert subject:\n$cbom")
    assert(cbom.contains("X.509"), s"cbom must carry certificateFormat X.509:\n$cbom")
  }

  test("T7.2 bundleMetadataRecorded") {
    val bytes = readFixture("test_data/certificates/pkcs7/detached.p7b.der")
    val cbom = emitFor(bytes, "signed.p7b", Some(Certificates.CertPkcs7Mime))
    // bundle keys ride as properties-from-extra
    assert(cbom.contains("Certificates:EntryCount"), s"bundle EntryCount must appear:\n$cbom")
    assert(cbom.contains("Certificates:CertCount"), s"bundle CertCount must appear:\n$cbom")
    assert(cbom.contains("Certificates:Cert:0:"), s"per-cert metadata must appear:\n$cbom")
  }

  test("T7.4 cbomComponentEquivalencePkcs7VsPem") {
    // Same certificate as a DER p7b (BK) vs a PEM bundle: the certificate
    // properties must be equal. We compare the key certificate-property
    // fields appearing in both outputs.
    val p7 = emitFor(
      readFixture("test_data/certificates/pkcs7/detached.p7b.der"),
      "signed.p7b",
      Some(Certificates.CertPkcs7Mime)
    )
    val pemBytes = readFixture("test_data/certificates/pkcs7/t.pem")
    val pem = emitFor(pemBytes, "t.pem", Some("application/x-pem-bundle"))
    // both carry the same subject/issuer/format:
    assert(p7.contains("GoatRodeo Test Root") && pem.contains("GoatRodeo Test Root"))
    assert(p7.contains("O=Spice Labs,CN=GoatRodeo Test Root") &&
      pem.contains("O=Spice Labs,CN=GoatRodeo Test Root"))
    assert(p7.contains("X.509") && pem.contains("X.509"))
  }

  test("T7.3 invalidBlobNeverInCbomAsCertificate") {
    val junk = Array.tabulate[Byte](64)(i => (i * 7 + 1).toByte)
    val cbom = emitFor(junk, "junk.p7b", Some(Certificates.CertPkcs7Mime))
    // The invalid blob is claimed but yields no certs; the item carries no
    // Certificates:Cert: metadata, so the CBOM has no certificate component.
    assert(!cbom.contains("assetType\":\"certificate"), s"no certificate asset allowed:\n$cbom")
    assert(!cbom.contains("Certificates:Cert:0:"), s"no per-cert metadata allowed:\n$cbom")
  }
}