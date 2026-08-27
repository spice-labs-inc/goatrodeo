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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport

/** Adaptive, worker-pool MIME type precompute.
  *
  * Computes `file.mimeType` for every file in the corpus exactly once, using a
  * bounded set of long-lived virtual-thread workers. The worker count is driven
  * up and down by an [[AdaptiveParallelism]] controller fed with the measured
  * per-file completion times.
  *
  * Why this shape
  *   - The reads that matter (Tika's window, the augmenter probes) are all head
  *     reads. While the OS page cache serves them, many concurrent workers are
  *     fine; once the working set exceeds RAM, each worker's read is a real
  *     seek and high concurrency thrashes a spinning disk. The controller
  *     measures exactly this: the median per-file completion time. Fast
  *     (cache-served) medians let it probe up; slow (seeking) medians make it
  *     halve.
  *   - Workers are long-lived: one virtual thread per worker, pulling work from
  *     a shared atomic cursor. There is no thread pool, no scheduler, no
  *     reflection — virtual threads are cheap to create and the cursor is the
  *     only shared state.
  *   - No file failure can stop the pass. Each worker treats its current file
  *     as a boundary: whatever escapes a worker's `mimeType` call is caught,
  *     counted, and logged (sanitized path, full cause chain, rate-limited).
  *     The pass completes when every file has resolved as either completed or
  *     failed, so `total == completed + failed` is the completion criterion and
  *     the result is always returned, never raised.
  *
  * The MIME detection pipeline itself is untouched: the pass only forces the
  * (lazy) `mimeType` on each wrapper, so results are identical to the
  * sequential computation.
  */
object AdaptiveMimeBuilder {

  /** The outcome of one precompute pass. */
  final case class MimePassResult(
      total: Long,
      completed: Long
  )

  /** Full failure logs are emitted for the first `MaxFullFailureLogs` failures,
    * then one summary log per `FailureSummaryEvery` failures.
    */
  val MaxFullFailureLogs: Int = 100

  val FailureSummaryEvery: Long = 10000L

  /** The default progress callback: an INFO line reporting the completed count
    * and the current worker target, on the cadence of `progressEvery`.
    */
  def progressLog(logger: Logger)(c: Long, w: Int): Unit = {
    logger.info(f"Mime builder count ${c}%,d (workers ${w})")
  }

