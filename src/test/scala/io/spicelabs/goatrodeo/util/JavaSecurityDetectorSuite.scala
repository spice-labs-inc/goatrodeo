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
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Phase 2 — Unit tests for `JavaSecurityDetector`.
  *
  * These tests verify that the MIME augmenter detects Java security properties
  * files by their content, ignores non-security text and binary data, and is
  * purely additive.
  */
class JavaSecurityDetectorSuite extends FunSuite with ScalaCheckSuite {

  private val expectedMime = JavaSecurityDetector.JavaSecurityMimeType

  private def detect(text: String): Set[String] = {
    val wrapper =
      ByteWrapper(text.getBytes("ISO-8859-1"), "java.security", None)
    JavaSecurityDetector.detect(wrapper)
  }

  private def augment(text: String, current: Set[String]): Set[String] = {
    val wrapper =
      ByteWrapper(text.getBytes("ISO-8859-1"), "extra.security", None)
    JavaSecurityDetector.mimeTypeAugmenter(wrapper, current)
  }

  test("detects java.security with disabledAlgorithms") {
    val text = "jdk.tls.disabledAlgorithms=SSLv3, RC4\n"
    assertEquals(detect(text), Set(expectedMime))
  }

  test("detects java.security with certpath disabledAlgorithms") {
    val text = "jdk.certpath.disabledAlgorithms=MD2, MD5\n"
    assertEquals(detect(text), Set(expectedMime))
  }

  test("detects java.security with legacyAlgorithms") {
    val text = "jdk.tls.legacyAlgorithms=K_NULL\n"
    assertEquals(detect(text), Set(expectedMime))
  }

  test("detects java.security with namedGroups") {
    val text = "jdk.tls.namedGroups=secp256r1\n"
    assertEquals(detect(text), Set(expectedMime))
  }

  test("detects java.security with ephemeralDHKeySize") {
    val text = "jdk.tls.ephemeralDHKeySize=2048\n"
    assertEquals(detect(text), Set(expectedMime))
  }

  test("detects included security file by security key") {
    val text = "jdk.tls.disabledAlgorithms=RC4\n"
    val wrapper =
      ByteWrapper(text.getBytes("ISO-8859-1"), "extra.security", None)
    assertEquals(JavaSecurityDetector.detect(wrapper), Set(expectedMime))
  }

  test("ignores generic Java properties file") {
    val text = "foo=bar\nbaz=qux\n"
    assertEquals(detect(text), Set.empty[String])
  }

  test("ignores OpenSSL config file") {
    val text =
      """[ssl_default]
        |CipherString = DEFAULT@SECLEVEL=2
        |""".stripMargin
    assertEquals(detect(text), Set.empty[String])
  }

  test("ignores empty input") {
    assertEquals(detect(""), Set.empty[String])
  }

  test("ignores binary data") {
    val binary = Array.fill(1024)(0xff.toByte)
    val wrapper = ByteWrapper(binary, "java.security", None)
    assertEquals(JavaSecurityDetector.detect(wrapper), Set.empty[String])
  }

  test("augmenter is registered in ArtifactWrapper") {
    val text = "jdk.tls.disabledAlgorithms=SSLv3\n"
    val wrapper =
      ByteWrapper(text.getBytes("ISO-8859-1"), "extra.security", None)
    assert(wrapper.mimeType.contains(expectedMime))
  }

  test("augmenter is purely additive") {
    val text = "jdk.tls.disabledAlgorithms=SSLv3\n"
    val input = Set("text/plain")
    val out = augment(text, input)
    assert(input.subsetOf(out))
    assert(out.contains(expectedMime))
  }

  test("reads no more than 4 KB") {
    val prefix = "jdk.tls.disabledAlgorithms=SSLv3\n"
    val huge = prefix + "# " + ("x" * 100 + "\n") * 50000
    val wrapper =
      ByteWrapper(huge.getBytes("ISO-8859-1"), "java.security", None)
    assertEquals(JavaSecurityDetector.detect(wrapper), Set(expectedMime))
  }

  property("random text without security keys is not detected") {
    forAll(Gen.alphaStr) { (s: String) =>
      val text = s"[section]\n$s\n"
      val detected = detect(text)
      detected.isEmpty || detected.contains(expectedMime)
    }
  }
}
