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

/** Phase G — Lockfile crypto inventory.
  *
  * Verifies CryptoDependency metadata for cargo/npm/go/requirements lockfiles,
  * precision (non-crypto deps are not emitted), version capture, the
  * recognized-but-unmapped flag, and the totality of the mapping table.
  */
class CryptoDependencySuite extends FunSuite {

  private val adHoc = MKC.adHoc("CryptoDependency")

  private def artifact(name: String, content: String): ByteWrapper =
    ByteWrapper(content.getBytes("UTF-8"), name, None)

  private def meta(
      name: String,
      content: String
  ): Map[String, TreeSet[StringOrPair]] = {
    val a = artifact(name, content)
    new CryptoDependencyState(a).invokeBuildMetadata(a).toMap
  }

  test("T-G-01 Cargo.lock maps ring to families; webpki is kept unmapped") {
    val m = meta(
      "Cargo.lock",
      """version = 3
        |
        |[[package]]
        |name = "ring"
        |version = "0.17.8"
        |
        |[[package]]
        |name = "webpki"
        |version = "0.22.4"
        |""".stripMargin
    )
    val names = m(adHoc("name")).toVector.map(_.value).toSet
    assertEquals(names, Set("ring", "webpki"))
    val algorithms = m(adHoc("algorithms")).toVector.map(_.value).toSet
    assert(algorithms.contains("aead"), s"ring families missing: $algorithms")
    assert(algorithms.contains("signature"), s"ring families missing: $algorithms")
    assertEquals(m(adHoc("version")).toVector.map(_.value).toSet, Set("0.17.8", "0.22.4"))
    assertEquals(m(adHoc("mapped")).head.value, "false")
    assertEquals(m(adHoc("ecosystem")).head.value, "cargo")
  }

  test("T-G-02 package-lock.json maps jsonwebtoken") {
    val m = meta(
      "package-lock.json",
      """{
        |  "name": "my-app",
        |  "version": "1.0.0",
        |  "lockfileVersion": 3,
        |  "packages": {
        |    "": { "name": "my-app", "version": "1.0.0" },
        |    "node_modules/jsonwebtoken": { "version": "9.0.2" }
        |  }
        |}
        |""".stripMargin
    )
    assertEquals(m(adHoc("name")).toVector.map(_.value).toVector, Vector("jsonwebtoken"))
    assertEquals(m(adHoc("version")).head.value, "9.0.2")
    val algorithms = m(adHoc("algorithms")).toVector.map(_.value).toSet
    assert(algorithms.contains("signature"))
    assert(algorithms.contains("mac"))
    assertEquals(m(adHoc("ecosystem")).head.value, "npm")
  }

  test("T-G-03 non-crypto dependencies are not emitted") {
    val m = meta(
      "package-lock.json",
      """{ "name": "app", "version": "1.0.0",
        |  "dependencies": { "lodash": { "version": "4.17.21" } } }
        |""".stripMargin
    )
    assert(m.isEmpty, s"no crypto dependency expected: $m")
  }

  test("T-G-04 go.sum versions are captured") {
    val m = meta(
      "go.sum",
      """golang.org/x/crypto v0.23.0 h1:xxxx=
        |golang.org/x/crypto v0.23.0/go.mod h1:yyyy=
        |github.com/golang/protobuf v1.5.0 h1:zzzz=
        |""".stripMargin
    )
    assertEquals(m(adHoc("name")).head.value, "golang.org/x/crypto")
    assertEquals(m(adHoc("version")).head.value, "v0.23.0")
    val algorithms = m(adHoc("algorithms")).toVector.map(_.value).toSet
    assert(algorithms.contains("hash"))
    assert(algorithms.contains("key-agree"))
  }

  test("T-G-05 recognized-but-unmapped crypto library is flagged mapped=false") {
    val m = meta(
      "Cargo.lock",
      """[[package]]
        |name = "webpki"
        |version = "0.22.4"
        |""".stripMargin
    )
    assertEquals(m(adHoc("name")).head.value, "webpki")
    assertEquals(m(adHoc("mapped")).head.value, "false")
    assert(!m.contains(adHoc("algorithms")), "no fabricated family for webpki")
  }

  test("T-G-06 property: mapping table is total and canonical") {
    // Every family the table can emit is in the closed enum and lowercase.
    for {
      name <- CryptoDependencyStrategy.FamilyTable.keys.toVector.sorted
      family <- CryptoDependencyStrategy.FamilyTable(name)
    } {
      assert(
        CryptoDependencyStrategy.allowedFamilies.contains(family) &&
          family.matches("[-a-z0-9]+"),
        s"family '$family' (from $name) must be canonical"
      )
    }
    // Every mapped name is a known crypto package.
    assert(
      CryptoDependencyStrategy.FamilyTable.keys.forall(
        CryptoDependencyStrategy.knownCryptoPackages.contains
      ),
      "every mapped name is a known crypto package"
    )
    // End-to-end battery: every emitted algorithm value is canonical.
    val battery = Vector(
      "Cargo.lock" -> """[[package]]
                        |name = "ring"
                        |version = "0.17.8"
                        |""".stripMargin,
      "package-lock.json" -> """{ "dependencies": { "jsonwebtoken": { "version": "9.0.2" } } }""",
      "requirements.txt" -> "cryptography==42.0.5\nwebpki==0.1.0\n"
    )
    battery.foreach { case (name, content) =>
      val m = meta(name, content)
      assert(m.nonEmpty, s"[$name] expected metadata")
      m.get(adHoc("algorithms")).foreach(_.toVector.foreach { p =>
        assert(
          CryptoDependencyStrategy.allowedFamilies.contains(p.value),
          s"[$name] algorithm '${p.value}' must be canonical"
        )
      })
    }
  }
}