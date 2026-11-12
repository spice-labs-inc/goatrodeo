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

/** What the writer must emit, independent of whether our reader happens to accept it.
  *
  * WHAT: the files `GraphManager.writeEntries` produces are inspected directly — the
  * envelopes are decoded and the raw index bytes are read — and three properties are
  * asserted that the format requires and no earlier test covered: index entries are
  * ascending by key, an index envelope's `data_files` is sorted and free of
  * duplicates, and a written data file declares `previous = 0` with an empty
  * `depends_on`. Boundary cases follow: no items at all, a single data file holding
  * many entries, and a duplicate identifier.
  *
  * WHY: a round trip proves the writer and reader agree; it does not prove either is
  * right. BigTent documents the ordering of index entries as a contract ("ideally
  * ascending ... so the indexes can be read in order and no sorting is required") and
  * Goat Rodeo's version 4 writer is supposed to honour it. Nothing checked. Likewise
  * the chain fields are documented as inert in version 4, and a writer that quietly
  * kept writing a back-pointer into the envelope would still round-trip.
  *
  * THEORY: three independent readings of the same written file. The envelope is
  * decoded with the project's codec; the index entries are read as raw bytes so their
  * order is observed rather than inferred from a decoded structure; and the boundary
  * cases pin the behaviour at zero and at a size large enough for the writer to write
  * one data file with many entries.
  *
  * LLM note: test ids are WC-n.
  */
class V4WriterContractSuite extends GoatRodeoFunSuite {

