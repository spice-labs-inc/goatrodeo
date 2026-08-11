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

/** Phase A — Tests for the ServiceTlsConfig strategy's cipher-suite capture.
  *
  * Verifies that nginx/lighttpd cipher directives are captured into
  * `TLSConfig:CipherString` and decomposed into `TLSConfig:algorithms`, and
  * that UCI configs without cipher directives stay unchanged.
  */
class ServiceTlsConfigSuite extends FunSuite {

  private val adHoc = MKC.adHoc("TLSConfig")

  private def configArtifact(
      name: String,
      content: String
  ): ByteWrapper = {
    ByteWrapper(content.getBytes("UTF-8"), name, None)
  }

  private def parse(
      artifact: ByteWrapper
  ): Map[String, TreeSet[StringOrPair]] = {
    new ServiceTlsConfigState(artifact)
      .invokeParseArtifact(artifact)
      .toMap
  }

  test("T-A-08 nginx ssl_ciphers decompose into TLSConfig:algorithms") {
    val nginx =
      """server {
        |    listen 443 ssl;
        |    ssl_certificate     /etc/nginx/tls/server.crt;
        |    ssl_certificate_key /etc/nginx/tls/server.key;
        |    ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:HIGH;
        |}
        |""".stripMargin
    val meta = parse(configArtifact("etc/nginx/nginx.conf", nginx))
    assertEquals(
      meta(adHoc("CipherString")).head.value,
      "ECDHE-RSA-AES128-GCM-SHA256:HIGH"
    )
    val algorithms = meta(adHoc("algorithms")).toVector.map(_.value).sorted
    assertEquals(
      algorithms,
      Vector("aes-128-gcm", "ecdh", "rsa", "sha-256")
    )
  }

  test("T-A-08 lighttpd ssl.cipher-list decomposes") {
    val lighttpd =
      """$SERVER["socket"] == ":443" {
        |    ssl.engine = "enable"
        |    ssl.pemfile = "/etc/lighttpd/tls/server.pem"
        |    ssl.privkey = "/etc/lighttpd/tls/server.key"
        |    ssl.cipher-list => "ECDHE-ECDSA-AES256-GCM-SHA384"
        |}
        |""".stripMargin
    val meta = parse(configArtifact("etc/lighttpd/lighttpd.conf", lighttpd))
    assertEquals(
      meta(adHoc("CipherString")).head.value,
      "ECDHE-ECDSA-AES256-GCM-SHA384"
    )
    val algorithms = meta(adHoc("algorithms")).toVector.map(_.value).sorted
    assertEquals(
      algorithms,
      Vector("aes-256-gcm", "ecdh", "ecdsa", "sha-384")
    )
  }

  test("T-A-08 UCI uhttpd without cipher directives stays unchanged") {
    val uci =
      """config uhttpd 'main'
        |	option redirect_https 1
        |	option cert '/etc/uhttpd.crt'
        |	option key '/etc/uhttpd.key'
        |""".stripMargin
    val meta = parse(configArtifact("etc/config/uhttpd", uci))
    assertEquals(meta.get(adHoc("RedirectHttps")).map(_.head.value), Some("1"))
    assert(!meta.contains(adHoc("CipherString")))
    assert(!meta.contains(adHoc("algorithms")))
  }
}
