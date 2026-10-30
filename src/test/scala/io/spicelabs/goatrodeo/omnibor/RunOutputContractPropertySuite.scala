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

package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.GoatRodeoBuilder
import io.spicelabs.goatrodeo.testing.GoatRodeoScalaCheckSuite
import io.spicelabs.goatrodeo.testsupport.RunOutputs
import io.spicelabs.goatrodeo.testsupport.RunOutputs.Run
import org.scalacheck.Prop

import java.io.File
import java.nio.file.Files

/** The batch-boundary property, over every batch size that can split the run.
  *
  * ==What this tests==
  *
  * For each `maxRecords` in a small set, a run over the same fixtures must
  * produce batch directories whose union is the whole graph: every purl the
  * single-batch run produced must appear in the union, and every batch
  * directory must be self-describing (it holds its own `.grc`, `.grd`, `.gri`,
  * `purls.txt` and `history.jsonl`).
  *
  * ==Why a property and not another example test==
  *
  * `RunOutputContractSuite.O-8` pins one batch size (`maxRecords = 1`) as a
  * worked example. The failure mode this guards is not one specific size but
  * any size at which the writer relocates output: a change that mishandled the
  * second batch, or the boundary between an overlapped batch and its successor,
  * could pass at one size and fail at another. Enumerating the sizes is cheap
  * (each run is a second) and covers the boundaries rather than one point on
  * them.
  *
  * ==Why the sizes are exactly these==
  *
  * `1`, `2`, `3` and `5` sit below, at, and above the fixture count (three
  * packages), so they exercise a run split finer than one item per batch, a run
  * split exactly at the item count, and a run split with items to spare. Larger
  * sizes converge on the single-batch case, which `O-1` already covers.
  *
  * ==LLM summary==
  *
  * | Property | Asserts                                                                              |
  * |:---------|:-------------------------------------------------------------------------------------|
  * | P-1      | for `maxRecords` in {1,2,3,5}: the union of purls equals the single-batch purl set   |
  * | P-1      | and every batch directory holds `.grc`, `.grd`, `.gri`, `purls.txt`, `history.jsonl` |
  *
  * Shared helper: [[RunOutputs]].
  */
class RunOutputContractPropertySuite extends GoatRodeoScalaCheckSuite {

  private val fixtures: Vector[String] = Vector(
    "debwithmetadata.deb",
    "twilito-0.5.0.gem",
    "cpufreqctl-8-1-x86_64.pkg.tar.zst"
  )

  private val expectedPurls: Set[String] = Set(
    "pkg:deb/debian/not-a-real-package@1.2.3",
    "pkg:gem/twilito@0.5.0",
    "pkg:alpm/arch/cpufreqctl@8-1?arch=x86_64"
  )

  private def build(maxRecords: Int): Run = {
    val input = Files.createTempDirectory("rocp-in").toFile()
    val output = Files.createTempDirectory("rocp-out").toFile()
    val json = Files.createTempDirectory("rocp-json").toFile()
    fixtures.foreach { name =>
      val f = new File("test_data", name)
      Files.copy(f.toPath, new File(input, name).toPath)
    }
    new GoatRodeoBuilder()
      .withPayload(input.getAbsolutePath)
      .withOutput(output.getAbsolutePath)
      .withExtraArg("emitJsonDir", json.getAbsolutePath)
      .withThreads(2)
      .withMaxRecords(maxRecords)
      .run()
    RunOutputs.deleteTree(input)
    RunOutputs.Run(output, json)
  }

  /** The batch sizes the property enumerates: below, at, and above the fixture
    * count (three packages).
    *
    * These are enumerated explicitly rather than sampled from a generator. A
    * `Gen.oneOf` over four values would leave any given size unchecked in
    * roughly a third of runs at a practical sample count, and an unchecked
    * boundary is exactly the gap this property exists to close. Enumerating
    * costs four runs and guarantees every boundary is exercised.
    */
  private val batchSizes: Vector[Int] = Vector(1, 2, 3, 5)

