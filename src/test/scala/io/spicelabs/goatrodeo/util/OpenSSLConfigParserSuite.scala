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

import java.nio.charset.StandardCharsets
import scala.collection.immutable.TreeSet

/** Phase 1 — Unit tests for `OpenSSLConfigParser`.
  *
  * These tests verify that the parser extracts security-relevant directives,
  * follows `ssl_conf` indirection, records `.include` references, handles
  * malformed input without throwing, and respects the read budget.
  */
class OpenSSLConfigParserSuite extends FunSuite with ScalaCheckSuite {

  private def parse(text: String): OpenSSLConfigData = {
    OpenSSLConfigParser.parseString(text).get
  }

  test("parses a minimal security section") {
    val text =
      """[ssl_default]
        |CipherString = DEFAULT@SECLEVEL=2
        |MinProtocol = TLSv1.2
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.cipherString, Some("DEFAULT@SECLEVEL=2"))
    assertEquals(data.minProtocol, Some("TLSv1.2"))
    assertEquals(data.sections, TreeSet("ssl_default"))
  }

  test("collects all security keys") {
    val text =
      """[ssl_default]
        |CipherString = DEFAULT
        |Ciphersuites = TLS_AES_256_GCM_SHA384
        |MinProtocol = TLSv1.2
        |MaxProtocol = TLSv1.3
        |Options = ServerPreference, NoRenegotiation
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.cipherString, Some("DEFAULT"))
    assertEquals(data.cipherSuites, Some("TLS_AES_256_GCM_SHA384"))
    assertEquals(data.minProtocol, Some("TLSv1.2"))
    assertEquals(data.maxProtocol, Some("TLSv1.3"))
    assertEquals(data.options, TreeSet("ServerPreference", "NoRenegotiation"))
  }

  test("follows ssl_conf indirection chain") {
    val text =
      """[openssl_init]
        |ssl_conf = ssl_sect
        |
        |[ssl_sect]
        |system_default = system_default_sect
        |
        |[system_default_sect]
        |CipherString = DEFAULT
        |MinProtocol = TLSv1.2
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.cipherString, Some("DEFAULT"))
    assertEquals(data.minProtocol, Some("TLSv1.2"))
    assert(data.sections.contains("openssl_init"))
    assert(data.sections.contains("ssl_sect"))
    assert(data.sections.contains("system_default_sect"))
  }

  test("records .include references") {
    val text =
      """[ req ]
        |.include /etc/ssl/extra.cnf
        |distinguished_name = req_distinguished_name
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.includeReferences, Vector("/etc/ssl/extra.cnf"))
  }

  test("records multiple .include references") {
    val text =
      """[ req ]
        |.include /etc/ssl/a.cnf
        |.include b.cnf
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.includeReferences, Vector("/etc/ssl/a.cnf", "b.cnf"))
  }

  test("ignores comment lines") {
    val text =
      """# This is a comment
        |; Also a comment
        |[ssl_default]
        |CipherString = DEFAULT
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.cipherString, Some("DEFAULT"))
    assertEquals(data.sections, TreeSet("ssl_default"))
  }

  test("ignores keys outside sections") {
    val text =
      """CipherString = DEFAULT
        |[ssl_default]
        |MinProtocol = TLSv1.2
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.cipherString, None)
    assertEquals(data.minProtocol, Some("TLSv1.2"))
  }

  test("last value wins for duplicate keys") {
    val text =
      """[ssl_default]
        |CipherString = OLD
        |CipherString = NEW
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.cipherString, Some("NEW"))
  }

  test("merges duplicate section names") {
    val text =
      """[ssl_default]
        |CipherString = OLD
        |[ssl_default]
        |MinProtocol = TLSv1.2
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.cipherString, Some("OLD"))
    assertEquals(data.minProtocol, Some("TLSv1.2"))
  }

  test("is case-insensitive for keys") {
    val text =
      """[ssl_default]
        |cipherstring = DEFAULT
        |MINPROTOCOL = TLSv1.2
        |""".stripMargin
    val data = parse(text)
    assertEquals(data.cipherString, Some("DEFAULT"))
    assertEquals(data.minProtocol, Some("TLSv1.2"))
  }

  test("returns empty data for unrelated INI file") {
    val text =
      """[section]
        |key = value
        |another = value
        |""".stripMargin
    val data = parse(text)
    assert(!data.hasSecurityData)
    assertEquals(data.sections, TreeSet.empty[String])
  }

  test("handles empty input") {
    val data = parse("")
    assert(!data.hasSecurityData)
    assertEquals(data.sections, TreeSet.empty[String])
  }

  test("handles comment-only input") {
    val data = parse("# comment\n; comment")
    assert(!data.hasSecurityData)
  }

  test("does not throw on binary data") {
    val binary = (0 until 256).map(_.toByte).toArray ++
      Array.fill(768)(0x00.toByte)
    val wrapper = ByteWrapper(binary, "test.cnf", None)
    val result = OpenSSLConfigParser.parse(wrapper)
    assert(result.isFailure)
  }

  test("does not throw on invalid UTF-8") {
    val bytes = Array[Byte](0x80.toByte, 0x81.toByte, 0x82.toByte)
    val wrapper = ByteWrapper(bytes, "test.cnf", None)
    val result = OpenSSLConfigParser.parse(wrapper)
    assert(result.isSuccess)
  }

  test("respects the 1 MB read budget") {
    val prefix =
      """[ssl_default]
        |CipherString = DEFAULT
        |""".stripMargin
    val huge = prefix + "\n# " + ("x" * 100 + "\n") * 20000
    val bytes = huge.getBytes(StandardCharsets.UTF_8)
    assert(bytes.length > OpenSSLConfigParser.MaxReadBytes)
    val wrapper = ByteWrapper(bytes, "test.cnf", None)
    val result = OpenSSLConfigParser.parse(wrapper)
    assert(result.isSuccess)
    assertEquals(result.get.cipherString, Some("DEFAULT"))
  }

  test("captures multiple security-relevant sections") {
    val text =
      """[ssl_default]
        |CipherString = DEFAULT
        |
        |[tls_system_default]
        |MinProtocol = TLSv1.2
        |
        |[system_default_sect]
        |MaxProtocol = TLSv1.3
        |""".stripMargin
    val data = parse(text)
    assert(data.sections.contains("ssl_default"))
    assert(data.sections.contains("tls_system_default"))
    assert(data.sections.contains("system_default_sect"))
    assertEquals(data.cipherString, Some("DEFAULT"))
    assertEquals(data.minProtocol, Some("TLSv1.2"))
    assertEquals(data.maxProtocol, Some("TLSv1.3"))
  }

  test("stops at max section depth") {
    val chain = (0 until 15)
      .map(i => s"[sect_$i]\nssl_conf = sect_${i + 1}")
      .mkString("\n")
    val text = chain + "\n[sect_15]\nCipherString = DEFAULT\n"
    val data = parse(text)
    // Should still capture CipherString despite depth limit
    assertEquals(data.cipherString, Some("DEFAULT"))
  }

  property("random printable text never causes an uncaught exception") {
    forAll(Gen.alphaStr) { (s: String) =>
      val result = OpenSSLConfigParser.parseString(s"[section]\n$s\n")
      result.isSuccess
    }
  }
}
