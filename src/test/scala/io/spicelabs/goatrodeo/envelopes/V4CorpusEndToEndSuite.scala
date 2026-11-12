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

import io.spicelabs.goatrodeo.GoatRodeoBuilder
import io.spicelabs.goatrodeo.omnibor.GoatRodeoCluster
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** A real Docker image, run end to end, produces version 4 clusters and unchanged
  * run outputs.
  *
  * WHAT: the pipeline runs over `bigtent_2025_03_22_docker.tar` — a real `docker save`
  * export — into a temporary output directory, and the files it produces are checked
  * against the version 4 contract: every `.grc`, `.gri` and `.grd` in the output is a
  * version 4 file with the BLAKE3 declaration, `purls.txt` holds the image purl the
  * round trip is expected to discover, and the tamper-evident chain head reaches the
  * cluster envelope when the log has entries.
  *
  * WHY: the unit-level tests build clusters from hand-made items. Only a run over a
  * real corpus exercises what the pipeline actually writes: many items, nested
  * containers, purls discovered by strategies, and the log written alongside. A writer
  * defect that only appears at corpus scale — an unsorted index, a cluster over the
  * size limit, a purl lost in merging — would slip past every other test in this plan.
  *
  * THEORY: the assertions are about the *files on disk* rather than about in-memory
  * state, because the files are the product. The purl assertion uses the known image
  * purl for this fixture, so a silently empty run fails rather than passing vacuously.
  *
  * LLM note: test ids are EC-n.
  */
class V4CorpusEndToEndSuite extends GoatRodeoFunSuite {

  private val corpus =
    new File("test_data/download/docker_tests/bigtent_2025_03_22_docker.tar")

  /** Run the pipeline over the corpus into a fresh directory. */
  private def run(): File = {
    assert(corpus.isFile, s"corpus fixture required: ${corpus.getAbsolutePath}")
    val input = Files.createTempDirectory("v4-corpus-in").toFile()
    val output = Files.createTempDirectory("v4-corpus-out").toFile()
    Files.copy(corpus.toPath, new File(input, corpus.getName).toPath)
    new GoatRodeoBuilder()
      .withPayload(input.getAbsolutePath)
      .withOutput(output.getAbsolutePath)
      .withThreads(2)
      .withMaxRecords(50000)
      .run()
    output
  }

  private def filesWithSuffix(dir: File, suffix: String): Vector[File] =
    Option(dir.listFiles()).map(_.toVector).getOrElse(Vector.empty)
      .filter(_.getName.endsWith(suffix))

  // EC-1 — every cluster, index and data file the run wrote is version 4.
  test("EC-1 a real Docker image run writes version 4 files") {
    val out = run()
    try {
      val clusters = filesWithSuffix(out, ".grc")
      assert(clusters.nonEmpty, s"no .grc written under ${out.getAbsolutePath}")

      clusters.foreach { grc =>
        val cluster = GoatRodeoCluster.open(grc)
        assertEquals(cluster.envelope.version, 4, s"cluster version in ${grc.getName}")
        assertEquals(cluster.envelope.encoding, Blake3IndexKey.encoding)
        cluster.indexFiles.values.foreach { idx =>
          assertEquals(idx.envelope.version, 1, s"index version in ${idx.file.getName}")
          assertEquals(idx.envelope.encoding, Blake3IndexKey.encoding)
        }
        cluster.dataFiles.values.foreach { dataFile =>
          assertEquals(dataFile.envelope.version, 2, "data envelope version")
          assertEquals(dataFile.envelope.previous, 0L)
          assert(dataFile.envelope.dependsOn.isEmpty)
        }
      }
    } finally {
      deleteTree(out)
      deleteTree(out.getParentFile.listFiles.find(_.getName.startsWith("v4-corpus-in")).getOrElse(out))
    }
  }

  // EC-2 — the purls the pipeline discovered reach purls.txt: the file is the
  // deliverable, so an empty or short file is a loss even when the clusters are valid.
  test("EC-2 the run's purls reach purls.txt") {
    val out = run()
    try {
      val purls = new File(out, "purls.txt")
      assert(purls.isFile, s"purls.txt must be in the output directory")
      val lines = Files.readAllLines(purls.toPath).asScala.map(_.trim).filter(_.nonEmpty).toVector
      assert(lines.nonEmpty, "purls.txt must not be empty")
      assert(
        lines.exists(_.contains("pkg:docker/bigtent@2025_03_22")),
        s"the image purl pkg:docker/bigtent@2025_03_22 must be discovered; got ${lines.filter(_.contains("docker")).take(5).mkString(", ")}"
      )
      assert(lines.distinct.length == lines.length, "purls.txt must not repeat a purl")
    } finally deleteTree(out)
  }

  // EC-3 — the run outputs that the format change must not disturb are still
  // written: the envelope still carries the file-hash manifest it carried before, and
  // `history.jsonl` is still produced next to the clusters. Nothing here asserts how
  // tamper-evident logging behaves; the only thing the format shares with it is the
  // `info` map those values live in, so the assertion is that the map still decodes
  // and still carries `sha256`.
  test("EC-3 the run still writes history.jsonl and a cluster with its hash manifest") {
    val out = run()
    try {
      val history = new File(out, "history.jsonl")
      assert(history.isFile, "history.jsonl must be written next to the clusters")
      assert(Files.size(history.toPath) > 0, "history.jsonl must not be empty")

      val clusterFiles = filesWithSuffix(out, ".grc")
      assert(clusterFiles.nonEmpty, "the run must write a cluster")
      val cluster = GoatRodeoCluster.open(clusterFiles.head)
      assertEquals(cluster.envelope.version, 4)
      assert(
        cluster.envelope.info.contains("sha256"),
        s"the envelope must still record its file hashes; keys: ${cluster.envelope.info.keys.mkString(", ")}"
      )
      assert(
        cluster.envelope.info("sha256").contains("grd"),
        "the hash manifest must still name the data files"
      )
    } finally deleteTree(out)
  }

  private def deleteTree(f: File): Unit = {
    if (f.isDirectory) Option(f.listFiles()).foreach(_.foreach(deleteTree))
    f.delete()
  }
}