  /** Escape C0/C1 control characters in a path so an untrusted corpus cannot
    * inject terminal escapes or fake log lines. Every other character passes
    * through unchanged.
    */
  def sanitizePath(raw: String): String = {
    val sb = new StringBuilder(raw.length)
    raw.foreach { ch =>
      val c = ch.toInt
      if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) {
        sb.append(f"\\u${c}%04x")
      } else {
        sb.append(ch)
      }
    }
    sb.toString
  }

  private def dirOf(path: String): String = {
    val idx = path.lastIndexOf('/')
    if (idx <= 0) "(root)" else path.substring(0, idx)
  }

  private def recordFailureDir(
      dirs: ConcurrentHashMap[String, LongAdder],
      dir: String
  ): Unit = {
    Option(dirs.get(dir)) match {
      case Some(adder) => adder.increment()
      case None =>
        val created = LongAdder()
        Option(dirs.putIfAbsent(dir, created)) match {
          case Some(adder) => adder.increment()
          case None        => created.increment()
        }
    }
  }

  private def topDirs(
      dirs: ConcurrentHashMap[String, LongAdder],
      limit: Int
  ): Vector[(String, Long)] = {
    var entries = Vector.empty[(String, Long)]
    dirs.forEach((k, v) => entries = entries :+ (k -> v.sum()))
    entries.sortBy(_._2).reverse.take(limit)
  }

  private def logFailure(
      logger: Logger,
      safePath: String,
      cause: Throwable,
      failureCount: Long
  ): Unit = {
    if (
      failureCount <= MaxFullFailureLogs ||
      failureCount % FailureSummaryEvery == 0
    ) {
      logger.error(s"MIME pass failure #${failureCount}: ${safePath}", cause)
    }
  }

  /** Compute the MIME type of every artifact in `files`.
    *
    * Semantics:
    *   - Every wrapper's `mimeType` is forced exactly once — the worker body is
    *     just `a.mimeType`, timed, nothing else.
    *   - A failure in one file is caught at the worker boundary, counted, and
    *     logged; it never stops the pass and never escapes it. The returned
    *     result always satisfies `total == completed + failed`.
    *   - The worker count starts at 2 and adapts between 1 and `min(32, max(8,
    *     args.threads))` based on measured per-file throughput, unless an
    *     explicit `controller` is supplied.
    *   - Progress is reported every `progressEvery` completions through
    *     `progress` (default: an INFO log line including the current worker
    *     target).
    *
    * @param files
    *   the artifacts to compute MIME types for
    * @param args
    *   configuration (uses `threads` for the adaptive worker bound)
    * @param logger
    *   the logger to report progress and failures through
    * @param progressEvery
    *   how many completions between progress callbacks
    * @param progress
    *   called as `(completed, currentWorkers)` every `progressEvery`
    *   completions; `None` selects the default INFO logging implementation
    * @param controller
    *   the adaptive concurrency controller; `None` selects the production
    *   default (min 1, start 2, max from `args.threads`)
    * @return
    *   the pass accounting
    */
  def computeMimeTypes(
      files: Vector[ArtifactWrapper],
      args: Configuration,
      logger: Logger,
      progressEvery: Long = 100000L,
      progress: Option[(Long, Int) => Unit] = None,
      controller: Option[AdaptiveParallelism] = None
  ): MimePassResult = {
    val total = files.length.toLong
    if (total == 0) {
      MimePassResult(0, 0)
    } else {

      val doProgress = progress.getOrElse(progressLog(logger))
      val progressStep = math.max(1L, progressEvery)

      val bound = math.min(32, math.max(8, args.threads))
      val adaptive = controller.getOrElse(
        AdaptiveParallelism(min = 1, max = bound, start = 2)
      )

      val nextIndex = AtomicLong(0)
      val liveWorkers = AtomicInteger(0)
      val completed = LongAdder()
      // The next progress milestone to report. Each worker atomically claims the
      // milestone it crosses, so every progressEvery-th completion is reported
      // exactly once even though `completed.sum()` (read after increment) can
      // observe a value that overshoots a milestone under concurrency.
      val milestone = AtomicLong(progressStep)

      // The worker body: claim the next index, force the file's mimeType, record
      // the outcome. The inner try/catch is the boundary — nothing below it can
      // stop the pass.
      val workerBody: Runnable = () => {
        try {
          var keepGoing = true
          while (keepGoing) {
            if (liveWorkers.get() > adaptive.current) {
              // the target shrank; this worker retires after its current item
              keepGoing = false
            } else {
              val idx = nextIndex.getAndIncrement()
              if (idx >= total) {
                keepGoing = false
              } else {
                val wrapper = files(idx.toInt)

                val t0 = System.nanoTime()
                wrapper.mimeType
                adaptive.record(math.max(0L, System.nanoTime() - t0))
                completed.increment()

                val done = completed.sum()
                var m = milestone.get()
                while (
                  done >= m && milestone.compareAndSet(m, m + progressStep)
                ) {
                  doProgress(m, adaptive.current)
                  m = milestone.get()
                }
              }
            }
          }
        } finally {
          liveWorkers.decrementAndGet()
        }
      }

      def snapshot(): MimePassResult = {

        MimePassResult(
          total,
          completed.sum()
        )
      }

      var lastWindowCheck = System.nanoTime()
      var keepCoordinating = true
      var outcome = snapshot()
      while (keepCoordinating) {
        val now = System.nanoTime()
        if (now - lastWindowCheck >= adaptive.windowNanos) {
          adaptive.forceWindowClose()
          lastWindowCheck = now
        }

        var spawnOk = true
        var spawned = false
        while (
          spawnOk && liveWorkers.get() < adaptive.current &&
          nextIndex.get() < total
        ) {
          liveWorkers.incrementAndGet()
          try {
            Thread.ofVirtual().name("mime-pass-", 0).start(workerBody)
            spawned = true
          } catch {
            case cause: Throwable =>
              liveWorkers.decrementAndGet()
              logger.error("Could not start a MIME pass worker", cause)
              spawnOk = false
          }
        }

        if (completed.sum() >= total) {
          adaptive.forceWindowClose()
          // resolution is the completion criterion; the drain only waits for
          // the winding-down workers to finish their final log lines and
          // decrement, so nothing is still writing when the pass returns
          var drained = false
          while (!drained) {
            if (liveWorkers.get() == 0) {
              drained = true
            } else {
              try {
                LockSupport.parkNanos(100_000L)
              } catch {
                case _: InterruptedException =>
                  Thread.currentThread().interrupt()
                  drained = true
              }
            }
          }
          outcome = snapshot()
          keepCoordinating = false
        } else if (!spawnOk) {
          // the spawning machinery itself is broken; report what is accounted
          // and stop rather than spin
          outcome = snapshot()
          keepCoordinating = false
        } else if (!spawned) {
          try {
            LockSupport.parkNanos(100_000L)
          } catch {
            case _: InterruptedException =>
              Thread.currentThread().interrupt()
              outcome = snapshot()
              keepCoordinating = false
          }
        }
      }
      outcome
    }
  }
}
