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
import munit.FunSuite

import java.io.File
import java.nio.file.Files

/** Tests for the batch wind-down overlap in [[Builder]].
  *
  * The next batch's workers are kicked off once the current batch is down to
  * 10% of its allocated threads, and never while more than two batches are
  * alive. THEORY: the batch tail is where the largest artifacts (single
  * workers building multi-million-vertex ADGs) gate the whole batch, so
  * overlapping the next batch's processing with that tail reclaims the idle
  * time without increasing the number of live storages.
  */
class BuilderBatchOverlapSuite extends FunSuite {

  private def cleanup(dir: File): Unit = {
    if (dir != null && dir.exists()) {
      Files
        .walk(dir.toPath())
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
      ()
    }
  }

  private def writeTinyFiles(dir: File, count: Int): Unit = {
    for (i <- 0 until count) {
      Files.writeString(new File(dir, f"f$i%05d.txt").toPath, s"hello $i\n")
    }
  }

  private def runBuild(dir: File, out: File, ingested: File, threads: Int): Unit = {
    new GoatRodeoBuilder()
      .withPayload(dir.getAbsolutePath)
      .withOutput(out.getAbsolutePath)
      .withThreads(threads)
      .withMaxRecords(100)
      .withIngested(ingested.getAbsolutePath)
      .run()
  }

  /** Count ADG cluster files written across the run's batch directories. Batch
    * dirs are siblings of `out` (named `<out>_<n>`), not children.
    */
  private def countBatchClusters(out: File): Long = {
    import scala.jdk.CollectionConverters.*
    val parent = Option(out.getParentFile()).getOrElse(out)
    val batchDirs = Option(parent.listFiles())
      .map(_.toVector)
      .getOrElse(Vector())
      .filter(_.isDirectory)
      .filter(_.getName.startsWith(out.getName + "_"))
    batchDirs
      .flatMap { d =>
        Files
          .walk(d.toPath())
          .iterator()
          .asScala
          .filter(_.toString.endsWith(".grc"))
          .toVector
      }
      .size
      .toLong
  }

  // T-BO-01 — the wind-down threshold is 10% of the allocated threads (integer
  // division), and 0 below 10 threads (so a batch is never overlapped when
  // fewer than 10 threads are allocated).
  test("T-BO-01 windDownThreshold is 10% of threads, zero below 10") {
    assertEquals(Builder.windDownThreshold(50), 5)
    assertEquals(Builder.windDownThreshold(100), 10)
    assertEquals(Builder.windDownThreshold(10), 1)
    assertEquals(Builder.windDownThreshold(9), 0)
    assertEquals(Builder.windDownThreshold(4), 0)
  }

  // T-BO-02 — a multi-batch run with >= 10 threads (overlap enabled) still
  // processes every top-level file exactly once and writes one ADG cluster per
  // batch.
  test("T-BO-02 overlap-enabled multi-batch run processes every file") {
    val in = Files.createTempDirectory("bo-in").toFile()
    val out = Files.createTempDirectory("bo-out").toFile()
    val ingested = new File(out, "ingested.txt")
    try {
      writeTinyFiles(in, 250)
      runBuild(in, out, ingested, threads = 10)
      assertEquals(
        Files.readAllLines(ingested.toPath()).size(),
        250,
        "every top-level file must be processed exactly once"
      )
      val grcCount = countBatchClusters(out)
      assert(grcCount >= 2L, s"expected more than one batch cluster, got $grcCount")
    } finally {
      cleanup(in)
      cleanup(out)
      Option(out.getParentFile()).foreach { p =>
        Option(p.listFiles()).foreach(_.foreach { f =>
          if (f.getName.startsWith(out.getName + "_")) cleanup(f)
        })
      }
    }
  }

  // T-BO-03 — with fewer than 10 threads the wind-down overlap is disabled
  // (batches are strictly sequential), and the run still processes every file.
  test("T-BO-03 below-10-threads multi-batch run processes every file") {
    val in = Files.createTempDirectory("bo-in").toFile()
    val out = Files.createTempDirectory("bo-out").toFile()
    val ingested = new File(out, "ingested.txt")
    try {
      writeTinyFiles(in, 250)
      runBuild(in, out, ingested, threads = 9)
      assertEquals(
        Files.readAllLines(ingested.toPath()).size(),
        250,
        "every top-level file must be processed exactly once"
      )
      val grcCount = countBatchClusters(out)
      assert(grcCount >= 2L, s"expected more than one batch cluster, got $grcCount")
    } finally {
      cleanup(in)
      cleanup(out)
      Option(out.getParentFile()).foreach { p =>
        Option(p.listFiles()).foreach(_.foreach { f =>
          if (f.getName.startsWith(out.getName + "_")) cleanup(f)
        })
      }
    }
  }
}