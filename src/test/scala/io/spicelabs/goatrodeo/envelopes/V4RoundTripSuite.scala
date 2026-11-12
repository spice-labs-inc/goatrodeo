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

import io.spicelabs.goatrodeo.omnibor.*
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.Helpers

import java.nio.ByteBuffer
import java.nio.file.Files
import scala.collection.immutable.{TreeMap, TreeSet}

/** What the writer emits, the reader must read back, and the items must survive.
  *
  * WHAT: a cluster is written with `GraphManager.writeEntries`, opened with
  * `GoatRodeoCluster.open` — the path that resolves the cluster file, its index files
  * and its data files by name — and every item reached through an index entry is
  * compared with the item that was written, connections included.
  *
  * WHY: writer-only and reader-only tests both pass while the two disagree about a
  * byte, and they did: the writer wrote a two-byte envelope length and this reader
  * read four, so every cluster was misparsed by two bytes. Only a round trip through
  * both sides catches that, and comparing items rather than counts is what catches a
  * key or an envelope that is self-consistent but wrong.
  *
  * THEORY: the fixture has three items, several edge types and multiple targets per
  * type, so an ordering, merge or key mistake changes the compared value instead of
  * the count. The index is walked the way a reader walks it: the entry's own
  * data-file hash and offset, then `readItemAt`.
  *
  * LLM note: test ids are RT-n.
  */
class V4RoundTripSuite extends GoatRodeoFunSuite {

  private def item(id: String, edges: (String, String)*): Item =
    Item(
      id,
      TestEdges.edges(edges*),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          TreeSet(s"file-$id"),
          TreeSet("application/octet-stream"),
          1L,
          TreeMap.empty[String, TreeSet[StringOrPair]]
        )
      )
    )

  private val idA = "gitoid:blob:sha256:" + "a" * 64
  private val idB = "gitoid:blob:sha256:" + "b" * 64
  private val idC = "gitoid:blob:sha256:" + "c" * 64

  private val written = Vector(
    item(idA, EdgeType.aliasFrom -> "purl:pkg:npm/a@1"),
    item(
      idB,
      EdgeType.aliasFrom -> "purl:pkg:npm/b@1",
      EdgeType.aliasTo -> "purl:pkg:npm/b@1",
      EdgeType.contains -> idC
    ),
    item(idC)
  )

  /** Every item the cluster's indexes point at. */
  private def itemsViaIndex(cluster: GoatRodeoCluster): Vector[Item] =
    cluster.indexFiles.values.toVector.flatMap { idx =>
      val bytes = Files.readAllBytes(idx.file.toPath)
      (0 until idx.envelope.size).toVector.map { i =>
        val at = idx.dataOffset.toInt + i * 32
        val dataFileHash = ByteBuffer.wrap(bytes, at + 16, 8).getLong
        val offset = ByteBuffer.wrap(bytes, at + 24, 8).getLong
        cluster.dataFiles(dataFileHash).readItemAt(offset)
      }
    }

  // RT-1 — write, read back through the cluster reader, compare items.
  test("RT-1 a written cluster round-trips through the cluster reader") {
    val dir = Files.createTempDirectory("v4-roundtrip").toFile()
    try {
      val (_, clusterFile) = GraphManager.writeEntries(dir, written.iterator)
      val cluster = GoatRodeoCluster.open(clusterFile)

      assertEquals(cluster.envelope.version, 4)
      assertEquals(cluster.envelope.encoding, Blake3IndexKey.encoding)
      assert(cluster.indexFiles.nonEmpty, "the cluster names its index files")
      cluster.indexFiles.values.foreach { idx =>
        assertEquals(idx.envelope.version, 1)
        assertEquals(idx.envelope.encoding, Blake3IndexKey.encoding)
      }

      val read = itemsViaIndex(cluster)
      assertEquals(
        read.map(_.identifier).toSet,
        written.map(_.identifier).toSet,
        "every written item must be reachable through an index entry"
      )
      read.foreach { got =>
        val expected = written.find(_.identifier == got.identifier).get
        assertEquals(got.connections, expected.connections, s"connections for ${got.identifier}")
      }
    } finally Helpers.deleteDirectory(dir.toPath())
  }

  // RT-2 — the index entry for an identifier is keyed by BLAKE3[0..16] of it, so a
  // lookup resolves to the item that produced the key.
  test("RT-2 an index entry is keyed by the identifier's BLAKE3 key") {
    val dir = Files.createTempDirectory("v4-roundtrip-key").toFile()
    try {
      val (_, clusterFile) = GraphManager.writeEntries(dir, written.iterator)
      val cluster = GoatRodeoCluster.open(clusterFile)
      val keys = cluster.indexFiles.values.toVector.flatMap { idx =>
        val bytes = Files.readAllBytes(idx.file.toPath)
        (0 until idx.envelope.size).toVector.map { i =>
          val at = idx.dataOffset.toInt + i * 32
          java.util.Arrays.copyOfRange(bytes, at, at + 16).toVector
        }
      }.toSet

      written.foreach { w =>
        assert(
          keys.contains(Blake3IndexKey.key(w.identifier).toVector),
          s"the index must hold BLAKE3[0..16] of ${w.identifier}"
        )
      }
    } finally Helpers.deleteDirectory(dir.toPath())
  }
}
