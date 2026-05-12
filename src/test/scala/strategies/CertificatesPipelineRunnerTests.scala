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

package strategies

import io.bullet.borer.Dom
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.ItemTagData
import io.spicelabs.goatrodeo.util.Gitoid
import munit.FunSuite

import java.io.File
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Unit tests for [[CertificatesPipelineRunner]] — specifically the
  * [[CertificatesPipelineRunner.filterPrimaryItems]] helper.
  *
  * Traces to: `certificates-strategy/appendices.md` Appendix B (`itemCount`
  * semantics — "number of Items the pipeline must emit for this fixture"). The
  * pipeline stores three kinds of Items:
  *   - primary artifact Items (body = `ItemMetaData`)
  *   - alias-stub Items written by the back-reference pass (body = None)
  *   - tag Items (body = `ItemTagData`, only present when `withTag(...)` is set
  *     in Config; Certificates fixture tests do not set that)
  *
  * Only primary items are what a sidecar's `itemCount` counts. The filter is
  * the seam that enforces that contract.
  *
  * ## LLM-friendly summary of each test
  *
  *   - "filterPrimaryItems keeps Items with ItemMetaData body" — the positive
  *     path.
  *   - "filterPrimaryItems drops Items with body=None (alias stubs)" — the
  *     negative path this filter was introduced to handle.
  *   - "filterPrimaryItems drops Items with ItemTagData body" — defensive: if a
  *     future test harness sets a tag, the filter correctly excludes tag Items
  *     from `itemCount`.
  *   - "filterPrimaryItems preserves input ordering" — stable output for
  *     deterministic `items.head` semantics in downstream assertions.
  *   - "runGoatRodeoOnSingleFile on a simple file returns at least one primary
  *     item" — end-to-end sanity (no alias stubs leak through).
  */
class CertificatesPipelineRunnerTests extends FunSuite {

  private def primaryItem(id: String): Item = Item(
    identifier = io.spicelabs.goatrodeo.test.GitoidFixtures.gitoidFor(id),
    connections = TreeSet.empty,
    bodyMimeType = Some(ItemMetaData.mimeType),
    body = Some(
      ItemMetaData(
        fileNames = TreeSet.empty,
        mimeType = TreeSet.empty,
        fileSize = 0L,
        extra = TreeMap.empty
      )
    )
  )

  private def aliasStubItem(id: String): Item = Item(
    identifier = io.spicelabs.goatrodeo.test.GitoidFixtures.gitoidFor(id),
    connections = TreeSet.empty,
    bodyMimeType = None,
    body = None
  )

  private def tagItem(id: String): Item = Item(
    identifier = io.spicelabs.goatrodeo.test.GitoidFixtures.gitoidFor(id),
    connections = TreeSet.empty,
    bodyMimeType = Some(ItemTagData.mimeType),
    body = Some(ItemTagData(Dom.NullElem))
  )

  test("filterPrimaryItems keeps Items with ItemMetaData body") {
    val a = primaryItem("A")
    val b = primaryItem("B")
    val out = CertificatesPipelineRunner.filterPrimaryItems(Vector(a, b))
    assertEquals(out, Vector(a, b))
  }

  test("filterPrimaryItems drops Items with body=None (alias stubs)") {
    val primary = primaryItem("primary")
    val stub1 = aliasStubItem("sha1-alias")
    val stub2 = aliasStubItem("md5-alias")
    val out = CertificatesPipelineRunner.filterPrimaryItems(
      Vector(stub1, primary, stub2)
    )
    assertEquals(out, Vector(primary))
  }

  test("filterPrimaryItems drops Items with ItemTagData body") {
    val primary = primaryItem("primary")
    val tag = tagItem("tag-gitoid")
    val out = CertificatesPipelineRunner.filterPrimaryItems(
      Vector(primary, tag)
    )
    assertEquals(out, Vector(primary))
  }

  test("filterPrimaryItems preserves input ordering") {
    val a = primaryItem("A")
    val b = primaryItem("B")
    val c = primaryItem("C")
    val stub = aliasStubItem("stub")
    val out = CertificatesPipelineRunner.filterPrimaryItems(
      Vector(c, stub, a, b)
    )
    assertEquals(out, Vector(c, a, b))
  }

  test("filterPrimaryItems on empty input returns empty") {
    assertEquals(
      CertificatesPipelineRunner.filterPrimaryItems(Vector.empty),
      Vector.empty[Item]
    )
  }

  test("filterPrimaryItems on all-stubs input returns empty") {
    val out = CertificatesPipelineRunner.filterPrimaryItems(
      Vector(aliasStubItem("a"), aliasStubItem("b"))
    )
    assertEquals(out, Vector.empty[Item])
  }

  test(
    "runGoatRodeoOnSingleFile on a simple file returns exactly one primary Item"
  ) {
    // Sanity integration test: run the real pipeline on a trivial
    // artifact written to a temp file. A pre-Phase-3 pipeline uses
    // GenericFile for unclaimed files → one primary Item. The filter
    // must drop the 5-ish alias stubs the pipeline also writes for
    // the alternate-hash forms of the same bytes.
    val tmp = File.createTempFile("pipelinerunner-", ".txt")
    Files.writeString(tmp.toPath, "hello goat rodeo\n")
    try {
      val items = CertificatesPipelineRunner.runGoatRodeoOnSingleFile(tmp)
      assertEquals(
        items.size,
        1,
        s"expected 1 primary Item, got ${items.size}: ${items.map(_.identifier)}"
      )
      assert(
        items.head.body.exists(_.isInstanceOf[ItemMetaData]),
        s"expected ItemMetaData body, got ${items.head.body}"
      )
    } finally tmp.delete()
  }
}
