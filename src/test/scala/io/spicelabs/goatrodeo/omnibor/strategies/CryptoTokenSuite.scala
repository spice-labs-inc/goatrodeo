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

/** Phase C — JWT/JWK inventory.
  *
  * Verifies JWT header algorithm extraction (including the `alg:none` security
  * finding), JWK kty/crv/use/size and private-members presence, that token
  * payloads/signatures and JWK key material are never echoed, tolerant handling
  * of garbage, and an output-level no-secret property check.
  */
class CryptoTokenSuite extends FunSuite {

  private val jwtAdHoc = MKC.adHoc("JWT")
  private val jwkAdHoc = MKC.adHoc("JWK")

  private def b64url(s: String): String =
    java.util.Base64.getUrlEncoder.withoutPadding
      .encodeToString(s.getBytes("UTF-8"))
  private def b64url(bytes: Array[Byte]): String =
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def artifact(name: String, content: String): ByteWrapper =
    ByteWrapper(content.getBytes("UTF-8"), name, None)

  private def meta(
      name: String,
      content: String
  ): Map[String, TreeSet[StringOrPair]] = {
    val a = artifact(name, content)
    new CryptoTokenState(a).invokeBuildMetadata(a).toMap
  }

  private val hs256 =
    s"${b64url("""{"alg":"HS256","typ":"JWT"}""")}.${b64url("""{"sub":"1234"}""")}.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
  private val noneToken =
    s"${b64url("""{"alg":"none","typ":"JWT"}""")}.${b64url("""{"sub":"x"}""")}."

  private val rsaN = Array.fill[Byte](256)(0x5a.toByte)
  private val rsaJwk =
    s"""{"kty":"RSA","use":"sig","n":"${b64url(rsaN)}","e":"AQAB"}"""
  private val privateJwk =
    s"""{"kty":"EC","crv":"P-256","d":"${b64url(
        "secretdvalidator"
      )}","x":"eJw","y":"A-8"}"""

  test(
    "T-C-01 JWT alg:HS256 yields JWT:alg and canonical signature algorithm"
  ) {
    val m = meta("token.jwt", hs256)
    assertEquals(m(jwtAdHoc("alg")).head.value, "HS256")
    assertEquals(m(jwtAdHoc("signature_algorithm")).head.value, "hmac-sha-256")
  }

  test("T-C-02 JWT alg:none is reported as a finding") {
    val m = meta("token.jwt", "auth=" + noneToken)
    assert(
      m.get(jwtAdHoc("none_present")).exists(_.head.value == "true"),
      "alg:none must be flagged"
    )
    assertEquals(m(jwtAdHoc("alg")).head.value, "none")
  }

  test("T-C-03 JWT token, payload and signature are never emitted") {
    val m = meta("token.jwt", hs256)
    val values = m.values.toVector.flatMap(_.toVector.map(_.value))
    assert(
      !values.exists(v => hs256.contains(v) && v == hs256),
      "full token leaked"
    )
    assert(!values.exists(_.contains("1234")), "payload leaked")
    assert(
      !values.exists(_.contains("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c")),
      "signature leaked"
    )
    assert(
      values.forall(v => v.length < 64),
      s"values should be short tags: $values"
    )
  }

  test("T-C-04 public RSA JWK gives kty/use/size, never n/e") {
    val m = meta("key.jwk.json", rsaJwk)
    assertEquals(m(jwkAdHoc("kty")).head.value, "RSA")
    assertEquals(m(jwkAdHoc("use")).head.value, "sig")
    assertEquals(m(jwkAdHoc("size")).head.value, "2048")
    val values = m.values.toVector.flatMap(_.toVector.map(_.value))
    assert(!values.exists(_.contains(b64url(rsaN))), "n leaked")
    assert(!values.exists(_.contains("AQAB")), "e leaked")
  }

  test("T-C-05 private JWK is presence-only") {
    val m = meta("key.jwk.json", privateJwk)
    assertEquals(m(jwkAdHoc("kty")).head.value, "EC")
    assertEquals(m(jwkAdHoc("crv")).head.value, "P-256")
    assertEquals(m(jwkAdHoc("private_present")).head.value, "true")
    val values = m.values.toVector.flatMap(_.toVector.map(_.value))
    assert(!values.exists(_.contains("secretdvalidator")), "private d leaked")
  }

  test("T-C-06 garbage / truncated tokens are not claimed and never panic") {
    assert(!CryptoTokenStrategy.detects("eyJ123"), "short eyJ only")
    assert(!CryptoTokenStrategy.detects("not a jwt at all"), "plain text")
    assert(
      !CryptoTokenStrategy.detects("eyJhbGciOiJ3cm9uZw"),
      "truncated header"
    )
    assert(CryptoTokenStrategy.detects(hs256), "real token detected")
    assert(CryptoTokenStrategy.detects(rsaJwk), "real JWK detected")
    // Garbage must parse without throwing.
    val m = CryptoTokenStrategy.parseJwts("eyJhbGciOiJ3cm9uZw..###")
    assertEquals(m.algs, Vector.empty)
  }

  test(
    "T-C-07 property: emitted JWT/JWK values are short tags, never secrets"
  ) {
    val battery = Vector(
      "token.jwt" -> hs256,
      "token2.jwt" -> ("bearer " + noneToken),
      "key.jwk.json" -> rsaJwk,
      "key2.jwk" -> privateJwk
    )
    val b64ish = """[A-Za-z0-9+/]{40,}=""".r
    battery.foreach { case (name, content) =>
      val m = meta(name, content)
      val values = m.values.toVector.flatMap(_.toVector.map(_.value))
      assert(values.nonEmpty, s"[$name] expected crypto metadata")
      assert(
        values.forall(_.length < 64),
        s"[$name] values must be short tags: ${values.mkString(",")}"
      )
      assert(
        !values.exists(v => b64ish.findFirstIn(v).isDefined),
        s"[$name] base64 secret-looking value: ${values.mkString(",")}"
      )
      assert(
        !values.exists(_.contains("PRIVATE KEY")),
        s"[$name] private key material in metadata"
      )
    }
  }
}
