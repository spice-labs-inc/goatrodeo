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
import org.apache.commons.io.FileUtils
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.immutable.TreeSet

/** Phase 2 — Unit tests for `JavaSecurityParser`.
  *
  * These tests verify that the parser extracts the five security-relevant
  * properties, tokenizes comma-separated lists, handles Java properties
  * conventions (line continuations, escapes, whitespace), tolerates malformed
  * input, and respects the read budget.
  */
class JavaSecurityParserSuite extends FunSuite with ScalaCheckSuite {

  private def parse(text: String): JavaSecurityData = {
    JavaSecurityParser.parseString(text).get
  }

  test("parses all five security properties") {
    val text =
      """jdk.tls.disabledAlgorithms=SSLv3, RC4, DES, MD5withRSA
        |jdk.certpath.disabledAlgorithms=MD2, MD5, SHA1
        |jdk.tls.legacyAlgorithms=K_NULL, M_NULL
        |jdk.tls.namedGroups=secp256r1, secp384r1
        |jdk.tls.ephemeralDHKeySize=2048
        |""".stripMargin
    val data = parse(text)
    assertEquals(
      data.disabledAlgorithms,
      TreeSet("SSLv3", "RC4", "DES", "MD5withRSA")
    )
    assertEquals(data.certpathDisabledAlgorithms, TreeSet("MD2", "MD5", "SHA1"))
    assertEquals(data.legacyAlgorithms, TreeSet("K_NULL", "M_NULL"))
    assertEquals(data.namedGroups, TreeSet("secp256r1", "secp384r1"))
    assertEquals(data.ephemeralDHKeySize, Some("2048"))
  }

  test("handles line continuations") {
    val text = "jdk.tls.disabledAlgorithms=SSLv3, \\\n  RC4, \\\n  DES\n"
    val data = parse(text)
    assertEquals(data.disabledAlgorithms, TreeSet("SSLv3", "RC4", "DES"))
  }

  test("trims whitespace and drops empty tokens") {
    val text = "jdk.tls.disabledAlgorithms=SSLv3 , , RC4 ,"
    val data = parse(text)
    assertEquals(data.disabledAlgorithms, TreeSet("SSLv3", "RC4"))
  }

  test("missing properties produce empty sets") {
    val text = "jdk.tls.disabledAlgorithms=SSLv3\n"
    val data = parse(text)
    assertEquals(data.disabledAlgorithms, TreeSet("SSLv3"))
    assertEquals(data.certpathDisabledAlgorithms, TreeSet.empty[String])
    assertEquals(data.legacyAlgorithms, TreeSet.empty[String])
    assertEquals(data.namedGroups, TreeSet.empty[String])
    assertEquals(data.ephemeralDHKeySize, None)
  }

  test("does not throw on IOException") {
    val dir = Files.createTempDirectory("java-security-test")
    val fakeFile = new File(dir.toFile(), "java.security")
    fakeFile.mkdir()
    try {
      val wrapper = FileWrapper(fakeFile, "java.security", None)
      val result = JavaSecurityParser.parse(wrapper)
      assert(result.isFailure)
    } finally {
      FileUtils.deleteDirectory(dir.toFile())
    }
  }

  test("preserves internal whitespace tokens") {
    val text =
      "jdk.tls.disabledAlgorithms=RSA keySize < 2048, DSA keySize < 2048"
    val data = parse(text)
    assertEquals(
      data.disabledAlgorithms,
      TreeSet("RSA keySize < 2048", "DSA keySize < 2048")
    )
  }

  test("handles unicode escapes") {
    val text = "jdk.tls.disabledAlgorithms=\\u0041\\u0042\\u0043, RC4"
    val data = parse(text)
    assertEquals(data.disabledAlgorithms, TreeSet("ABC", "RC4"))
  }

  test("respects the 1 MB read budget") {
    val prefix =
      """jdk.tls.disabledAlgorithms=SSLv3
        |""".stripMargin
    val huge = prefix + "# " + ("x" * 100 + "\n") * 20000
    val bytes = huge.getBytes(StandardCharsets.ISO_8859_1)
    assert(bytes.length > JavaSecurityParser.MaxReadBytes)
    val wrapper = ByteWrapper(bytes, "java.security", None)
    val result = JavaSecurityParser.parse(wrapper)
    assert(result.isSuccess)
    assertEquals(result.get.disabledAlgorithms, TreeSet("SSLv3"))
  }

  test("returns empty data for unrelated properties file") {
    val text = "foo=bar\nbaz=qux\n"
    val data = parse(text)
    assert(!data.hasSecurityData)
  }

  test("handles empty input") {
    val data = parse("")
    assert(!data.hasSecurityData)
  }

  test("handles comment-only input") {
    val data = parse("# comment\n! another comment\n")
    assert(!data.hasSecurityData)
  }

  property("random comma-separated tokens tokenize correctly") {
    forAll(Gen.listOf(Gen.alphaNumStr.filter(_.nonEmpty))) {
      (tokens: List[String]) =>
        val unique = tokens.distinct
        val value = unique.mkString(",")
        val data = JavaSecurityParser
          .parseString(
            s"jdk.tls.disabledAlgorithms=$value\n"
          )
          .get
        data.disabledAlgorithms == TreeSet.from(unique)
    }
  }

  property("random printable text never causes an uncaught exception") {
    forAll(Gen.alphaStr) { (s: String) =>
      val result =
        JavaSecurityParser.parseString(s"jdk.tls.disabledAlgorithms=$s\n")
      result.isSuccess
    }
  }
}
