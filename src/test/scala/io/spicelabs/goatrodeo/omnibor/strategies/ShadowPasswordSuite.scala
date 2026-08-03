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
}