  private def item(id: String, targets: String*): Item =
    Item(
      id,
      TreeMap(
        EdgeType.aliasFrom -> TreeSet.from(targets.map(t => s"purl:$t"))
      ),
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

  private def ids(n: Int): Vector[String] =
    (0 until n).toVector.map(i => f"gitoid:blob:sha256:$i%064x")

  private def write(items: Vector[Item]): (java.io.File, GoatRodeoCluster) = {
    val dir = Files.createTempDirectory("v4-writer").toFile()
    val (_, clusterFile) = GraphManager.writeEntries(dir, items.iterator)
    dir -> GoatRodeoCluster.open(clusterFile)
  }

  private def indexKeys(cluster: GoatRodeoCluster): Vector[Vector[Byte]] =
    cluster.indexFiles.values.toVector.flatMap { idx =>
      val bytes = Files.readAllBytes(idx.file.toPath)
      (0 until idx.envelope.size).toVector.map { i =>
        val at = idx.dataOffset.toInt + i * 32
        java.util.Arrays.copyOfRange(bytes, at, at + 16).toVector
      }
    }

  // WC-1 — the ordering contract, in the shape the pipeline actually uses.
  //
  // `GraphManager.writeEntries` does not sort: `Builder` hands it items ordered by
  // `Storage.pathsSortedWithMD5`, whose digest is the same digest the index key uses.
  // So the file is ascending because the input is, and the writer only has to
  // preserve that order. Asserting a sorted file from an unsorted input would be
  // asserting a precondition violation, which is what an earlier version of this
  // test did.
  test("WC-1 index entries are ascending when the items arrive in key order") {
    val ordered = ids(20)
      .sortBy(id => Blake3IndexKey.keyHex(id))
    val (dir, cluster) = write(ordered.zipWithIndex.toVector.map { case (id, i) => item(id, s"pkg:npm/x$i@1") })
    try {
      val keys = indexKeys(cluster)
      assertEquals(keys.length, 20, "one index entry per item")
      assertEquals(
        keys.map(_.toArray.toVector),
        keys.map(_.toArray.toVector).sortWith { (a, b) =>
          var i = 0
          var decided: Option[Boolean] = None
          while (i < 16 && decided.isEmpty) {
            val x = a(i) & 0xff
            val y = b(i) & 0xff
            if (x != y) decided = Some(x < y)
            i += 1
          }
          decided.getOrElse(false)
        },
        "the index must be ascending by key"
      )
    } finally Helpers.deleteDirectory(dir.toPath())
  }

  // WC-1b — and the ordering comes from the store's sort using the index key, which
  // is what makes WC-1 more than a restatement: if this digest ever drifts from the
  // index key, the files stop being ascending and BigTent refuses to read them.
  test("WC-1b the store's order is the index key order") {
    val storage = MemStorage(None)
    val sample = ids(12)
    sample.foreach(id => storage.write(id, _ => Some(item(id, s"pkg:npm/s$id@1")), _ => ""))
    val fromStore = storage.pathsSortedWithMD5().map(_._2)
    assertEquals(
      fromStore,
      fromStore.sortBy(id => Blake3IndexKey.keyHex(id)),
      "pathsSortedWithMD5 must order by the same digest the index key uses"
    )
  }

  // WC-2 — the index envelope's data_files is sorted and has no duplicates, and names
  // the data file the entries point into.
  test("WC-2 index envelope data_files is sorted and deduplicated") {
    val (dir, cluster) = write(ids(5).zipWithIndex.toVector.map { case (id, i) => item(id, s"pkg:npm/y$i@1") })
    try {
      cluster.indexFiles.values.foreach { idx =>
        val files = idx.envelope.dataFiles.toVector
        assertEquals(files, files.sorted, "data_files must be ascending")
        assertEquals(files.distinct.length, files.length, "data_files must not repeat")
        assert(files.forall(cluster.dataFiles.contains), "every named data file must exist")
      }
    } finally Helpers.deleteDirectory(dir.toPath())
  }

  // WC-3 — version 4 data files declare the chain fields inert.
  test("WC-3 a written data file declares previous = 0 and an empty depends_on") {
    val (dir, cluster) = write(ids(3).zipWithIndex.toVector.map { case (id, i) => item(id, s"pkg:npm/z$i@1") })
    try {
      assert(cluster.dataFiles.nonEmpty)
      cluster.dataFiles.values.foreach { dataFile =>
        assertEquals(dataFile.envelope.version, 2)
        assertEquals(dataFile.envelope.previous, 0L, "previous is inert in version 4")
        assert(dataFile.envelope.dependsOn.isEmpty, "depends_on is inert in version 4")
      }
    } finally Helpers.deleteDirectory(dir.toPath())
  }

  // WC-4 — no items at all: a cluster is still written and still reads back, with no
  // data or index files named.
  test("WC-4 a run with no items writes a readable empty version 4 cluster") {
    val (dir, cluster) = write(Vector.empty)
    try {
      assertEquals(cluster.envelope.version, 4)
      assertEquals(cluster.envelope.encoding, Blake3IndexKey.encoding)
      assert(cluster.indexFiles.isEmpty, "no items means no index files")
      assert(cluster.dataFiles.isEmpty, "no items means no data files")
    } finally Helpers.deleteDirectory(dir.toPath())
  }

  // WC-5 — one data file with many entries: the index declares the count it wrote and
  // every entry resolves.
  test("WC-5 one data file holds many entries and every entry resolves") {
    val items = ids(500).zipWithIndex.toVector.map { case (id, i) => item(id, s"pkg:npm/many$i@1") }
    val (dir, cluster) = write(items)
    try {
      val entries = cluster.indexFiles.values.toVector.map(_.envelope.size).sum
      assertEquals(entries, items.length, "the index declares every entry it wrote")

      val read = cluster.indexFiles.values.toVector.flatMap { idx =>
        val bytes = Files.readAllBytes(idx.file.toPath)
        (0 until idx.envelope.size).toVector.map { i =>
          val at = idx.dataOffset.toInt + i * 32
          val dataFileHash = ByteBuffer.wrap(bytes, at + 16, 8).getLong
          val offset = ByteBuffer.wrap(bytes, at + 24, 8).getLong
          cluster.dataFiles(dataFileHash).readItemAt(offset)
        }
      }
      assertEquals(read.map(_.identifier).toSet, items.map(_.identifier).toSet)
    } finally Helpers.deleteDirectory(dir.toPath())
  }

  // WC-6 — the key is a function of the identifier, never of the position: a repeated
  // identifier produces entries with the *same* key rather than two keys. Dedupe is
  // the store's business — `writeEntries` writes what it is handed — so this pins the
  // property that makes a lookup by key well defined.
  test("WC-6 a repeated identifier yields the same key, not a second one") {
    val id = ids(1).head
    val (dir, cluster) = write(Vector(item(id, "pkg:npm/dup@1"), item(id, "pkg:npm/dup@1")))
    try {
      val keys = indexKeys(cluster).map(_.toArray.toVector).distinct
      assertEquals(keys.length, 1, s"one key per identifier, got ${keys.length}")
      assertEquals(keys.head, Blake3IndexKey.key(id).toVector)
    } finally Helpers.deleteDirectory(dir.toPath())
  }
}
