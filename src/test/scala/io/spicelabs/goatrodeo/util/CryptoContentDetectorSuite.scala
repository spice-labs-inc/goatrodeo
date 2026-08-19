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

/** Tests for the [[CryptoContentDetector]] MIME augmenter.
  *
  * WHAT: one bounded read per artifact during the MIME pass feeds every
  * content-based strategy detector; a dedicated MIME is emitted per strategy
  * family so strategy selection becomes a pure `mimeType.contains` check with
  * no file reads.
  *
  * WHY: the strategies previously re-read file content during strategy
  * selection (after the MIME pass), multiplying per-file opens and re-touching
  * the page-cache window — a measured 50% regression. These tests pin that each
  * detector's MIME is emitted exactly when its content matches, so the
  * MIME-only claiming in the strategies is trustworthy.
  *
  * THEORY: the augmenter is a pure function of (artifact, currentMimes); each
  * test feeds real content bytes and asserts the emitted MIME set. The
  * detection logic itself stays in the strategy detectors (tested in their own
  * suites); this suite pins the wiring.
  *
  * LLM note: C-A-xx = test id.
  */
class CryptoContentDetectorSuite extends FunSuite {

  private def bytes(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.ISO_8859_1)

  private def wrapper(
      content: String,
      name: String,
      mimes: Set[String] = Set("text/plain")
  ): ByteWrapper = ByteWrapper(bytes(content), name, None)

  private def augment(
      content: String,
      name: String,
      mimes: Set[String] = Set("text/plain")
  ): Set[String] =
    CryptoContentDetector.mimeTypeAugmenter(
      wrapper(content, name, mimes),
      mimes
    )

  test("C-A-01 JWT content emits the crypto-token MIME") {
    val jwt = "header.eyJhbGciOiJIUzI1NiJ9.payload.signature"
    assert(
      augment(jwt, "token.txt").contains(CryptoContentDetector.CryptoTokensMime)
    )
  }

  test("C-A-02 JWK content emits the crypto-token MIME") {
    val jwk = """{"kty":"RSA","n":"abc","e":"AQAB"}"""
    assert(
      augment(jwk, "key.jwk").contains(CryptoContentDetector.CryptoTokensMime)
    )
  }

  test("C-A-03 usign key content emits the usign MIME") {
    val usign =
      "untrusted comment: opkg key\nRWQ6dW9vbG9vZ3k\n"
    assert(
      augment(usign, "key.sig").contains(CryptoContentDetector.UsignKeyMime)
    )
  }

  test("C-A-04 binary EVP symbol emits the crypto-footprint MIME") {
    val binMimes = Set("application/x-sharedlib")
    val content = "garbage\u0000\u0001EVP_sha256\u0000more garbage"
    assert(
      augment(content, "/usr/lib/libcrypto.so", binMimes)
        .contains(CryptoContentDetector.CryptoFootprintMime)
    )
  }

  test("C-A-05 text PEM emits the embedded-pem MIME; binaries do not") {
    val pem =
      "config\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
    assert(
      augment(pem, "/etc/config/app.conf")
        .contains(CryptoContentDetector.EmbeddedPemMime)
    )
    assert(
      !augment(pem, "/usr/lib/libcrypto.so", Set("application/x-sharedlib"))
        .contains(CryptoContentDetector.EmbeddedPemMime)
    )
  }

  test("C-A-06 shadow hash content emits the shadow-password MIME") {
    val shadow = "root:$1$abc$defghijklmnopqrstuv:19000:0:99999:7:::\n"
    assert(
      augment(shadow, "etc/shadow")
        .contains(CryptoContentDetector.ShadowPasswordMime)
    )
    // locked/placeholder content must not match
    val passwd = "nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n"
    assert(
      !augment(passwd, "etc/passwd")
        .contains(CryptoContentDetector.ShadowPasswordMime)
    )
  }

  test("C-A-07 nginx TLS config emits the tls-config MIME") {
    val nginx =
      "server {\n  ssl_certificate /etc/uhttpd.crt;\n  ssl_certificate_key /etc/uhttpd.key;\n}\n"
    assert(
      augment(nginx, "/etc/nginx/sites-enabled/default")
        .contains(CryptoContentDetector.TlsConfigMime)
    )
  }

  test("C-A-08 gradle lockfile emits its MIME by name") {
    val lock = "org.scala-lang:scala3-library_3:3.8.3=compileClasspath\n"
    assert(
      augment(lock, "gradle.lockfile")
        .contains(CryptoContentDetector.GradleLockfileMime)
    )
  }

  test("C-A-09 JVM release file emits its MIME by name + content") {
    val release =
      "JAVA_VERSION=\"21.0.11\"\nJAVA_RUNTIME_VERSION=\"21.0.11+10\"\n"
    assert(
      augment(release, "release")
        .contains(CryptoContentDetector.JvmReleaseMime)
    )
    assert(
      !augment("not a release file", "release")
        .contains(CryptoContentDetector.JvmReleaseMime)
    )
  }

  test("C-A-10 unrelated text emits no strategy MIMEs") {
    val out = augment("just some prose", "notes.txt")
    assert(!out.contains(CryptoContentDetector.CryptoTokensMime))
    assert(!out.contains(CryptoContentDetector.UsignKeyMime))
    assert(!out.contains(CryptoContentDetector.EmbeddedPemMime))
    assert(!out.contains(CryptoContentDetector.CryptoFootprintMime))
    assert(!out.contains(CryptoContentDetector.ShadowPasswordMime))
    assert(!out.contains(CryptoContentDetector.TlsConfigMime))
  }
}
