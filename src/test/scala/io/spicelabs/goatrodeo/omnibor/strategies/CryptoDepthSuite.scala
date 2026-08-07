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
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.CryptoDetector
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import scala.collection.immutable.TreeSet

/** Phase F — Detection depth: keystore name fallback, binary PGP keyrings,
  * keybox detection, PKCS#8 DER envelopes, certificate extension depth, and
  * the no-secret output property.
  */
class CryptoDepthSuite extends FunSuite {

  private val certAdHoc = MKC.adHoc("Certificates")
  private val keyAdHoc = MKC.adHoc("EmbeddedKey")

  private def b64(s: String): String =
    Base64.getEncoder.withoutPadding.encodeToString(s.getBytes(StandardCharsets.UTF_8))

  private def artifact(name: String, content: Array[Byte]): ByteWrapper =
    ByteWrapper(content, name, None)

  private def artifact(name: String, content: String): ByteWrapper =
    artifact(name, content.getBytes(StandardCharsets.UTF_8))

  private def meta(
      name: String,
      content: String
  ): Map[String, TreeSet[StringOrPair]] = {
    val a = artifact(name, content)
    new EmbeddedPemState(a).invokeBuildMetadata(a).toMap
  }


  /** Strip an ASCII-armor body (headers, blanks, checksum) from PEM/OpenPGP text. */
  private def armorBody(text: String): String = {
    val lines = text.split("\r?\n").toVector.map(_.trim)
    val afterBegin = lines.dropWhile(!_.startsWith("-----BEGIN")).drop(1)
    val bodyLines = afterBegin
      .dropWhile(l => l.isEmpty || !l.matches("[A-Za-z0-9+/]+(=*)?"))
      .takeWhile(!_.startsWith("-----END"))
      .filter(_.nonEmpty)
    if (bodyLines.nonEmpty && bodyLines.last.startsWith("=")) bodyLines.init.mkString
    else bodyLines.mkString
  }

  test("T-F-01 keystore name/extension fallback augments MIME") {
    for (name <- Vector("conf/security/jssecacerts", "conf/security/cacerts", "keystore.jks", "x.bks")) {
      val mimes = CryptoDetector.detectFromBytes(Array[Byte](0x00, 0x01, 0x02), name, None)
      assert(
        mimes.contains("application/x-java-keystore"),
        s"$name should be a keystore: $mimes"
      )
    }
    assert(
      CryptoDetector.detectFromBytes(Array[Byte](0x00, 0x01, 0x02), "store.p12", None)
        .contains("application/pkcs12"),
      "p12 extension adds pkcs12"
    )
    // A non-keystore name is NOT classified.
    assert(
      !CryptoDetector.detectFromBytes(Array[Byte](0x00, 0x01, 0x02), "notes.txt", None)
        .contains("application/x-java-keystore")
    )
  }

  test("T-F-02 binary .gpg keyring yields per-key metadata") {
    val armored = Files.readString(
      Path.of("test_data/certificates/pgp/real/debian-cdimage.asc"),
      StandardCharsets.UTF_8
    )
    val binary = Base64.getDecoder.decode(armorBody(armored))

    val mimes = CryptoDetector.detectFromBytes(binary.take(4096), "etc/pgp/key.gpg", Some(binary))
    assert(mimes.contains("application/pgp-keys"), s"binary keyring MIME: $mimes")

    val strategies = ToProcess.strategiesForArtifacts(Vector(artifact("etc/pgp/key.gpg", binary)), _ => (), false)
    val certs = strategies.collectFirst { case c: Certificates => c }
      .getOrElse(fail("binary .gpg must be claimed by Certificates"))
    val (els, state) = certs.getElementsToProcess()
    val item = io.spicelabs.goatrodeo.omnibor.Item(
      "x",
      TreeSet.empty,
      None,
      None
    )
    val (m, _) = state.getMetadata(els.head._1, item, new SingleMarker())
    val map = m.toMap
    assert(map.contains(certAdHoc("PgpKeyCount")), s"per-key metadata expected: ${map.keys}")
  }

  test("T-F-03 GPG keybox is detected (magic + extension)") {
    val magic = Array[Byte](0x23, 0x4b, 0x42, 0x58, 0x66) // "#KBXf"
    assert(
      CryptoDetector.detectFromBytes(magic, "pubring.kbx", None).contains("application/pgp-keys"),
      "keybox magic detected"
    )
    assert(
      CryptoDetector.detectFromBytes(Array[Byte](0x01, 0x02, 0x03), "a.kbx", None)
        .contains("application/pgp-keys"),
      ".kbx extension detected"
    )
  }

