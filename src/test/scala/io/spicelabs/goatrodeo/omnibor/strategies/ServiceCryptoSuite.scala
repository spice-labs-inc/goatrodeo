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

/** Phase B — Service/transform config dialect capture + Kerberos enctypes.
  *
  * Verifies per-dialect parsing (OpenVPN, strongSwan, Mosquitto, WireGuard,
  * Kerberos, HAProxy/Redis/PostgreSQL/MySQL basics), the presence-only secret
  * rule (secrets are never echoed), tolerant empty parsing, and no-claim for
  * unknown files.
  */
class ServiceCryptoSuite extends FunSuite {

  private val sc = MKC.adHoc("ServiceCrypto")
  private val krb = MKC.adHoc("Kerberos")

  private def configArtifact(
      name: String,
      content: String
  ): ByteWrapper = {
    ByteWrapper(content.getBytes("UTF-8"), name, None)
  }

  private def meta(
      name: String,
      content: String
  ): Map[String, TreeSet[StringOrPair]] = {
    val a = configArtifact(name, content)
    new ServiceCryptoState(a).invokeBuildMetadata(a).toMap
  }

  test("T-B-01 OpenVPN data-ciphers decompose into ServiceCrypto:algorithms") {
    val m = meta(
      "etc/openvpn/client.ovpn",
      """client
        |dev tun
        |proto udp
        |remote vpn.example.com 1194
        |cipher AES-256-GCM
        |data-ciphers AES-256-GCM:AES-128-GCM
        |auth SHA256
        |key /etc/openvpn/client.key
        |cert /etc/openvpn/client.crt
        |""".stripMargin
    )
    assertEquals(m(sc("service")).head.value, "openvpn")
    val algs = m(sc("algorithms")).toVector.map(_.value).sorted
    assertEquals(algs, Vector("aes-128-gcm", "aes-256-gcm", "sha-256"))
    assertEquals(
      m(sc("key_file")).head.value,
      "/etc/openvpn/client.key"
    )
  }

  test("T-B-02 strongSwan ike/esp transforms decompose") {
    val m = meta(
      "etc/ipsec.conf",
      """conn %default
        |	ike=aes256-sha256-modp2048
        |	esp=aes256gcm16-sha256
        |""".stripMargin
    )
    assertEquals(m(sc("service")).head.value, "strongswan")
    val algs = m(sc("algorithms")).toVector.map(_.value).toSet
    assert(algs.contains("aes-256"), s"aes-256 missing: $algs")
    assert(algs.contains("sha-256"), s"sha-256 missing: $algs")
    assert(algs.contains("ffdhe-2048"), s"ffdhe-2048 missing: $algs")
    assert(algs.contains("aes-256-gcm"), s"esp gcm missing: $algs")
    assertEquals(
      m(sc("transform:0")).head.value,
      "aes256-sha256-modp2048",
      "first IKE proposal recorded"
    )
    assertEquals(
      m(sc("transform:1")).head.value,
      "aes256gcm16-sha256",
      "first ESP proposal recorded"
    )
  }

  test("T-B-03 Mosquitto psk_file is presence-only (no secret, no path echo)") {
    val m = meta(
      "etc/mosquitto/mosquitto.conf",
      """listener 8883
        |cafile /etc/mosquitto/certs/ca.crt
        |certfile /etc/mosquitto/certs/server.crt
        |keyfile /etc/mosquitto/certs/server.key
        |tls_ciphers TLS_AES_256_GCM_SHA384
        |psk_file /etc/mosquitto/passwd
        |""".stripMargin
    )
    assertEquals(m(sc("service")).head.value, "mosquitto")
    assertEquals(m(sc("psk_present")).head.value, "true")
    assert(
      m.values.forall(!_.exists(_.value.contains("/etc/mosquitto/passwd"))),
      "psk_file path/content must never be echoed"
    )
    assertEquals(m(sc("cert_file")).head.value, "/etc/mosquitto/certs/server.crt")
  }

  test("T-B-04 WireGuard secrets are presence-only; base64 never emitted") {
    val priv = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    val psk = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
    val m = meta(
      "etc/wireguard/wg0.conf",
      s"""[Interface]
         |PrivateKey = $priv
         |Address = 10.0.0.1/24
         |
         |[Peer]
         |PublicKey = CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=
         |PresharedKey = $psk
         |""".stripMargin
    )
    assertEquals(m(sc("service")).head.value, "wireguard")
    assertEquals(m(sc("private_key_present")).head.value, "true")
    assertEquals(m(sc("psk_present")).head.value, "true")
    val all = m.values.toVector.flatMap(_.toVector.map(_.value))
    assert(!all.exists(_.contains(priv)), "PrivateKey value leaked")
    assert(!all.exists(_.contains(psk)), "PresharedKey value leaked")
  }

  test("T-B-05 krb5.conf enctypes inventory") {
    val m = meta(
      "etc/krb5.conf",
      """[libdefaults]
        |	default_realm = EXAMPLE.COM
        |	default_tkt_enctypes = aes256-cts-hmac-sha1-96
        |	default_tgs_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96
        |""".stripMargin
    )
    val encs = m(krb("enctypes")).toVector.map(_.value).toSet
    assert(encs.contains("aes256-cts-hmac-sha1-96"))
    assert(encs.contains("aes128-cts-hmac-sha1-96"))
    assertEquals(
      m(krb("enctype:aes256-cts-hmac-sha1-96")).head.value,
      "true"
    )
  }

  test("T-B-06 config without crypto settings yields empty metadata") {
    val m = meta(
      "etc/redis.conf",
      """bind 127.0.0.1
        |port 6379
        |""".stripMargin
    )
    assert(m.isEmpty, s"expected empty metadata, got $m")
  }

  test("T-B-07 unknown service config is not claimed") {
    assertEquals(ServiceCryptoStrategy.detectService("app.config"), None)
    assertEquals(ServiceCryptoStrategy.detectService("etc/nginx/nginx.conf"), None)
    assertEquals(
      ServiceCryptoStrategy.detectService("etc/openvpn/server.conf"),
      Some("openvpn")
    )
    assertEquals(
      ServiceCryptoStrategy.detectService("etc/krb5.conf"),
      Some("kerberos")
    )
  }

  test("T-B-08 property: emitted ServiceCrypto/Kerberos values carry no secrets") {
    val battery = Vector(
      "etc/wireguard/wg0.conf" -> """[Interface]
        |PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        |PresharedKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
        |""".stripMargin,
      "etc/openvpn/client.ovpn" -> """cipher AES-256-GCM
        |data-ciphers AES-256-GCM:AES-128-GCM
        |key /etc/openvpn/client.key
        |""".stripMargin,
      "etc/mosquitto/mosquitto.conf" -> "psk_file /etc/mosquitto/passwd\n",
      "etc/krb5.conf" -> "default_tgs_enctypes = aes256-cts-hmac-sha1-96\n",
      "etc/haproxy/haproxy.cfg" -> "ssl-default-bind-ciphers HIGH:!aNULL\n"
    )
    val b64ish = """[A-Za-z0-9+/]{40,}=""".r
    battery.foreach { case (name, content) =>
      val m = meta(name, content)
      val values = m.values.toVector.flatMap(_.toVector.map(_.value))
      assert(
        !values.exists(_.contains("PRIVATE KEY")),
        s"[$name] private key material in metadata"
      )
      assert(
        !values.exists(v => b64ish.findFirstIn(v).isDefined),
        s"[$name] base64 secret-looking value in metadata: ${values.mkString(",")}"
      )
    }
  }
}