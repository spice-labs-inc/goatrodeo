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

package io.spicelabs.goatrodeo.util
import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** Tests for the adaptive MIME precompute pass [[AdaptiveMimeBuilder]].
  *
  * WHAT: the pass forces `ArtifactWrapper.mimeType` on every artifact in a
  * corpus using a bounded set of long-lived virtual-thread workers whose count
  * is driven by [[AdaptiveParallelism]].
  *
  * WHY: the previous MIME pass (a) spawned one virtual thread per artifact —
  * wasteful at 40M+ files — and (b) died after ~33.5h because a single file
  * failure escaped the pass as an exception and killed the whole build. These
  * tests pin the two properties that fix that outcome: workers are reused
  * (bounded) and nothing can escape the pass (see the file-deleted,
  * uniform-treatment, and `MimePipelineRulesSuite` tests).
  *
  * THEORY: the worker count adapts on measured per-file throughput, with the
  * policy transitions themselves pinned deterministically in
  * `AdaptiveParallelismSuite`. The worker body is just `a.mimeType` — there is
  * no injected computation seam — so the tests run real wrappers and real Tika
  * detection. Worker identity is observed through the progress callback, which
  * fires on the resolving worker thread.
  *
  * `ArtifactWrapper.mimeType` guards its own I/O internally (returns
  * `application/octet-stream` on trouble), so no test can make a real wrapper
  * throw; the never-raise property is therefore pinned by behavior on real
  * wrappers plus the source scans.
  */
class AdaptiveMimeBuilderSuite extends GoatRodeoFunSuite {

  private val logger = Logger(getClass.getName)

  private def bytes(i: Int): Array[Byte] =
    s"content-$i".getBytes("UTF-8")

  private def bw(i: Int): ByteWrapper =
    ByteWrapper(bytes(i), s"file-$i.bin", None)

  private def tempDir(): File = Files.createTempDirectory("mime-test").toFile()

  // a file deleted after the walk completes the pass without a
  // failure: `ArtifactWrapper.mimeType` guards its own I/O internally, and
  // the pass does not add any special-casing for wrapper types or file
  // states — it just calls `mimeType`.
  test("a file deleted after construction still completes the pass") {
    val dir = tempDir()
    val f = new File(dir, "gone.bin")
    Files.write(f.toPath, "data".getBytes("UTF-8"))
    val wrapper = FileWrapper(f, "gone.bin", None)
    f.delete()
    val res =
      AdaptiveMimeBuilder.computeMimeTypes(
        Vector(wrapper),
        Configuration(),
        logger
      )
    assertEquals(res.total, 1L)
    assertEquals(res.completed, 1L)
  }

  // FileWrapper and ByteWrapper artifacts flow through the same
  // worker path — no wrapper-type branching. THEORY: ArtifactWrapper is one
  // abstraction; the pass is not in the business of wrapper internals.
  test("FileWrapper and ByteWrapper are treated uniformly") {
    val dir = tempDir()
    val fileWrappers = (0 until 10).map { i =>
      val f = new File(dir, s"real-$i.bin")
      Files.write(f.toPath, bytes(i))
      FileWrapper(f, s"real-$i.bin", None)
    }.toVector
    val byteWrappers = (0 until 10).map(bw).toVector
    val files = fileWrappers ++ byteWrappers
    val res =
      AdaptiveMimeBuilder.computeMimeTypes(files, Configuration(), logger)
    assertEquals(res.total, 20L)
    assertEquals(res.completed, 20L)

  }

  // workers are reused. THEORY: the pass must hold a bounded worker
  // set (no thread per artifact); with 500 files and a bound of 8 workers,
  // the distinct-thread count must stay within the bound, not scale with the
  // file count. Worker identity is observed via the progress callback, which
  // fires on the resolving worker thread.
  test("workers are reused, distinct threads stay within the bound") {
    val threadsSeen = ConcurrentHashMap[Long, Thread]()
    val files = (0 until 500).map(bw).toVector
    val res = AdaptiveMimeBuilder.computeMimeTypes(
      files,
      Configuration(threads = 4),
      logger,
      progressEvery = 1,
      progress = Some((_, _) => {
        val t = Thread.currentThread()
        threadsSeen.putIfAbsent(t.threadId(), t)
        ()
      })
    )
    assertEquals(res.total, 500L)
    assertEquals(res.completed, 500L)

    // threads=4 -> bound = min(32, max(8, 4)) = 8
    assert(threadsSeen.size() <= 8, s"distinct workers ${threadsSeen.size()}")
    assert(threadsSeen.size() >= 2, s"multiple workers should run")
  }

