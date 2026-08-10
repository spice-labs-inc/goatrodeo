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

/** Phase A — Tests for `CipherSuiteResolver` (cipher-suite decomposition).
  *
  * Verifies that concrete OpenSSL and TLS 1.3 suite names decompose into their
  * constituent algorithms, that grammar keywords and unknown tokens stay
  * name-only (no fabricated algorithms), that output is deterministic and
  * deduplicated, and that every resolved algorithm belongs to the closed
  * vocabulary.
  */
class CipherSuiteResolverSuite extends FunSuite with ScalaCheckSuite {

  test("T-A-01 ECDHE-RSA-AES128-GCM-SHA256 decomposes to its algorithms") {
    val entries = CipherSuiteResolver.resolveCipherString(
      "ECDHE-RSA-AES128-GCM-SHA256"
    )
    assertEquals(entries.size, 1)
    assertEquals(
      entries.head.name,
      "ECDHE-RSA-AES128-GCM-SHA256"
    )
    assertEquals(
      entries.head.algorithms,
      Vector("ecdh", "rsa", "aes-128-gcm", "sha-256")
    )
  }

  test("T-A-02 grammar keywords HIGH/!aNULL stay name-only (no invention)") {
    val entries = CipherSuiteResolver.resolveCipherString("HIGH:!aNULL")
    assertEquals(entries.map(_.name), Vector("HIGH", "!aNULL"))
    assert(
      entries.forall(_.algorithms.isEmpty),
      "cipher-class keywords resolve to no algorithms"
    )
  }

  test("T-A-03 TLS 1.3 suite TLS_AES_256_GCM_SHA384 resolves") {
    val algs = CipherSuiteResolver.resolveToken("TLS_AES_256_GCM_SHA384")
    assertEquals(algs, Some(Vector("aes-256-gcm", "sha-384")))
  }

  test("T-A-04 DEFAULT@SECLEVEL=2 parses without error and invents nothing") {
    val entries = CipherSuiteResolver.resolveCipherString("DEFAULT@SECLEVEL=2")
    assertEquals(entries.size, 1)
    assertEquals(entries.head.name, "DEFAULT@SECLEVEL=2")
    assertEquals(entries.head.algorithms, Vector.empty)
  }

  test("T-A-05 unknown token FOO-BAR stays name-only") {
    val algs = CipherSuiteResolver.resolveToken("FOO-BAR")
    assertEquals(algs, None)
    val entries =
      CipherSuiteResolver.resolveCipherString("FOO-BAR:ECDHE-RSA-AES256-GCM-SHA384")
    assertEquals(entries.map(_.name), Vector("FOO-BAR", "ECDHE-RSA-AES256-GCM-SHA384"))
    assertEquals(entries.head.algorithms, Vector.empty)
  }

  test("T-A-06 output is deterministic, ordered, and deduplicated") {
    val s = "ECDHE-RSA-AES128-GCM-SHA256:AES128-SHA:AES128-SHA"
    val a = CipherSuiteResolver.resolveCipherString(s)
    val b = CipherSuiteResolver.resolveCipherString(s)
    assertEquals(a, b, "repeat calls must agree")
    assertEquals(a(0).algorithms, Vector("ecdh", "rsa", "aes-128-gcm", "sha-256"))
    assertEquals(a(1).algorithms, Vector("aes-128-cbc", "sha-1"))
    // The entry for a repeated suite is kept once, per token position.
    assertEquals(a.map(_.name), Vector("ECDHE-RSA-AES128-GCM-SHA256", "AES128-SHA", "AES128-SHA"))
  }

  property("T-A-07 property: resolved algorithms are in the closed vocabulary") {
    val tokenGen = Gen.oneOf(
      "ECDHE-RSA-AES128-GCM-SHA256",
      "ECDHE-ECDSA-AES256-GCM-SHA384",
      "TLS_CHACHA20_POLY1305_SHA256",
      "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
      "HIGH",
      "!aNULL",
      "FOO-BAR",
      "DEFAULT@SECLEVEL=2"
    )
    forAll(tokenGen) { token =>
      CipherSuiteResolver.resolveToken(token) match {
        case Some(algs) =>
          algs.forall(a =>
            CipherSuiteResolver.knownAlgorithms.contains(a) &&
              a.matches("[-a-z0-9]+")
          )
        case None => true // unknown tokens may stay unresolved
      }
    }
  }
}