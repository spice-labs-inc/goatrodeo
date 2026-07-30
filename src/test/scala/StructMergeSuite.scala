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

import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import munit.FunSuite

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Phase 0 (0.11) — ItemMetaData.merge handles empty fileNames gracefully.
  *
  * ## What this tests
  *
  * `ItemMetaData.merge` combines two metadata instances. The merge logic for
  * `fileNames` has three branches based on the sizes of the left and right
  * fileNames sets. These tests verify that empty fileNames on either or both
  * sides do not cause exceptions (e.g., from `headOption` on empty TreeSet or
  * from the gitoid-qualified filename mapping).
  *
  * ## Why this matters
  *
  * In production, an ItemMetaData may have empty fileNames if the artifact was
  * recorded without a filename (e.g., a raw gitoid-only entry). The merge must
  * not crash when combining such entries.
  *
  * ## Requirement trace
  *
  * Phase 0 item 0.11: ItemMetaData.merge handles empty fileNames on left side,
  * right side, or both without exception.
  *
  * ## LLM-friendly summary
  *
  * | Test           | Left fileNames | Right fileNames | Expected                   |
  * |:---------------|:---------------|:----------------|:---------------------------|
  * | empty left     | TreeSet()      | TreeSet("b")    | merged OK                  |
  * | empty right    | TreeSet("a")   | TreeSet()       | merged OK                  |
  * | both non-empty | TreeSet("a")   | TreeSet("b")    | merged with both filenames |
  * | both empty     | TreeSet()      | TreeSet()       | merged OK, fileNames empty |
  */
class StructMergeSuite extends FunSuite {

  test("ItemMetaData - merge handles empty fileNames on left side") {

    /** What: Merges an ItemMetaData with empty fileNames (left) into one with a
      * non-empty fileNames (right). Why: Empty fileNames on the left should not
      * cause headOption to produce unexpected results or crash. The merge
      * should produce a result containing the right side's filenames.
      * Requirement: Phase 0 §0.11 — merge tolerates empty left fileNames.
      */
    val left = ItemMetaData(
      fileNames = TreeSet(),
      mimeType = TreeSet("text/plain"),
      fileSize = 10,
      extra = TreeMap()
    )
    val right = ItemMetaData(
      fileNames = TreeSet("bar.txt"),
      mimeType = TreeSet(),
      fileSize = 20,
      extra = TreeMap()
    )

    val merged = left.merge(right)

    assert(
      merged.fileNames.nonEmpty,
      "Merged fileNames should contain right-side entry"
    )
    assert(
      merged.fileNames.exists(_.contains("bar.txt")),
      "Merged fileNames should include 'bar.txt'"
    )
  }

  test("ItemMetaData - merge handles empty fileNames on right side") {

    /** What: Merges an ItemMetaData with non-empty fileNames (left) into one
      * with empty fileNames (right). Why: Empty fileNames on the right should
      * not cause headOption to produce unexpected results. The merge result
      * should contain the left side's filenames. Requirement: Phase 0 §0.11 —
      * merge tolerates empty right fileNames.
      */
    val left = ItemMetaData(
      fileNames = TreeSet("foo.txt"),
      mimeType = TreeSet("text/plain"),
      fileSize = 10,
      extra = TreeMap()
    )
    val right = ItemMetaData(
      fileNames = TreeSet(),
      mimeType = TreeSet(),
      fileSize = 20,
      extra = TreeMap()
    )

    val merged = left.merge(right)

    assert(
      merged.fileNames.nonEmpty,
      "Merged fileNames should contain left-side entry"
    )
    assert(
      merged.fileNames.exists(_.contains("foo.txt")),
      "Merged fileNames should include 'foo.txt'"
    )
  }

  test("ItemMetaData - merge with both sides non-empty works") {

    /** What: Merges two ItemMetaData instances both with non-empty fileNames.
      * Why: When both sides have a single filename and they differ, the merge
      * should preserve both filenames. Requirement: Phase 0 §0.11 — merge
      * correctly handles both non-empty.
      */
    val left = ItemMetaData(
      fileNames = TreeSet("foo.txt"),
      mimeType = TreeSet("text/plain"),
      fileSize = 10,
      extra = TreeMap("key1" -> TreeSet(StringOrPair("val1")))
    )
    val right = ItemMetaData(
      fileNames = TreeSet("bar.txt"),
      mimeType = TreeSet("application/json"),
      fileSize = 20,
      extra = TreeMap("key2" -> TreeSet(StringOrPair("val2")))
    )

    val merged = left.merge(right)

    assert(
      merged.fileNames.size >= 2,
      s"When both sides have different filenames, merged should preserve both; got ${merged.fileNames}"
    )
    assert(
      merged.mimeType.contains("text/plain"),
      "Merged mimeTypes should include left side"
    )
    assert(
      merged.mimeType.contains("application/json"),
      "Merged mimeTypes should include right side"
    )
    assert(
      merged.extra.contains("key1"),
      "Merged extra should include left key"
    )
    assert(
      merged.extra.contains("key2"),
      "Merged extra should include right key"
    )
  }

  test("ItemMetaData - merge with both sides empty works") {

    /** What: Merges two ItemMetaData instances both with empty fileNames. Why:
      * Both sides empty should produce empty fileNames in the result without
      * any exception from headOption/getOrElse logic. Requirement: Phase 0
      * §0.11 — merge tolerates both sides empty.
      */
    val left = ItemMetaData(
      fileNames = TreeSet(),
      mimeType = TreeSet("text/plain"),
      fileSize = 10,
      extra = TreeMap()
    )
    val right = ItemMetaData(
      fileNames = TreeSet(),
      mimeType = TreeSet("application/json"),
      fileSize = 20,
      extra = TreeMap()
    )

    val merged = left.merge(right)

    assertEquals(
      merged.fileNames,
      TreeSet.empty[String],
      "Both sides empty should produce empty fileNames"
    )
    assert(
      merged.mimeType.contains("text/plain"),
      "Merged mimeTypes should include left side"
    )
    assert(
      merged.mimeType.contains("application/json"),
      "Merged mimeTypes should include right side"
    )
  }
}