  test("T-F-04 PKCS#8 DER private key → envelope-only, no bytes") {
    val pem = Files.readString(
      Path.of("test_data/certificates/private-keys/synthetic/pkcs8-rsa-2048-unencrypted.pem"),
      StandardCharsets.UTF_8
    )
    val der = Base64.getDecoder.decode(armorBody(pem))

    // The DER is embedded as a base64 data field (e.g. kubeconfig client-key-data
    // holding a DER private key); the decode path exercises parsePrivateKeyDer.
    val content =
      "client-key-data: " + Base64.getEncoder.withoutPadding.encodeToString(der) + "\n"
    val m = new EmbeddedPemState(artifact("kube-der.yaml", content))
      .invokeBuildMetadata(artifact("kube-der.yaml", content))
      .toMap
    assertEquals(m(certAdHoc("DerivedFromPrivateKey")).head.value, "true")
    assertEquals(m(keyAdHoc("kind")).head.value, "private-key")
    assertEquals(m(keyAdHoc("key_algorithm")).head.value, "rsa")
    assertEquals(m(keyAdHoc("key_size")).head.value, "2048")
    val all = m.values.toVector.flatMap(_.toVector.map(_.value))
    assert(!all.exists(_.contains("PRIVATE KEY")), "no PEM text in metadata")
    assert(
      !all.exists(v =>
        v.length >= 40 &&
          !v.matches("[0-9a-fA-F]{40,}") &&
          (v.contains('+') || v.contains('/') || v.contains('='))
      ),
      "no base64 blob"
    )
  }

  private def certificateMetadata(
      pemPath: String
  ): Map[String, TreeSet[StringOrPair]] = {
    val a = artifact(
      pemPath,
      Files.readAllBytes(Path.of(pemPath))
    )
    val strategies = ToProcess.strategiesForArtifacts(Vector(a), _ => (), false)
    val certs = strategies.collectFirst { case c: Certificates => c }
      .getOrElse(fail(s"$pemPath must be claimed by Certificates"))
    val (els, state) = certs.getElementsToProcess()
    val item = io.spicelabs.goatrodeo.omnibor.Item("x", TreeSet.empty, None, None)
    val (m, _) = state.getMetadata(els.head._1, item, new SingleMarker())
    m.toMap
  }

  test("T-F-05 certificate OCSP/CRL-DP extension depth") {
    val m = certificateMetadata(
      "test_data/certificates/x509/leaves/python.org__www.python.org__a162964cfe.der"
    )
    assert(
      m.get(certAdHoc("OcspUrl")).exists(_.head.value.nonEmpty),
      "OcspUrl must be populated from the AIA extension"
    )
    assert(
      m.get(certAdHoc("CrlDistributionPoints")).exists(_.head.value.nonEmpty),
      "CrlDistributionPoints must be populated"
    )
  }

  test("T-F-06 certificate SKI + policies") {
    val m = certificateMetadata(
      "test_data/certificates/x509/canonical/letsencrypt-e1.pem"
    )
    val skiVal = m.get(certAdHoc("SubjectKeyIdentifier")).map(_.head.value)
    assert(
      skiVal.exists(_.matches("^[0-9A-Fa-f]+$")),
      s"SubjectKeyIdentifier must be a hex key id, got: $skiVal"
    )
    assert(
      m.get(certAdHoc("CertificatePolicies")).exists(_.head.value.nonEmpty),
      "CertificatePolicies must be populated"
    )
  }

  test("T-F-10 property: F-family outputs carry no secrets") {
    val certPem = Files.readString(
      Path.of("test_data/certificates/x509/canonical/letsencrypt-e1.pem"),
      StandardCharsets.UTF_8
    )
    val battery = Vector(
      "kube.yaml" -> ("certificate-authority-data: " + b64(certPem) + "\n"),
      "kube2.yaml" -> ("client-key-data: " + b64(
        Files.readString(
          Path.of("test_data/certificates/private-keys/synthetic/pkcs8-rsa-2048-unencrypted.pem"),
          StandardCharsets.UTF_8
        )
      ) + "\n")
    )
    val b64ish = """[A-Za-z0-9+/]{40,}=""".r
    battery.foreach { case (name, content) =>
      val m = meta(name, content)
      assert(m.nonEmpty, s"[$name] expected metadata")
      val all = m.values.toVector.flatMap(_.toVector.map(_.value))
      assert(
        !all.exists(v => b64ish.findFirstIn(v).isDefined),
        s"[$name] secret-looking value: ${all.mkString(",")}"
      )
      assert(!all.exists(_.contains("PRIVATE KEY")), s"[$name] key material")
    }
  }
}