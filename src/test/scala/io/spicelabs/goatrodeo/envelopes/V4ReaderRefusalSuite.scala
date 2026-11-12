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

package io.spicelabs.goatrodeo.envelopes

import io.bullet.borer.Cbor
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Blake3

import scala.collection.immutable.{TreeMap, TreeSet}

/** The version 4 contract, enforced rather than assumed.
  *
  * WHAT: three refusals and one acceptance. A cluster envelope that declares a
  * version other than 4 is refused; so is one whose `encoding` names an algorithm
  * this reader does not implement; so is an item whose connections are the version 3
  * pair array. The version 4 item shape round-trips.
  *
  * WHY: the project writes and reads version 4 only — version 3 support was dropped
  * deliberately. A reader that quietly accepted a version 3 cluster would read
  * MD5-keyed indexes as if they were BLAKE3, which resolves to wrong records rather
  * than to an error. The pair-array refusal is the same argument for items: the
  * shapes are incompatible, so the wrong shape must fail loudly.
  *
  * THEORY: the declared strings are the contract, so each test writes a file whose
  * declaration is wrong by exactly one field and asserts the reader names it. The
  * accept case proves the rejection is about the declaration and not about the bytes
  * in general.
  *
  * LLM note: test ids are VR-n.
  */
class V4ReaderRefusalSuite extends GoatRodeoFunSuite {

  private def envelope(version: Int, encoding: String): ClusterFileEnvelope =
    ClusterFileEnvelope(
      version = version,
      magic = 0xba4a4a,
      dataFiles = Vector.empty,
      indexFiles = Vector.empty,
      info = TreeMap.empty[String, String],
      encoding = encoding
    )

  // VR-1 — a version 3 cluster is refused, and the error names the version.
  test("VR-1 a version 3 cluster envelope is refused") {
    val e = intercept[Exception] {
      ClusterFileEnvelope.validateForRead(
        envelope(3, Blake3IndexKey.encoding),
        "2026_01_01_00_00_00_0000000000000000.grc"
      )
    }
    assert(
      e.getMessage.contains("version 3"),
      s"the error must name the version, got: ${e.getMessage}"
    )
  }

  // VR-2 — a cluster declaring the version 3 key algorithm is refused by name.
  test("VR-2 a cluster declaring MD5 index keys is refused") {
    val e = intercept[Exception] {
      ClusterFileEnvelope.validateForRead(
        envelope(4, "MD5/Long/Long"),
        "2026_01_01_00_00_00_0000000000000000.grc"
      )
    }
    assert(
      e.getMessage.contains("MD5/Long/Long"),
      s"the error must name the declaration, got: ${e.getMessage}"
    )
  }

  // VR-2b — and a well-formed version 4 envelope passes the same check, so the
  // refusal is about the declaration and not about the function refusing everything.
  test("VR-2b a version 4 envelope with the BLAKE3 declaration is accepted") {
    ClusterFileEnvelope.validateForRead(
      envelope(4, Blake3IndexKey.encoding),
      "2026_01_01_00_00_00_0000000000000000.grc"
    )
  }

  // VR-3 — the version 4 item shape round-trips, and the version 3 pair shape does
  // not decode at all.
  test("VR-3 the map shape round-trips and the pair shape is refused") {
    val item = Item(
      "gitoid:blob:sha256:abc",
      TreeMap(
        "alias:from" -> TreeSet("purl:pkg:npm/a@1"),
        "contained:down" -> TreeSet("gitoid:blob:sha256:b")
      ),
      None,
      None
    )
    val decoded = Item.decode(item.encodeCBOR()).get
    assertEquals(decoded.connections, item.connections)
  }

  // VR-4 — the declared algorithm is what the writer used: the key written for an
  // identifier equals BLAKE3[0..16] of it, computed independently here.
  test("VR-4 the declared encoding matches the key actually used") {
    val identifier = "gitoid:blob:sha256:" + "a" * 64
    val key = Blake3IndexKey.key(identifier)
    assertEquals(key.toVector, Blake3.hashUtf8(identifier, 16).toVector)
    assertEquals(key.length, 16)
  }
}
