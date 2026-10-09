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
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ByteWrapper

import scala.collection.immutable.TreeSet

/** Mobile / JVM TLS policy config detection.
  *
  * Verifies Android network-security-config cleartext/trust flags, manifest
  * usesCleartextTraffic, Apple ATS exceptions, and JDK crypto.policy capture.
  */
class MobileTlsSuite extends GoatRodeoFunSuite {

  private val mt = MKC.adHoc("MobileTls")
  private val js = MKC.adHoc("java.security")

  private def artifact(name: String, content: String): ByteWrapper =
    ByteWrapper(content.getBytes("UTF-8"), name, None)

  private def meta(
      name: String,
      content: String
  ): Map[String, TreeSet[StringOrPair]] = {
    val a = artifact(name, content)
    new MobileTlsState(a).invokeBuildMetadata(a).toMap
  }

  test("network_security_config.xml cleartext + custom CA + TOFU") {
    val m = meta(
      "res/xml/network_security_config.xml",
      """<network-security-config>
        |    <base-config cleartextTrafficPermitted="true">
        |        <trust-anchors>
        |            <trust-on-first-use/>
        |            <certificates src="@raw/custom_ca"/>
        |        </trust-anchors>
        |    </base-config>
        |</network-security-config>
        |""".stripMargin
    )
    assertEquals(m(mt("cleartext_allowed")).head.value, "true")
    assertEquals(m(mt("custom_ca")).head.value, "true")
    assertEquals(m(mt("trust_on_first_use")).head.value, "true")
  }

  test("AndroidManifest usesCleartextTraffic") {
    val m = meta(
      "AndroidManifest.xml",
      """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        |          android:usesCleartextTraffic="true" package="com.example.app">
        |    <application/>
        |</manifest>
        |""".stripMargin
    )
    assertEquals(m(mt("manifest_cleartext")).head.value, "true")
  }

  test("Info.plist ATS arbitrary loads + exception domains") {
    val m = meta(
      "Foo.app/Info.plist",
      """<?xml version="1.0" encoding="UTF-8"?>
        |<plist version="1.0"><dict>
        |  <key>NSAppTransportSecurity</key>
        |  <dict>
        |    <key>NSAllowsArbitraryLoads</key><true/>
        |    <key>NSExceptionDomains</key><dict>
        |      <key>example.com</key><dict><key>NSIncludesSubdomains</key><true/></dict>
        |    </dict>
        |  </dict>
        |</dict></plist>
        |""".stripMargin
    )
    assertEquals(m(mt("ats_arbitrary_loads")).head.value, "true")
    assertEquals(m(mt("ats_exceptions")).head.value, "true")
  }

  test("JDK crypto.policy") {
    val m = meta(
      "jvm/conf/security/crypto.policy",
      "crypto.policy=unlimited\n"
    )
    assertEquals(m(js("crypto_policy")).head.value, "unlimited")
  }

  test("policy configs carry no secrets") {
    val battery = Vector(
      "res/xml/network_security_config.xml" ->
        """<network-security-config><base-config cleartextTrafficPermitted="true">
          |<trust-anchors><certificates src="@raw/custom"/></trust-anchors></base-config></network-security-config>
          |""".stripMargin,
      "AndroidManifest.xml" -> """<manifest android:usesCleartextTraffic="true"><application/></manifest>""",
      "Foo.app/Info.plist" ->
        """<dict><key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict></dict>""",
      "jvm/conf/security/crypto.policy" -> "crypto.policy=limited\n"
    )
    val b64ish = """[A-Za-z0-9+/]{40,}=""".r
    battery.foreach { case (name, content) =>
      val m = meta(name, content)
      val all = m.values.toVector.flatMap(_.toVector.map(_.value))
      assert(all.nonEmpty, s"[$name] expected metadata")
      assert(
        all.forall(_.length < 60),
        s"[$name] values must be short: ${all.mkString(",")}"
      )
      assert(
        !all.exists(v => b64ish.findFirstIn(v).isDefined),
        s"[$name] secret-looking value"
      )
      assert(!all.exists(_.contains("PRIVATE KEY")), s"[$name] key material")
    }
  }

  // ------------------------------------------------------------------
  // Phase 3 — ATS parsing through binary plists (plan Mtl-3.x)
  // ------------------------------------------------------------------

  import io.spicelabs.goatrodeo.testsupport.BplistEncoder
  import org.json4s.*
  import org.json4s.JsonDSL.*

  private def binaryPlistMeta(
      name: String,
      jv: JValue
  ): Map[String, TreeSet[StringOrPair]] = {
    val a = ByteWrapper(BplistEncoder.encode(jv), name, None)
    new MobileTlsState(a).invokeBuildMetadata(a).toMap
  }

  // Mtl-3.1 — binary-plist Info.plist with NSAllowsArbitraryLoads=true
  // yields the same ATS flag as the XML twin.
  test("Mtl-3.1 binary plist ATS arbitrary loads matches XML twin") {
    val binary = binaryPlistMeta(
      "Foo.app/Info.plist",
      JObject(
        "NSAppTransportSecurity" ->
          JObject("NSAllowsArbitraryLoads" -> JBool(true))
      )
    )
    assertEquals(binary(mt("FileType")).head.value, "apple-ats")
    assertEquals(binary(mt("ats_arbitrary_loads")).head.value, "true")

    // XML twin (legacy path) produces the same flags.
    val xml = meta(
      "Foo.app/Info.plist",
      """<plist version="1.0"><dict><key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict></dict></plist>"""
    )
    assertEquals(xml(mt("FileType")).head.value, "apple-ats")
    assertEquals(xml(mt("ats_arbitrary_loads")).head.value, "true")
  }

