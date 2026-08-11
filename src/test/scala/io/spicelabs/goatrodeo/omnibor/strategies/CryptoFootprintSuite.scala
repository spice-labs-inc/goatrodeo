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
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.ByteWrapper
import munit.FunSuite

import scala.collection.immutable.TreeSet

/** Phase E — Binary crypto algorithm-footprint scanner.
  *
  * Verifies EVP/Go/Rust/.NET footprint detection, precision (a bare substring
  * such as "aes" must not mint an asset), preservation of the existing
  * EmbeddedCertificates behavior, the read bound, and the totality of the
  * pattern table (every emitted algorithm is a known canonical name).
  */
class CryptoFootprintSuite extends FunSuite {

  private val adHoc = MKC.adHoc("CryptoAlgorithms")

  private def artifact(name: String, content: String): ByteWrapper =
    ByteWrapper(content.getBytes("ISO-8859-1"), name, None)

  private def meta(
      name: String,
      content: String
  ): Map[String, TreeSet[StringOrPair]] = {
    val a = artifact(name, content)
    new CryptoFootprintState(a).invokeBuildMetadata(a).toMap
  }

  test("T-E-01 binary with EVP_aes_256_gcm emits aes-256-gcm") {
    val m = meta(
      "libssl.so",
      "\u0000\u0000libcrypto strings \u0000 EVP_aes_256_gcm \u0000 EVP_sha256 \u0000"
    )
    val algs = m(adHoc("algorithm")).toVector.map(_.value).toSet
    assert(algs.contains("aes-256-gcm"), s"aes-256-gcm missing: $algs")
    assert(algs.contains("sha-256"), s"sha-256 missing: $algs")
    assert(m(adHoc("classifier")).toVector.map(_.value).toSet == Set("evp"))
    assert(
      m(adHoc("value")).toVector.map(_.value).toSet == Set(
        "EVP_aes_256_gcm",
        "EVP_sha256"
      )
    )
    assert(m(adHoc("confidence")).toVector.map(_.value).toSet == Set("symbol"))
    assert(!m.contains(adHoc("unknown")), "all EVP needles resolve")
  }

  test("T-E-02 Go binary with crypto/sha256 emits sha-256") {
    val m = meta(
      "server",
      "\u0000go:build\u0000 crypto/sha256 \u0000 golang.org/x/crypto/curve25519 \u0000"
    )
    val algs = m(adHoc("algorithm")).toVector.map(_.value).toSet
    assert(algs.contains("sha-256"))
    assert(algs.contains("curve25519"))
    assert(m(adHoc("classifier")).toVector.map(_.value).toSet == Set("golang"))
  }

  test("T-E-03 a bare substring like aes must not mint an asset") {
    assert(
      !CryptoFootprintStrategy.isKnownNeedle("aes"),
      "bare 'aes' is not a signal"
    )
    assert(CryptoFootprintStrategy.scan("the aes cipher in libc").isEmpty)
    assert(
      !CryptoFootprintStrategy.detects(
        "some library text mentioning AES ciphers"
      )
    )
    // A distinctive identifier still works.
    assert(CryptoFootprintStrategy.detects("EVP_sha256"))
  }

  test(
    "T-E-04 EmbeddedCertificates behavior is preserved for PEM-marker libs"
  ) {
    val markerLib = ByteWrapper(
      (
        "\u0000\u0000-----BEGIN CERTIFICATE-----\u0000MIIB\u0000-----END CERTIFICATE-----\u0000"
      ).getBytes("ISO-8859-1"),
      "usr/lib/libmbedtls.so.2.14.1",
      None
    )
    val evpBin = ByteWrapper(
      "\u0000opaque EVP_aes_256_gcm\u0000\u0000".getBytes("ISO-8859-1"),
      "usr/local/lib/libapp.so",
      None
    )
    val strategies = ToProcess.strategiesForArtifacts(
      Vector(markerLib, evpBin),
      _ => (),
      false
    )
    assert(
      strategies.exists(_.isInstanceOf[EmbeddedCertificateToProcess]),
      "PEM-marker library must still be claimed by EmbeddedCertificates"
    )
    assert(
      strategies.exists(_.isInstanceOf[CryptoFootprintToProcess]),
      "binary with EVP symbols must be claimed by the footprint scanner"
    )
  }

  test("T-E-05 scan is bounded: needles beyond the cap are not seen") {
    val near =
      artifact("lib.so", "prefix" + ("A" * 1024) + "EVP_sha256" + "suffix")
    assert(
      CryptoFootprintStrategy.contentOf(near).contains("EVP_sha256"),
      "in-cap needle is read"
    )

    val far = artifact(
      "lib.so",
      ("A" * (CryptoFootprintStrategy.MaxReadBytes + 1)) + "EVP_sha256"
    )
    assert(
      !CryptoFootprintStrategy.contentOf(far).contains("EVP_sha256"),
      "needle beyond the read bound must not be seen"
    )
    // Huge content scans without error.
    val _ = CryptoFootprintStrategy.scan(
      "B" * (CryptoFootprintStrategy.MaxReadBytes * 2)
    )
  }

  test(
    "T-E-06 property: every emitted algorithm value is a known canonical name"
  ) {
    // The pattern table is total: Every algorithm the scanner can emit is in
    // knownAlgorithms and is lowercase-hyphenated.
    val battery = Vector(
      "libssl.so" -> "EVP_aes_256_gcm EVP_chacha20_poly1305 EVP_ripemd160",
      "server" -> "crypto/sha512 golang.org/x/crypto/argon2 golang.org/x/crypto/bcrypt",
      "app" -> "System.Security.Cryptography.AesGcm System.Security.Cryptography.ECDsa",
      "lib" -> "aes-gcm sha2 rustls "
    )
    battery.foreach { case (name, needles) =>
      val m = meta(name, needles)
      assert(m.nonEmpty, s"[$name] expected metadata")
      val algs = m(adHoc("algorithm")).toVector.map(_.value)
      algs.foreach { a =>
        assert(
          CryptoFootprintStrategy.knownAlgorithms.contains(a) &&
            a.matches("[-a-z0-9]+"),
          s"[$name] algorithm '$a' must be known and canonical"
        )
      }
      // `rustls` is a library with no single algorithm → flagged unknown.
      if (needles.contains("rustls")) {
        assertEquals(m(adHoc("unknown")).head.value, "true")
      }
    }
    // Closed vocabulary over the table itself.
    assert(
      CryptoFootprintStrategy.allNeedles.nonEmpty,
      "the needle table must be non-empty"
    )
  }
}