  /** The property, instantiated at one batch size: every batch directory is
    * self-describing, the union of purls across them is the whole graph, and no
    * temporary file survives. Wrapped in [[Prop.secure]] so an assertion
    * failure is reported as a property failure with its message intact.
    *
    * @param maxRecords
    *   the batch size to exercise
    * @return
    *   the property at that batch size
    */
  private def wholeGraphAcrossBatches(maxRecords: Int): Prop = Prop.secure {
    val run = build(maxRecords)
    try {
      val batches = run.batches
      assert(
        batches.nonEmpty,
        s"maxRecords=$maxRecords produced no batch directory at all"
      )

      batches.foreach { dir =>
        assert(
          RunOutputs.filesWithSuffix(dir, ".grc").nonEmpty,
          s"maxRecords=$maxRecords: ${dir.getName} is a batch without a .grc"
        )
        // An empty batch is legitimate: a batch that gathered no records writes a
        // cluster with an empty data/index list. What must never happen is the
        // cluster naming a .grd/.gri that is not there, or the directory holding
        // a .grd/.gri the cluster does not name — either way a consumer that
        // trusts the cluster and one that lists the directory would disagree
        // about what the batch contains.
        val envelope = RunOutputs.readClusterEnvelope(
          RunOutputs.filesWithSuffix(dir, ".grc").head
        )
        val namedData = envelope.dataFiles.map(h => f"$h%016x.grd").toSet
        val namedIndex = envelope.indexFiles.map(h => f"$h%016x.gri").toSet
        val presentData =
          RunOutputs.filesWithSuffix(dir, ".grd").map(_.getName).toSet
        val presentIndex =
          RunOutputs.filesWithSuffix(dir, ".gri").map(_.getName).toSet
        assertEquals(
          namedData.diff(presentData),
          Set.empty[String],
          s"maxRecords=$maxRecords: ${dir.getName} names .grd files that are missing"
        )
        assertEquals(
          presentData.diff(namedData),
          Set.empty[String],
          s"maxRecords=$maxRecords: ${dir.getName} holds .grd files the cluster does not name"
        )
        assertEquals(
          namedIndex.diff(presentIndex),
          Set.empty[String],
          s"maxRecords=$maxRecords: ${dir.getName} names .gri files that are missing"
        )
        assertEquals(
          presentIndex.diff(namedIndex),
          Set.empty[String],
          s"maxRecords=$maxRecords: ${dir.getName} holds .gri files the cluster does not name"
        )
        assert(
          new File(dir, "purls.txt").isFile,
          s"maxRecords=$maxRecords: ${dir.getName} has a .grc but no purls.txt"
        )
        assert(
          new File(dir, "history.jsonl").isFile,
          s"maxRecords=$maxRecords: ${dir.getName} has a .grc but no history.jsonl"
        )
      }

      // the union across batches is the whole graph: no purl may be lost
      // wherever the writer chose to relocate a batch
      assertEquals(
        run.purls,
        expectedPurls,
        s"maxRecords=$maxRecords: the union of purls.txt across ${batches.length} batch(es)"
      )

      // nothing temporary survived, at any batch size
      batches.foreach { dir =>
        val leftovers = Option(dir.listFiles())
          .getOrElse(Array.empty[File])
          .filter(f =>
            RunOutputs.temporaryNamePrefixes.exists(p =>
              f.getName.startsWith(p)
            )
          )
        assert(
          leftovers.isEmpty,
          s"maxRecords=$maxRecords: ${dir.getName} holds temporary file(s) ${leftovers.map(_.getName).toVector}"
        )
      }
    } finally run.cleanup()
    Prop.proved
  }

  test(
    "P-1 every batch size yields self-describing batches whose union is whole"
  ) {
    Prop.all(batchSizes.map(wholeGraphAcrossBatches)*)
  }
}