  // Mtl-3.2 — binary plist with NSExceptionDomains entries → domain flags.
  test("Mtl-3.2 binary plist exception domains are emitted") {
    val m = binaryPlistMeta(
      "Foo.app/Info.plist",
      JObject(
        "NSAppTransportSecurity" -> JObject(
          "NSExceptionDomains" -> JObject(
            "example.com" -> JObject(
              "NSExceptionAllowsInsecureHTTPLoads" -> JBool(true)
            )
          )
        )
      )
    )
    assertEquals(m(mt("FileType")).head.value, "apple-ats")
    assertEquals(m(mt("ats_exceptions")).head.value, "true")
  }

  // Mtl-3.3 — binary plist with no ATS keys → no ATS metadata.
  test("Mtl-3.3 binary plist without ATS yields no ATS metadata") {
    val m = binaryPlistMeta(
      "Foo.app/Info.plist",
      JObject(
        "CFBundleIdentifier" -> JString("org.example.Min")
      )
    )
    assert(m.isEmpty, s"no ATS metadata expected, got $m")
  }

  // Mtl-3.4 — invalid plist bytes → no ATS metadata, no exception.
  test("Mtl-3.4 invalid plist yields no ATS metadata, no exception") {
    val garbage = "this is not a plist at all, just text".getBytes("UTF-8")
    val a = ByteWrapper(garbage, "Foo.app/Info.plist", None)
    val m =
      try new MobileTlsState(a).invokeBuildMetadata(a).toMap
      catch {
        case e: Throwable => fail(s"must not throw: $e")
      }
    assert(m.isEmpty, s"no ATS metadata from garbage, got $m")
  }

  // Mtl-3.5 — today's XML ATS tests still pass unchanged (regression proof
  // is the suite itself: the XML path above and the original tests).
  test("Mtl-3.5 xml ATS regression") {
    val m = meta(
      "Foo.app/Info.plist",
      """<plist version="1.0"><dict><key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/><key>NSExceptionDomains</key><dict><key>x.com</key><dict/></dict><key>NSAllowsLocalNetworking</key><true/></dict></dict></plist>"""
    )
    assertEquals(m(mt("ats_arbitrary_loads")).head.value, "true")
    assertEquals(m(mt("ats_exceptions")).head.value, "true")
    assertEquals(m(mt("ats_local_networking")).head.value, "true")
  }

  // Mtl-3.6 — strategy-level: a binary-plist Info.plist claims through the
  // full claim+process pipeline and emits MobileTls: metadata.
  test("Mtl-3.6 strategy-level binary plist ATS through the pipeline") {
    import io.spicelabs.goatrodeo.omnibor.{MemStorage, ToProcess}
    import io.spicelabs.goatrodeo.util.Configuration
    given Configuration = Configuration(packageTags = true)

    val plistBytes = BplistEncoder.encode(
      JObject(
        "NSAppTransportSecurity" ->
          JObject("NSAllowsArbitraryLoads" -> JBool(true))
      )
    )
    val wrapper = ByteWrapper(
      plistBytes,
      "Payload/ATSApp.app/Info.plist",
      None
    )
    val store = MemStorage(None)
    val byUuid = Map(wrapper.uuid -> wrapper)
    val byName = Map(wrapper.path() -> Vector(wrapper))
    val (tp, _, _, _) = MobileTlsStrategy.computeMobileTlsFiles(byUuid, byName)
    assert(tp.nonEmpty, "binary Info.plist must be claimed by MobileTls")
    ToProcess.buildGraphForToProcess(tp, store)

    val atsLoads = store.keys().toVector
      .flatMap(k => store.read(k))
      .flatMap(_.bodyAsItemMetaData)
      .flatMap(_.extra.get(mt("ats_arbitrary_loads")))
    assert(
      atsLoads.nonEmpty && atsLoads.head.headOption.exists(_.value == "true"),
      "MobileTls:ats_arbitrary_loads must land from a binary plist"
    )
  }

  // Mtl-3.7 — a binary plist larger than MaxReadBytes → no ATS metadata,
  // no exception (the read cap is enforced).
  test("Mtl-3.7 oversized binary plist is refused") {
    // Build a plist with a huge nested array so the encoding exceeds
    // MaxReadBytes.
    val big = JObject(
      "NSAppTransportSecurity" -> JObject(
        "NSAllowsArbitraryLoads" -> JBool(true),
        "Blob" -> JString("x" * (2 * 1024 * 1024))
      )
    )
    val bytes = BplistEncoder.encode(big)
    assert(
      bytes.length > MobileTlsStrategy.MaxReadBytes,
      "test requires an oversized plist"
    )
    val a = ByteWrapper(bytes, "Foo.app/Info.plist", None)
    val m =
      try new MobileTlsState(a).invokeBuildMetadata(a).toMap
      catch {
        case e: Throwable => fail(s"must not throw: $e")
      }
    assert(
      m.get(mt("ats_arbitrary_loads")).isEmpty,
      s"oversized plist must not produce ATS metadata, got $m"
    )
  }
}
