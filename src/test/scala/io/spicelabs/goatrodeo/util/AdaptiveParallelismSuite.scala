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

import munit.FunSuite
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalacheck.Prop.propBoolean

/** Tests for the [[AdaptiveParallelism]] concurrency controller.
  *
  * WHAT: the controller answers "how many workers can this device serve before
  * throughput collapses", using the median per-item completion time of each
  * window.
  *
  * WHY: the MIME precompute pass runs against corpora that can exceed RAM.
  * While the page cache serves reads, many workers are fine; once the working
  * set spills to real disk, high concurrency thrashes. The controller is the
  * signal that drives the worker count up or down.
  *
  * THEORY: the tests drive the controller with synthetic completion-time traces
  * (no wall clock, no I/O — the controller is a pure function of the
  * `record`/`forceWindowClose` calls it receives), so every policy transition
  * is deterministic. The property test pins that the concurrency target never
  * leaves [min, max] for arbitrary traces.
  *
  * LLM note: T-AP-xx = test id; each test documents the requirement it pins.
  * The clamping tests (T-AP-02) pin R6: the constructor never raises, invalid
  * parameters are coerced, and the production defaults are byte-identical.
  */
class AdaptiveParallelismSuite extends FunSuite with ScalaCheckSuite {

  /** Fill `windowSize` completions with the given synthetic nanos. */
  private def windowOf(c: AdaptiveParallelism, nanos: Long): Unit = {
    var i = 0
    while (i < c.windowSize) {
      c.record(nanos)
      i += 1
    }
  }

  /** Fill `n` windows with the given synthetic nanos. */
  private def windowsOf(c: AdaptiveParallelism, n: Int, nanos: Long): Unit = {
    var i = 0
    while (i < n) {
      windowOf(c, nanos)
      i += 1
    }
  }

  // T-AP-01 — R6: production defaults must be byte-identical to the original
  // (v2) controller so tuning behavior is unchanged for callers that rely on
  // the defaults.
  test("T-AP-01 production defaults are pinned") {
    val c = AdaptiveParallelism()
    assertEquals(c.min, 1)
    assertEquals(c.max, 32)
    assertEquals(c.start, 2)
    assertEquals(c.windowSize, 128)
    assertEquals(c.windowNanos, 1000L * 1000L * 1000L)
    assertEquals(c.collapseThreshold, 3.0)
    assertEquals(c.growthThreshold, 1.5)
    assertEquals(c.collapseConfirmationWindows, 2)
    assertEquals(c.growthConfirmationWindows, 3)
    assertEquals(c.collapseCooldownWindows, 10)
    assertEquals(c.emaAlpha, 0.5)
    assertEquals(c.floorAlpha, 0.1)
    assertEquals(c.current, 2)
  }

  // T-AP-02 — R6: invalid constructor parameters must be clamped, never raise.
  // THEORY: the pass must not be able to die from a bad configuration; the
  // coercions pin the parameter space. NaN/Infinity floating inputs fall back
  // to the production default.
  test("T-AP-02 invalid parameters clamp instead of raising") {
    assertEquals(AdaptiveParallelism(min = 0).min, 1)
    assertEquals(AdaptiveParallelism(min = -7).min, 1)

    val maxBelowMin = AdaptiveParallelism(min = 9, max = 8)
    assertEquals(maxBelowMin.min, 9)
    assertEquals(maxBelowMin.max, 9)
    assertEquals(maxBelowMin.start, 9)
    assertEquals(maxBelowMin.current, 9)

    assertEquals(AdaptiveParallelism(max = 0).max, 1)
    assertEquals(AdaptiveParallelism(start = 100, max = 8).start, 8)
    assertEquals(AdaptiveParallelism(start = -3).start, 1)
    assertEquals(AdaptiveParallelism(windowSize = 1).windowSize, 2)
    assertEquals(
      AdaptiveParallelism(collapseConfirmationWindows =
        0
      ).collapseConfirmationWindows,
      1
    )
    assertEquals(
      AdaptiveParallelism(growthConfirmationWindows =
        -3
      ).growthConfirmationWindows,
      1
    )
    assertEquals(
      AdaptiveParallelism(collapseCooldownWindows = -5).collapseCooldownWindows,
      0
    )
    assertEquals(
      AdaptiveParallelism(windowNanos = -1L).windowNanos,
      1000000000L
    )
    assertEquals(AdaptiveParallelism(windowNanos = 0L).windowNanos, 1000000000L)

    assertEquals(AdaptiveParallelism(emaAlpha = Double.NaN).emaAlpha, 0.5)
    assertEquals(AdaptiveParallelism(emaAlpha = 1.5).emaAlpha, 0.5)
    assertEquals(AdaptiveParallelism(emaAlpha = -0.2).emaAlpha, 0.5)
    assertEquals(
      AdaptiveParallelism(floorAlpha = Double.NegativeInfinity).floorAlpha,
      0.1
    )
    assertEquals(
      AdaptiveParallelism(collapseThreshold =
        Double.PositiveInfinity
      ).collapseThreshold,
      3.0
    )
    assertEquals(
      AdaptiveParallelism(collapseThreshold = 0.5).collapseThreshold,
      3.0
    )
    assertEquals(
      AdaptiveParallelism(growthThreshold = Double.NaN).growthThreshold,
      1.5
    )
  }

