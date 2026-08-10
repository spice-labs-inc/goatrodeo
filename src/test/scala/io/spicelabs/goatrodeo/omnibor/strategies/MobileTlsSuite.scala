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
import munit.FunSuite

import scala.collection.immutable.TreeSet

/** Phase F — Mobile / JVM TLS policy config detection.
  *
  * Verifies Android network-security-config cleartext/trust flags, manifest
  * usesCleartextTraffic, Apple ATS exceptions, and JDK crypto.policy capture.
  */
class MobileTlsSuite extends FunSuite {

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

  test("T-F-07 network_security_config.xml cleartext + custom CA + TOFU") {
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

  test("T-F-08 AndroidManifest usesCleartextTraffic") {
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

  test("T-F-09 Info.plist ATS arbitrary loads + exception domains") {
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

  test("T-F-09b JDK crypto.policy") {
    val m = meta(
      "jvm/conf/security/crypto.policy",
      "crypto.policy=unlimited\n"
    )
    assertEquals(m(js("crypto_policy")).head.value, "unlimited")
  }

  test("T-F-10 policy configs carry no secrets") {
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
      assert(all.forall(_.length < 60), s"[$name] values must be short: ${all.mkString(",")}")
      assert(!all.exists(v => b64ish.findFirstIn(v).isDefined), s"[$name] secret-looking value")
      assert(!all.exists(_.contains("PRIVATE KEY")), s"[$name] key material")
    }
  }
}