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

import munit.FunSuite

class ShadowPasswordSuite extends FunSuite {

  test("hashAlgorithm maps crypt prefixes to families") {
    assertEquals(ShadowPasswordStrategy.hashAlgorithm("$1$salt$hash"), "md5")
    assertEquals(
      ShadowPasswordStrategy.hashAlgorithm("$2a$10$salthash"),
      "bcrypt"
    )
    assertEquals(ShadowPasswordStrategy.hashAlgorithm("$5$salt$hash"), "sha256")
    assertEquals(ShadowPasswordStrategy.hashAlgorithm("$6$salt$hash"), "sha512")
    assertEquals(
      ShadowPasswordStrategy.hashAlgorithm("$y$j9s$salt$hash"),
      "yescrypt"
    )
    assertEquals(ShadowPasswordStrategy.hashAlgorithm("$7$10$hash"), "scrypt")
    assertEquals(ShadowPasswordStrategy.hashAlgorithm("*"), "locked")
    assertEquals(ShadowPasswordStrategy.hashAlgorithm("!"), "locked")
    assertEquals(ShadowPasswordStrategy.hashAlgorithm("x"), "locked")
  }

  test("hashDetails extracts bcrypt cost and salt") {
    val d = ShadowPasswordStrategy.hashDetails("$2a$10$saltbase64encodedhash")
    assertEquals(d.algorithm, "bcrypt")
    assertEquals(d.cost, Some("10"))
    assertEquals(d.salt, Some("saltbase64encodedhash"))
  }

  test("hashDetails extracts md5 salt") {
    val d = ShadowPasswordStrategy.hashDetails("$1$salt$hash")
    assertEquals(d.algorithm, "md5")
    assertEquals(d.salt, Some("salt"))
    assertEquals(d.cost, None)
  }

  test("hashDetails extracts yescrypt params and salt") {
    val d = ShadowPasswordStrategy.hashDetails("$y$j9s$salt$hash")
    assertEquals(d.algorithm, "yescrypt")
    assertEquals(d.params, Some("j9s"))
    assertEquals(d.salt, Some("salt"))
  }

  test("hashDetails extracts scrypt params") {
    val d = ShadowPasswordStrategy.hashDetails("$7$10$hashwithsalt")
    assertEquals(d.algorithm, "scrypt")
    assertEquals(d.params, Some("10"))
  }

  // Phase H — new hash envelopes (R5).
  //
  // S-T-01: argon2id — params = the `m=…,t=…,p=…` field; the `v=19` version
  // field is parsed but never emitted; salt is the penultimate field.
  test("S-T-01 hashDetails extracts argon2id params and salt") {
    val d = ShadowPasswordStrategy.hashDetails(
      "$argon2id$v=19$m=65536,t=3,p=4$Jh7M.9rR$qJ9pRfF7"
    )
    assertEquals(d.algorithm, "argon2id")
    assertEquals(d.params, Some("m=65536,t=3,p=4"))
    assertEquals(d.salt, Some("Jh7M.9rR"))
    assertEquals(d.cost, None)
  }

  // S-T-02: NT hash — `$3$$<hex>` has an empty second field and no salt;
  // all extracted fields must be None and the hash value itself never
  // appears in the details.
  test("S-T-02 hashDetails parses NT hashes with no salt and no crash") {
    val d =
      ShadowPasswordStrategy.hashDetails("$3$$8846f7eaee8fb117ad06bdd830b7586c")
    assertEquals(d.algorithm, "nt-hash")
    assertEquals(d.salt, None)
    assertEquals(d.cost, None)
    assertEquals(d.params, None)
    // Empty/truncated NT hashes still classify, never crash.
    assertEquals(ShadowPasswordStrategy.hashAlgorithm("$3$$"), "nt-hash")
    assertEquals(ShadowPasswordStrategy.hashAlgorithm("$3$"), "nt-hash")
  }

  // S-T-03: Apache md5-crypt — salt is the field after the id.
  test("S-T-03 hashDetails extracts apr1 salt") {
    val d = ShadowPasswordStrategy.hashDetails("$apr1$Jh7M.9rR$qJ9pRfF7N3E0iG")
    assertEquals(d.algorithm, "apr1")
    assertEquals(d.salt, Some("Jh7M.9rR"))
    assertEquals(d.cost, None)
    assertEquals(d.params, None)
    // Empty salt tolerated.
    val empty = ShadowPasswordStrategy.hashDetails("$apr1$$hashvalue")
    assertEquals(empty.algorithm, "apr1")
    assertEquals(empty.salt, None)
  }

  // S-T-04: negative pinning — argon2i/argon2d stay "other" (deferred);
  // locked sentinels unchanged.
  test(
    "S-T-04 unknown argon2 variants stay other; locked sentinels unchanged"
  ) {
    assertEquals(
      ShadowPasswordStrategy.hashAlgorithm(
        "$argon2i$v=19$m=4096,t=3,p=1$salt$hash"
      ),
      "other"
    )
    assertEquals(
      ShadowPasswordStrategy.hashAlgorithm(
        "$argon2d$v=19$m=4096,t=3,p=1$salt$hash"
      ),
      "other"
    )
    Vector("*", "!", "!!", "x", "").foreach { sentinel =>
      assertEquals(ShadowPasswordStrategy.hashAlgorithm(sentinel), "locked")
    }
  }

  // S-T-05: property over the prefix table — every supported prefix yields a
  // registry-known algorithm name; control values `locked`/`other` excluded.
  test("S-T-05 property: supported prefixes yield registry-known names") {
    import io.spicelabs.goatrodeo.omnibor.CryptoAlgorithms
    val supported = Vector(
      "$1$salt$hash",
      "$2a$10$salt",
      "$2b$10$salt",
      "$2y$10$salt",
      "$3$$hash",
      "$5$salt$hash",
      "$6$salt$hash",
      "$7$10$hash",
      "$y$j9s$salt$hash",
      "$argon2id$v=19$m=65536,t=3,p=4$salt$hash",
      "$apr1$salt$hash"
    )
    supported.foreach { h =>
      val alg = ShadowPasswordStrategy.hashAlgorithm(h)
      assert(
        alg != "locked" && alg != "other",
        s"[$h] must be a recognized family, got '$alg'"
      )
      assert(
        CryptoAlgorithms.canonicalVocabulary.contains(alg),
        s"[$h] algorithm '$alg' must be in the registry vocabulary"
      )
    }
  }
}