  // T-AP-03 — R6 (property): for arbitrary completion-time traces, the
  // concurrency target never leaves [min, max]. THEORY: whatever the shape of
  // the trace — pathological outliers, bursts, empty windows — the controller
  // must stay in bounds; a violation would let the pass over-subscribe the
  // device it is trying to protect.
  property("T-AP-03 concurrency stays within [min, max] for arbitrary traces") {
    forAll(
      Gen.choose(2, 64),
      Gen.listOfN(400, Gen.choose(0L, 1000000000L))
    ) { (window, times) =>
      val c = AdaptiveParallelism(
        min = 1,
        max = 8,
        start = 2,
        windowSize = window,
        collapseConfirmationWindows = 1,
        growthConfirmationWindows = 1
      )
      times.foreach { t =>
        c.record(t)
        c.forceWindowClose()
      }
      (c.current >= c.min && c.current <= c.max) :| s"current=${c.current}"
    }
  }

  // T-AP-04 — collapse policy: sustained slow completions (median far above
  // the best-seen floor) halve the target down to min. THEORY: multiplicative
  // decrease is the back-off the pass relies on when the cache stops serving
  // reads.
  test("T-AP-04 sustained slow windows collapse the target to min") {
    val c = AdaptiveParallelism(
      min = 1,
      max = 8,
      start = 2,
      windowSize = 4,
      collapseConfirmationWindows = 1
    )
    windowsOf(c, 40, 1000L) // fast: grow to the 8 cap
    assertEquals(c.current, 8)
    windowsOf(c, 6, 1000000000L) // slow: halve per window (confirmation = 1)
    assertEquals(c.current, 1)
  }

  // T-AP-05 — growth policy: sustained fast completions grow the target by
  // one probe at a time up to max. THEORY: additive increase keeps the probe
  // gentle; the pass should rediscover spare throughput after a transient
  // slow period.
  test("T-AP-05 sustained fast windows grow the target to max") {
    val c = AdaptiveParallelism(min = 1, max = 8, start = 2, windowSize = 4)
    windowsOf(c, 40, 1000L) // 3 growth-confirmation windows per +1
    assertEquals(c.current, 8)
  }

  // T-AP-06 — collapse cooldown: after a collapse, growth is banned for
  // `collapseCooldownWindows` windows even when completions are fast, so a
  // borderline corpus settles instead of saw-toothing. THEORY: without the
  // cooldown the controller would immediately re-probe into the regime that
  // just collapsed.
  //
  // Trace note: one slow spike causes two collapse windows by design — the
  // EMA decays only by half per window while the floor rises 10%, so the
  // ratio stays above `collapseThreshold` for the window after the spike
  // (8 -> 4 -> 2). The second collapse resets the cooldown; the pinned
  // behavior is that the following fast windows cannot grow until the
  // cooldown expires.
  test("T-AP-06 growth is banned for the cooldown window after collapse") {
    val c = AdaptiveParallelism(
      min = 1,
      max = 8,
      start = 2,
      windowSize = 4,
      collapseConfirmationWindows = 1,
      growthConfirmationWindows = 1,
      collapseCooldownWindows = 10
    )
    windowsOf(c, 20, 1000L) // grow to 8
    assertEquals(c.current, 8)
    windowsOf(c, 1, 1000000000L) // spike: collapse 8 -> 4, cooldown = 10
    assertEquals(c.current, 4)
    windowsOf(c, 8, 0L) // spike decays; second collapse 4 -> 2, then banned
    assertEquals(c.current, 2)
    windowsOf(c, 20, 0L) // cooldown expires, growth resumes
    assert(
      c.current > 2,
      s"growth should resume after cooldown, got ${c.current}"
    )
  }

  // T-AP-07 — median robustness: the window statistic is the median, so a
  // single pathological completion cannot drag the signal down. THEORY: a
  // mean-based controller would collapse on one huge file; the median must
  // ignore it.
  test("T-AP-07 a single outlier does not move the median") {
    val c = AdaptiveParallelism(windowSize = 4)
    c.record(100L)
    c.record(100L)
    c.record(100L)
    c.record(1000000000L)
    assertEquals(c.lastMedianNanos, 100.0)
  }

  // T-AP-08 — partial windows: forceWindowClose evaluates whatever has
  // accumulated (the wall-clock timer path), even when the window is not
  // full. THEORY: very slow workloads must still produce measurements.
  test("T-AP-08 forceWindowClose evaluates a partial window") {
    val c = AdaptiveParallelism(windowSize = 128)
    c.record(10L)
    c.record(30L)
    assertEquals(c.lastMedianNanos, 0.0) // not closed yet
    c.forceWindowClose()
    assertEquals(c.lastMedianNanos, 20.0)
  }

  // T-AP-09 — window storage grows past windowSize without losing samples or
  // raising. THEORY: the internal buffer expands as needed; the median of the
  // remainder after the last auto-close must be exact.
  test("T-AP-09 record survives more samples than windowSize") {
    val c = AdaptiveParallelism(windowSize = 4)
    var i = 1L
    while (i <= 15L) {
      c.record(i)
      i += 1
    }
    c.forceWindowClose()
    // windows: [1..4], [5..8], [9..12], remainder [13,14,15] -> median 14
    assertEquals(c.lastMedianNanos, 14.0)
  }
}
