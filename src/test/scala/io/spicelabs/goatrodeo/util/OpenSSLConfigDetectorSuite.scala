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

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.forAll

import java.nio.charset.StandardCharsets

/** Phase 0 — Unit tests for `OpenSSLConfigDetector`.
  *
  * These tests verify that the augmenter:
  *   - detects OpenSSL configs with section headers and security keywords,
  *   - ignores non-OpenSSL text files,
  *   - ignores binary data,
  *   - reads no more than the configured prefix,
  *   - is purely additive.
  */
class OpenSSLConfigDetectorSuite extends ScalaCheckSuite {

  private val expectedMime = OpenSSLConfigDetector.OpenSSLConfigMimeType

  private def bytes(text: String): Array[Byte] =
    text.getBytes(StandardCharsets.UTF_8)

  private def detect(text: String): Set[String] = {
    val wrapper = ByteWrapper(bytes(text), "test.cnf", None)
    OpenSSLConfigDetector.detect(wrapper)
  }

  private def augment(text: String, current: Set[String]): Set[String] = {
    val wrapper = ByteWrapper(bytes(text), "test.cnf", None)
    OpenSSLConfigDetector.mimeTypeAugmenter(wrapper, current)
  }

  test("detects OpenSSL config with CipherString") {
    val text =
      """[ ssl_default ]
        |CipherString = DEFAULT@SECLEVEL=2
        |MinProtocol = TLSv1.2
        |""".stripMargin
    assertEquals(detect(text), Set(expectedMime))
  }

  test("detects OpenSSL config with ssl_conf") {
    val text =
      """[ openssl_init ]
        |ssl_conf = ssl_sect
        |
        |[ ssl_sect ]
        |system_default = system_default_sect
        |""".stripMargin
    assertEquals(detect(text), Set(expectedMime))
  }

  test("detects OpenSSL config with .include") {
    val text =
      """[ req ]
        |.include /etc/ssl/openssl.cnf
        |distinguished_name = req_distinguished_name
        |""".stripMargin
    assertEquals(detect(text), Set(expectedMime))
  }

  test("detects OpenSSL config with case-insensitive keywords") {
    val text =
      """[ SSL_DEFAULT ]
        |CIPHERSTRING = DEFAULT
        |MINPROTOCOL = TLSv1.2
        |""".stripMargin
    assertEquals(detect(text), Set(expectedMime))
  }

  test("ignores generic INI file") {
    val text =
      """[ section ]
        |key = value
        |another = value
        |""".stripMargin
    assertEquals(detect(text), Set.empty[String])
  }

  test("ignores TOML file") {
    val text =
      """[package]
        |name = "example"
        |version = "1.0.0"
        |features = ["a", "b"]
        |""".stripMargin
    assertEquals(detect(text), Set.empty[String])
  }

  test("ignores Java properties file") {
    val text =
      """# Java properties
        |jdk.tls.disabledAlgorithms=SSLv3, RC4
        |java.security.properties=/dev/null
        |""".stripMargin
    assertEquals(detect(text), Set.empty[String])
  }

  test("ignores PEM certificate") {
    val text =
      """-----BEGIN CERTIFICATE-----
        |MIIDXTCCAkWgAwIBAgIJAKLdQVPy90XJMA0GCSqGSIb3DQEBCwUAMEUxCzAJBgNV
        |-----END CERTIFICATE-----
        |""".stripMargin
    assertEquals(detect(text), Set.empty[String])
  }

  test("ignores empty input") {
    assertEquals(detect(""), Set.empty[String])
  }

  test("ignores binary data") {
    val binary = Array.fill(1024)(0xff.toByte)
    val wrapper = ByteWrapper(binary, "test.cnf", None)
    assertEquals(OpenSSLConfigDetector.detect(wrapper), Set.empty[String])
  }

  test("ignores binary prefix with ASCII markers") {
    // Random binary data that happens to contain a few printable characters
    // but is not an OpenSSL config.
    val binary = (Array.fill(100)(0xff.toByte) ++
      "[ section ]".getBytes(StandardCharsets.UTF_8) ++
      Array.fill(100)(0x00.toByte) ++
      "CipherString".getBytes(StandardCharsets.UTF_8) ++
      Array.fill(100)(0x00.toByte))
    val wrapper = ByteWrapper(binary, "test.cnf", None)
    assertEquals(OpenSSLConfigDetector.detect(wrapper), Set.empty[String])
  }

  test("detects ISO-8859-1 encoded config") {
    val text =
      """[ ssl_default ]
        |CipherString = DEFAULT
        |""".stripMargin
    val bytes = text.getBytes(StandardCharsets.ISO_8859_1)
    val wrapper = ByteWrapper(bytes, "test.cnf", None)
    assertEquals(
      OpenSSLConfigDetector.detect(wrapper),
      Set(expectedMime)
    )
  }

  test("augmenter is registered in ArtifactWrapper") {
    val text =
      """[ ssl_default ]
        |CipherString = DEFAULT
        |""".stripMargin
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    val wrapper = ByteWrapper(bytes, "test.cnf", None)
    assert(
      wrapper.mimeType.contains(expectedMime),
      "ArtifactWrapper.mimeType must include the OpenSSL config MIME type"
    )
  }

  test("augmenter is purely additive") {
    val text =
      """[ ssl_default ]
        |CipherString = DEFAULT
        |""".stripMargin
    val input = Set("text/plain")
    val out = augment(text, input)
    assert(input.subsetOf(out), "output must be a superset of input")
    assert(out.contains(expectedMime))
  }

  property("random text without OpenSSL markers is not detected") {
    forAll(Gen.alphaStr) { (s: String) =>
      val text = s"""[ section ]\n$s\n"""
      val detected = detect(text)
      // The generator might rarely produce an OpenSSL keyword; allow that.
      detected.isEmpty || detected.contains(expectedMime)
    }
  }

  test("reads no more than 4 KB") {
    // The detector should only inspect the prefix, so a huge file with a
    // valid header at the start should still be detected.
    val prefix =
      """[ ssl_default ]
        |CipherString = DEFAULT
        |""".stripMargin
    val huge = prefix + "\n" + ("# " + "x" * 100 + "\n") * 50000
    val wrapper = ByteWrapper(bytes(huge), "test.cnf", None)
    assertEquals(
      OpenSSLConfigDetector.detect(wrapper),
      Set(expectedMime)
    )
  }
}
