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
import munit.FunSuite

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
  * (bounded, T-A-03) and nothing can escape the pass (T-A-01/08 + the
  * mechanical scans in `MimePipelineRulesSuite`).
  *
  * THEORY: the worker count adapts on measured per-file throughput (T-A-05,
  * with the policy transitions themselves pinned deterministically in
  * `AdaptiveParallelismSuite`). The worker body is just `a.mimeType` — there is
  * no injected computation seam — so the tests run real wrappers and real Tika
  * detection. Worker identity is observed through the progress callback, which
  * fires on the resolving worker thread.
  *
  * LLM note: T-A-xx = test id. `ArtifactWrapper.mimeType` guards its own I/O
  * internally (returns `application/octet-stream` on trouble), so no test can
  * make a real wrapper throw; the never-raise property is therefore pinned by
  * behavior on real wrappers plus the source scans.
  */
class AdaptiveMimeBuilderSuite extends FunSuite {

  private val logger = Logger(getClass.getName)

  private def bytes(i: Int): Array[Byte] =
    s"content-$i".getBytes("UTF-8")

  private def bw(i: Int): ByteWrapper =
    ByteWrapper(bytes(i), s"file-$i.bin", None)

  private def tempDir(): File = Files.createTempDirectory("mime-test").toFile()

  // T-A-01 — a file deleted after the walk completes the pass without a
  // failure: `ArtifactWrapper.mimeType` guards its own I/O internally, and
  // the pass does not add any special-casing for wrapper types or file
  // states — it just calls `mimeType`.
  test("T-A-01 a file deleted after construction still completes the pass") {
    val dir = tempDir()
    val f = new File(dir, "gone.bin")
    Files.write(f.toPath, "data".getBytes("UTF-8"))
    val wrapper = FileWrapper(f, "gone.bin", None)
    f.delete()
    val res =
      AdaptiveMimeBuilder.computeMimeTypes(Vector(wrapper), Config(), logger)
    assertEquals(res.total, 1L)
    assertEquals(res.completed, 1L)
  }

  // T-A-02 — FileWrapper and ByteWrapper artifacts flow through the same
  // worker path — no wrapper-type branching. THEORY: ArtifactWrapper is one
  // abstraction; the pass is not in the business of wrapper internals.
  test("T-A-02 FileWrapper and ByteWrapper are treated uniformly") {
    val dir = tempDir()
    val fileWrappers = (0 until 10).map { i =>
      val f = new File(dir, s"real-$i.bin")
      Files.write(f.toPath, bytes(i))
      FileWrapper(f, s"real-$i.bin", None)
    }.toVector
    val byteWrappers = (0 until 10).map(bw).toVector
    val files = fileWrappers ++ byteWrappers
    val res = AdaptiveMimeBuilder.computeMimeTypes(files, Config(), logger)
    assertEquals(res.total, 20L)
    assertEquals(res.completed, 20L)

  }

  // T-A-03 — workers are reused. THEORY: the pass must hold a bounded worker
  // set (no thread per artifact); with 500 files and a bound of 8 workers,
  // the distinct-thread count must stay within the bound, not scale with the
  // file count. Worker identity is observed via the progress callback, which
  // fires on the resolving worker thread.
  test("T-A-03 workers are reused, distinct threads stay within the bound") {
    val threadsSeen = ConcurrentHashMap[Long, Thread]()
    val files = (0 until 500).map(bw).toVector
    val res = AdaptiveMimeBuilder.computeMimeTypes(
      files,
      Config(threads = 4),
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

  // T-A-04 — every worker thread is a virtual thread (the cheap concurrency
  // primitive the pass relies on; no platform threads, no pools).
  test("T-A-04 workers are virtual threads") {
    val threadsSeen = ConcurrentHashMap[Long, Thread]()
    val files = (0 until 100).map(bw).toVector
    AdaptiveMimeBuilder.computeMimeTypes(
      files,
      Config(),
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

  // T-A-05 — the worker target adapts on measured mimeType throughput: with
  // a sustained fast corpus, the target grows above its start (observed via
  // the progress callback's worker count). The slow-side collapse policy is
  // pinned deterministically by `AdaptiveParallelismSuite.T-AP-04` with
  // synthetic traces; real files cannot be made deterministically slow
  // without a computation seam, which the design deliberately does not have.
  test("T-A-05 worker target grows under sustained fast completions") {
    val seen = ConcurrentLinkedQueue[(Long, Int)]()
    val files = (0 until 500).map(bw).toVector
    val res = AdaptiveMimeBuilder.computeMimeTypes(
      files,
      Config(),
      logger,
      progressEvery = 1,
      progress = Some((c, w) => seen.add((c, w)))
    )
    assertEquals(res.total, 500L)
    assertEquals(res.completed, 500L)

    val maxWorkers = seen.asScala.map(_._2).max
    assert(
      maxWorkers >= 3,
      s"target should grow past start 2, saw max $maxWorkers"
    )
  }

  // T-A-06 — progress is an Option callback (no null in the API). The Some
  // variant receives (completed, workers) at every progressEvery multiple;
  // the None variant completes silently through the default logger.
  test("T-A-06 progress is an Option and fires at progressEvery multiples") {
    val files = (0 until 25).map(bw).toVector
    val seen = ConcurrentLinkedQueue[(Long, Int)]()
    val res = AdaptiveMimeBuilder.computeMimeTypes(
      files,
      Config(),
      logger,
      progressEvery = 5,
      progress = Some((c, w) => seen.add((c, w)))
    )
    assertEquals(res.completed, 25L)
    assertEquals(
      seen.asScala.map(_._1).toVector,
      Vector(5L, 10L, 15L, 20L, 25L)
    )

    val resNone = AdaptiveMimeBuilder.computeMimeTypes(files, Config(), logger)
    assertEquals(resNone.completed, 25L)
  }

  // T-A-07 — an empty corpus completes immediately with zero counts.
  test("T-A-07 empty corpus completes immediately") {
    val res =
      AdaptiveMimeBuilder.computeMimeTypes(Vector(), Config(), logger)
    assertEquals(res.total, 0L)
    assertEquals(res.completed, 0L)

  }

  // T-A-08 — a full run against real wrappers (real Tika detection) drains
  // cleanly — no hang, no leftover workers, the accounting invariant holds.
  // THEORY: this is the shape of the production call: every file resolved
  // exactly once.
  test("T-A-08 real mimeType work drains cleanly") {
    val files = (0 until 100).map(bw).toVector
    val res = AdaptiveMimeBuilder.computeMimeTypes(files, Config(), logger)
    assertEquals(res.total, 100L)
    assertEquals(res.completed, 100L)

  }
}
