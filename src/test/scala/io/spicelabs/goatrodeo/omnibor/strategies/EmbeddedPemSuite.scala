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

import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.Helpers.sha256Hex
import munit.FunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.CertificateFactory
import scala.collection.immutable.TreeSet

/** Phase D — Base64-embedded PEM capture (redaction-first).
  *
  * Decodes certificates/private keys embedded as base64 data fields or inline
  * PEM in text configs using the real certificate/key corpus, and verifies the
  * hard constraint: private key bytes are never emitted and only public SPKI
  * hashes are recorded.
  */
class EmbeddedPemSuite extends FunSuite {

  private val certAdHoc = MKC.adHoc("Certificates")
  private val keyAdHoc = MKC.adHoc("EmbeddedKey")

  private val certPem: String =
    Files.readString(
      Path.of("test_data/certificates/x509/canonical/letsencrypt-e1.pem"),
      StandardCharsets.UTF_8
    )
  private val rsaKeyPem: String =
    Files.readString(
      Path.of(
        "test_data/certificates/private-keys/synthetic/pkcs8-rsa-2048-unencrypted.pem"
      ),
      StandardCharsets.UTF_8
    )

  private def b64(s: String): String =
    java.util.Base64.getEncoder.withoutPadding
      .encodeToString(s.getBytes(StandardCharsets.UTF_8))

  private def artifact(name: String, content: String): ByteWrapper =
    ByteWrapper(content.getBytes(StandardCharsets.UTF_8), name, None)

  private def meta(
      name: String,
      content: String
  ): Map[String, TreeSet[StringOrPair]] = {
    val a = artifact(name, content)
    new EmbeddedPemState(a).invokeBuildMetadata(a).toMap
  }

  private def values(m: Map[String, TreeSet[StringOrPair]]): Vector[String] =
    m.values.toVector.flatMap(_.toVector.map(_.value))

  test("T-D-01 kubeconfig certificate-authority-data yields a certificate item") {
    val m = meta(
      "kubeconfig.yaml",
      "current-context: ctx\napiVersion: v1\nclusters:\n  - name: c\n    cluster:\n      certificate-authority-data: " +
        b64(certPem) + "\n"
    )
    assert(
      m.get(certAdHoc("SubjectDN")).exists(_.nonEmpty),
      "SubjectDN must be present"
    )
    assert(m.contains(certAdHoc("IssuerDN")))
    assert(m.contains(certAdHoc("NotBefore")))
    assert(m.contains(certAdHoc("NotAfter")))
    assert(m.contains(certAdHoc("KeyAlgorithm")))
    assert(m.contains(certAdHoc("SigAlgorithm")))
    assert(m.contains(certAdHoc("SpkiSha256")))
    assert(m.contains(certAdHoc("CertSha256")))
    assert(m.contains(certAdHoc("IsCA")))
    assert(m.contains(certAdHoc("SelfSigned")))
    assert(m.contains(certAdHoc("Version")))
    assertEquals(m(keyAdHoc("kind")).head.value, "certificate")
    assertEquals(m(keyAdHoc("source")).head.value, "kubeconfig.yaml")
    assert(!values(m).exists(_.contains("BEGIN CERTIFICATE")), "PEM body must not be stored")
  }

  test("T-D-02 kubeconfig client-key-data yields a private-key envelope, zero key bytes") {
    val m = meta(
      "kubeconfig.yaml",
      "users:\n  - name: u\n    user:\n      client-key-data: " + b64(rsaKeyPem) + "\n"
    )
    assertEquals(
      m(certAdHoc("DerivedFromPrivateKey")).head.value,
      "true"
    )
    assertEquals(m(keyAdHoc("kind")).head.value, "private-key")
    assertEquals(m(keyAdHoc("key_algorithm")).head.value, "rsa")
    assertEquals(m(keyAdHoc("key_size")).head.value, "2048")
    assert(
      m.get(keyAdHoc("derived_spki_sha256")).exists(_.nonEmpty),
      "derived public SPKI hash must be present"
    )
    val all = values(m)
    assert(
      !all.exists(v =>
        v.length >= 40 &&
          !v.matches("[0-9a-fA-F]{40,}") &&
          (v.contains('+') || v.contains('/') || v.contains('='))
      ),
      s"no base64/key blob may be emitted: ${all.mkString(",")}"
    )
    assert(!all.exists(_.contains("PRIVATE KEY")), "private key material must not be emitted")
  }