  // every worker thread is a virtual thread (the cheap concurrency
  // primitive the pass relies on; no platform threads, no pools).
  test("workers are virtual threads") {
    val threadsSeen = ConcurrentHashMap[Long, Thread]()
    val files = (0 until 100).map(bw).toVector
    AdaptiveMimeBuilder.computeMimeTypes(
      files,
      Configuration(),
      logger,
      progressEvery = 1,
      progress = Some((_, _) => {
        val t = Thread.currentThread()
        threadsSeen.putIfAbsent(t.threadId(), t)
        ()
      })
    )
    assert(threadsSeen.size() >= 1)
    assert(threadsSeen.values().asScala.forall(_.isVirtual))
  }

  // the adaptive wiring: measured per-file completion times flow
  // into the controller, the controller's target moves, and the coordinator
  // spawns up to it. To make this deterministic on noisy CI machines, the
  // injected controller's policy is unconditionally "grow": with the
  // smallest legal window (windowSize 1 clamps to 2) and confirmation 1,
  // every closed window grows the target, and with
  // collapseThreshold/growthThreshold at Double.MaxValue the growth
  // predicate (ema < threshold × floor) is mathematically true for ANY
  // measured times while collapse can never fire. So the target must reach
  // its max (4) within the first few completions and stay there, regardless
  // of GC pauses or JIT warmup. The timing-sensitive growth/collapse policy
  // itself is pinned deterministically by
  // `AdaptiveParallelismSuite` with synthetic traces; this
  // test only needs real timings to flow, not to be shaped.
  test("worker target reaches max under an always-grow policy") {
    val seen = ConcurrentLinkedQueue[(Long, Int)]()
    val files = (0 until 500).map(bw).toVector
    val alwaysGrow = AdaptiveParallelism(
      min = 1,
      max = 4,
      start = 2,
      windowSize = 1,
      collapseConfirmationWindows = 1,
      growthConfirmationWindows = 1,
      collapseThreshold = Double.MaxValue,
      growthThreshold = Double.MaxValue
    )
    val res = AdaptiveMimeBuilder.computeMimeTypes(
      files,
      Configuration(),
      logger,
      progressEvery = 1,
      progress = Some((c, w) => seen.add((c, w))),
      controller = Some(alwaysGrow)
    )
    assertEquals(res.total, 500L)
    assertEquals(res.completed, 500L)
    val maxWorkers = seen.asScala.map(_._2).max
    assertEquals(maxWorkers, 4)
  }

  // progress is an Option callback (no null in the API). The Some
  // variant receives (completed, workers) at every progressEvery multiple;
  // the None variant completes silently through the default logger.
  test("progress is an Option and fires at progressEvery multiples") {
    val files = (0 until 25).map(bw).toVector
    val seen = ConcurrentLinkedQueue[(Long, Int)]()
    val res = AdaptiveMimeBuilder.computeMimeTypes(
      files,
      Configuration(),
      logger,
      progressEvery = 5,
      progress = Some((c, w) => seen.add((c, w)))
    )
    assertEquals(res.completed, 25L)
    assertEquals(
      seen.asScala.map(_._1).toVector,
      Vector(5L, 10L, 15L, 20L, 25L)
    )

    val resNone =
      AdaptiveMimeBuilder.computeMimeTypes(files, Configuration(), logger)
    assertEquals(resNone.completed, 25L)
  }

  // an empty corpus completes immediately with zero counts.
  test("empty corpus completes immediately") {
    val res =
      AdaptiveMimeBuilder.computeMimeTypes(Vector(), Configuration(), logger)
    assertEquals(res.total, 0L)
    assertEquals(res.completed, 0L)

  }

  // a full run against real wrappers (real Tika detection) drains
  // cleanly — no hang, no leftover workers, the accounting invariant holds.
  // THEORY: this is the shape of the production call: every file resolved
  // exactly once.
  test("real mimeType work drains cleanly") {
    val files = (0 until 100).map(bw).toVector
    val res =
      AdaptiveMimeBuilder.computeMimeTypes(files, Configuration(), logger)
    assertEquals(res.total, 100L)
    assertEquals(res.completed, 100L)

  }
}
