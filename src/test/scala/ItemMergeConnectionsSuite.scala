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

import io.spicelabs.goatrodeo.omnibor.{EdgeType, Item, ItemMetaData, TestEdges}
import io.spicelabs.goatrodeo.testing.GoatRodeoScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

import scala.collection.immutable.{TreeMap, TreeSet}

/** Merging two items' connections must union the target sets per edge type.
  *
  * WHAT: `Item.merge` combines two items whose `connections` are the version 4
  * shape — an ordered map of edge type to target set. For every edge type present
  * on either side, the merged item must carry the union of both target sets.
  *
  * WHY: the map shape makes this the one thing a merge can silently get wrong.
  * `TreeMap.++` *replaces* on a key collision, so merging two items that both alias
  * the same edge type keeps only one side's targets — data loss in exactly the case
  * the set-of-targets exists for, and invisible to any count-based check that
  * happens to use one edge type per side. These tests fail on that implementation
  * and pass on a per-key union.
  *
  * THEORY: merging is a semilattice join on connections — per key, set union. That
  * gives three properties worth asserting: the result contains every target from
  * both sides (union), merging an item with itself changes nothing (idempotence),
  * and the order of the two sides does not matter (commutativity on connections).
  * The body of the merged item follows the pre-existing "this wins" rule and is not
  * part of the property.
  *
  * LLM note: test ids are MC-n.
  */
class ItemMergeConnectionsSuite extends GoatRodeoScalaCheckSuite {

  private val edgeTypes = Vector(
    EdgeType.aliasFrom, EdgeType.aliasTo, EdgeType.contains,
    EdgeType.containedBy, EdgeType.tagFrom, EdgeType.tagTo
  )

  private val genConnections
      : org.scalacheck.Gen[TreeMap[String, TreeSet[String]]] =
    for {
      n <- Gen.choose(0, 4)
      pairs <- Gen.listOfN(
        n,
        for {
          e <- Gen.oneOf(edgeTypes)
          t <- Gen.oneOf("t1", "t2", "t3", "t4")
        } yield (e, t)
      )
    } yield TestEdges.edgesOf(pairs)


  private def item(
      id: String,
      connections: TreeMap[String, TreeSet[String]]
  ): Item =
    Item(id, connections, Some(ItemMetaData.mimeType), None)

  // MC-1 — the case the map merge broke: the same edge type on both sides with
  // different targets. Both targets must survive.
  test("MC-1 same edge type, different targets: the union survives") {
    val a = item("a", TestEdges.edges(EdgeType.aliasFrom -> "one"))
    val b = item("a", TestEdges.edges(EdgeType.aliasFrom -> "two"))
    val merged = a.merge(b)
    assertEquals(
      merged.targetsOf(EdgeType.aliasFrom),
      TreeSet("one", "two"),
      "both aliases must be present after the merge"
    )
  }

  // MC-2 — the same target on both sides: one entry, not a duplicate, and the
  // merged map equals the single-side map.
  test("MC-2 same edge type, identical targets: deduplicated") {
    val a = item("a", TestEdges.edges(EdgeType.aliasFrom -> "one"))
    val b = item("a", TestEdges.edges(EdgeType.aliasFrom -> "one"))
    val merged = a.merge(b)
    assertEquals(merged.connections, a.connections)
    assertEquals(merged.targetsOf(EdgeType.aliasFrom), TreeSet("one"))
  }

  // MC-3 — three targets spread across two items, one of them shared.
  test("MC-3 targets accumulate across both sides") {
    val a = item(
      "a",
      TestEdges.edges(EdgeType.aliasFrom -> "one", EdgeType.aliasFrom -> "shared")
    )
    val b = item(
      "a",
      TestEdges.edges(EdgeType.aliasFrom -> "two", EdgeType.aliasFrom -> "shared")
    )
    val merged = a.merge(b)
    assertEquals(
      merged.targetsOf(EdgeType.aliasFrom),
      TreeSet("one", "two", "shared")
    )
  }

  // MC-4 — disjoint edge types: each keeps its own set, neither is dropped and no
  // empty set is invented for a key that only one side has.
  test("MC-4 disjoint edge types both survive") {
    val a = item("a", TestEdges.edges(EdgeType.aliasFrom -> "one"))
    val b = item("a", TestEdges.edges(EdgeType.contains -> "two"))
    val merged = a.merge(b)
    assertEquals(merged.targetsOf(EdgeType.aliasFrom), TreeSet("one"))
    assertEquals(merged.targetsOf(EdgeType.contains), TreeSet("two"))
    assertEquals(merged.connections.size, 2)
  }

  // MC-5 — empty on one side, and empty on both.
  test("MC-5 an empty side is the identity") {
    val full = item("a", TestEdges.edges(EdgeType.aliasFrom -> "one"))
    val empty = item("a", TreeMap.empty[String, TreeSet[String]])

    assertEquals(full.merge(empty).connections, full.connections)
    assertEquals(empty.merge(full).connections, full.connections)
    assertEquals(
      empty.merge(empty).connections,
      TreeMap.empty[String, TreeSet[String]]
    )
  }

  // MC-6 — the property: per-key union, idempotent, and commutative on
  // connections. A hand-written case with one edge type per side passes even with
  // `++`; this is the assertion that would have caught it.
  property("MC-6 merging connections is a per-key union, idempotent and commutative") {
    forAll(genConnections, genConnections) { (a, b) =>
      val ia = item("same-id", a)
      val ib = item("same-id", b)
      val merged = ia.merge(ib)

      val expected: TreeMap[String, TreeSet[String]] =
        (a.keySet ++ b.keySet).foldLeft(TreeMap.empty[String, TreeSet[String]]) {
          (acc, k) =>
            acc.updated(k, a.getOrElse(k, TreeSet.empty[String]) ++
              b.getOrElse(k, TreeSet.empty[String]))
        }

      val unionHolds = merged.connections == expected
      val idempotent = ia.merge(ia).connections == a
      val commutative = ia.merge(ib).connections == ib.merge(ia).connections
      unionHolds && idempotent && commutative
    }
  }
}