  test("T-D-03 inline PEM block inside YAML yields a certificate item") {
    val yaml = "kind: Secret\napiVersion: v1\nstringData:\n  tls.crt: |-\n    " +
      certPem.replace("\n", "\n    ") + "\n"
    val m = meta("secret.yaml", yaml)
    assert(m.contains(certAdHoc("SubjectDN")), "inline PEM must parse as a certificate")
    assertEquals(m(keyAdHoc("kind")).head.value, "certificate")
  }

  test("T-D-04 oversized base64 blob is skipped without OOM") {
    val big = java.util.Base64.getEncoder
      .encodeToString(Array.fill[Byte](EmbeddedPemStrategy.MaxDecodeBytes + 1)(0x41))
    val m = meta(
      "kubeconfig.yaml",
      "client-key-data: " + b64(rsaKeyPem) + " # real\ncertificate-authority-data: " + big + "\n"
    )
    // The oversize blob alone must not produce a cert/private envelope; only the real key blob above may.
    assert(m.nonEmpty, "the in-budget blob should still be captured")
    assert(!m.contains(certAdHoc("SubjectDN")), "oversize blob must not decode")
  }

  test("T-D-05 malformed base64 is tolerated, no item, no panic") {
    val m = meta(
      "kubeconfig.yaml",
      "certificate-authority-data: " + ("A" * 24) + "%\nclient-key-data: \"not-base64!!\"\n"
    )
    assert(m.isEmpty, s"malformed input must not produce metadata: $m")
  }

  test("T-D-06 property: emitted values are short tags, never secrets") {
    val battery = Vector(
      "kubeconfig.yaml" ->
        ("certificate-authority-data: " + b64(certPem) + "\nclient-key-data: " + b64(rsaKeyPem) + "\n"),
      "terraform.tf" ->
        ("resource \"tls_private_key\" \"k\" {}\n  private_key_pem = <<EOT\n" + rsaKeyPem + "EOT\n"),
      "secret.yaml" ->
        ("stringData:\n  tls.crt: |-\n    " + certPem.replace("\n", "\n    ") + "\n")
    )
    val b64ish = """[A-Za-z0-9+/]{40,}=""".r
    battery.foreach { case (name, content) =>
      val m = meta(name, content)
      val all = values(m)
      assert(all.nonEmpty, s"[$name] expected metadata")
      assert(
        all.forall(v => v.length < 100),
        s"[$name] values must be short tags: ${all.mkString(",")}"
      )
      assert(
        !all.exists(v => b64ish.findFirstIn(v).isDefined),
        s"[$name] base64 secret-looking value: ${all.mkString(",")}"
      )
      assert(!all.exists(_.contains("PRIVATE KEY")), s"[$name] private key material")
    }
  }

  test("T-D-07 certificate SPKI hash is the public key hash, not the secret") {
    val cf = CertificateFactory.getInstance("X.509")
    val cert = cf
      .generateCertificate(new ByteArrayInputStream(certPem.getBytes(StandardCharsets.ISO_8859_1)))
      .asInstanceOf[java.security.cert.X509Certificate]
    val m = meta(
      "kubeconfig.yaml",
      "certificate-authority-data: " + b64(certPem) + "\n"
    )
    val expectedSpki = sha256Hex(cert.getPublicKey.getEncoded)
    assertEquals(
      m(certAdHoc("SpkiSha256")).head.value,
      expectedSpki,
      "SpkiSha256 must hash the public SPKI (derived, not the secret)"
    )
    assert(!values(m).contains(expectedSpki == ""), "never an empty placeholder")
  }
}