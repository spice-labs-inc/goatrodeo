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

package io.spicelabs.goatrodeo.util

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/** Tests for the per-augmenter MIME applicability rules.
  *
  * WHAT: every registered MIME augmenter carries its own `mimeRule`, a
  * block-shaped predicate over the artifact's Tika MIME set, so a new augmenter
  * can be added without understanding any global skip logic.
  *
  * WHY: the augmenter chain costs ~700–1,350µs per non-terminal artifact
  * (measured), which on 50M-artifact builds halves throughput. The rules remove
  * provably-impossible work (class files, XML, media) per augmenter.
  *
  * THEORY: rules are BLOCK-shaped by design — they only skip MIMEs that
  * provably cannot match, so every unknown/degenerate MIME (inner package
  * fragments from debs/rpms/nupkgs, future ecosystems) keeps being probed. A
  * wrong rule can only cost performance, never a false negative. These tests
  * pin both directions: blocked families are skipped, and everything else —
  * including binary PEM carriers and octet-stream fragments — still detects.
  *
  * LLM note: R-x = test id.
  */
class MimeAugmenterRuleSuite extends FunSuite {

  private def bytes(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.ISO_8859_1)

  private def realClassFile(): Array[Byte] = {
    val jar = new ZipFile(
      new java.io.File(
        "test_data/download/adg_tests/repo_ea/aop-common-1.3.2.jar"
      )
    )
    try {
      val entry = jar.entries().asScala.find(_.getName.endsWith(".class")).get
      jar.getInputStream(entry).readAllBytes()
    } finally jar.close()
  }

  // R-1 — each rule blocks its declared impossible families and admits
  // everything else.
  test("R-1 per-augmenter rules block declared families only") {
    assert(!CryptoDetector.mimeRule(Set("application/java-vm")))
    assert(CryptoDetector.mimeRule(Set("application/octet-stream")))
    assert(CryptoDetector.mimeRule(Set("application/xml")))

    assert(!CryptoContentDetector.mimeRule(Set("application/java-vm")))
    assert(CryptoContentDetector.mimeRule(Set("application/octet-stream")))

    assert(!DotnetDetector.mimeRule(Set("application/xml")))
    assert(!DotnetDetector.mimeRule(Set("text/plain")))
    assert(!DotnetDetector.mimeRule(Set("application/java-vm")))
    assert(
      DotnetDetector.mimeRule(Set("application/x-msdownload; format=pe32"))
    )
    assert(DotnetDetector.mimeRule(Set("application/octet-stream")))

    assert(!SaffronDetector.mimeRule(Set("application/java-vm")))
    // text stays probed on purpose (Tika mislabels .vhd as text/x-vhdl)
    assert(SaffronDetector.mimeRule(Set("text/plain")))

    assert(!OpenSSLConfigDetector.mimeRule(Set("application/java-vm")))
    assert(!OpenSSLConfigDetector.mimeRule(Set("image/png")))
    assert(OpenSSLConfigDetector.mimeRule(Set("text/plain")))
    assert(OpenSSLConfigDetector.mimeRule(Set("application/octet-stream")))

    assert(!JavaSecurityDetector.mimeRule(Set("application/java-vm")))
    assert(JavaSecurityDetector.mimeRule(Set("text/plain")))

    assert(!JavaArchiveDetector.mimeRule(Set("text/plain")))
    assert(!JavaArchiveDetector.mimeRule(Set("application/xml")))
    assert(JavaArchiveDetector.mimeRule(Set("application/octet-stream")))
  }

  // R-2 — a real class file yields only the class MIME; the crypto augmenters
  // never fire on it.
  test("R-2 real class file is augmented by nothing") {
    val w = ByteWrapper(realClassFile(), "Foo.class", None)
    assertEquals(w.mimeType, Set("application/java-vm"))
  }

  // R-3 — XML is still probed by the crypto detectors (embedded PEM can live
  // in configs) but not by the binary-only/text-config detectors.
  test("R-3 XML keeps crypto detection, skips binary/text-config augmenters") {
    val pemInXml = ByteWrapper(
      bytes(
        "<config>\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</config>"
      ),
      "pom.xml",
      None
    )
    assert(pemInXml.mimeType.contains("application/x-pem-file"))

    val javaSecInXml = ByteWrapper(
      bytes("<x>jdk.tls.disabledAlgorithms=SSLv3, RC4</x>"),
      "settings.xml",
      None
    )
    assert(
      !javaSecInXml.mimeType.contains(JavaSecurityDetector.JavaSecurityMimeType)
    )

    val opensslInXml = ByteWrapper(
      bytes("<x>openssl_conf = default_conf</x>"),
      "config.xml",
      None
    )
    assert(
      !opensslInXml.mimeType.contains(
        OpenSSLConfigDetector.OpenSSLConfigMimeType
      )
    )
  }

  // R-4 — degenerate octet-stream fragments are still probed by every
  // content detector (inner package fragments must not lose coverage).
  test("R-4 octet-stream fragments stay probed") {
    val jwt = ByteWrapper(
      bytes("\u0000header.eyJhbGciOiJIUzI1NiJ9.payload.sig"),
      "fragment.bin",
      None
    )
    assert(jwt.mimeType.contains(CryptoContentDetector.CryptoTokensMime))
  }

  // R-5 — binary PEM carriers (embedded certs in shared libraries) keep their
  // detection; the CryptoDetector/CryptoContentDetector rules must not block
  // binaries.
  test("R-5 binary PEM carrier still detected") {
    val markerLib = ByteWrapper(
      bytes(
        "\u0000\u0000-----BEGIN CERTIFICATE-----\u0000MIIB\u0000-----END CERTIFICATE-----\u0000"
      ),
      "usr/lib/libmbedtls.so.2.14.1",
      None
    )
    assert(markerLib.mimeType.contains("application/x-pem-file"))
    assert(markerLib.mimeType.contains("application/x-x509-ca-cert"))
  }
}
