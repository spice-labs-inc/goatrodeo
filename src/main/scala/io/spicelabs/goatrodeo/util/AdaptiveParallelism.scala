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

/** An adaptive concurrency controller for I/O-heavy passes.
  *
  * The controller answers one question: "how many concurrent workers can this
  * device serve before the aggregate throughput collapses?" The signal is the
  * median per-item completion time (in nanoseconds) observed in each window.
  *
  * Theory of operation
  *   - A window is a group of `windowSize` completions, or whatever has
  *     accumulated when `forceWindowClose()` is called (e.g. by a wall-clock
  *     timer so very slow workloads still get measured).
  *   - The window's median completion time is a robust (outlier-resistant)
  *     estimate of "how long a typical item took". A mean would let a single
  *     pathological item drag the signal down and cause an unnecessary global
  *     back-off.
  *   - The EMA of the median is compared against a slowly-decaying floor, the
  *     best (smallest) median seen. Fast items keep the EMA near the floor;
  *     when the working set stops being served by the page cache and real disk
  *     seeking begins, the median jumps far above the floor.
  *   - If `ema > collapseThreshold * floor` for `collapseConfirmationWindows`
  *     consecutive windows, the concurrency halves (multiplicative decrease,
  *     the back-off). If `ema < growthThreshold * floor` for
  *     `growthConfirmationWindows` consecutive windows, concurrency increases
  *     by one (additive increase probe).
  *   - After a collapse, growth is banned for `collapseCooldownWindows` windows
  *     so a corpus whose size is borderline for RAM settles at the lower stable
  *     concurrency instead of saw-toothing between thrashing and recovery.
  *
  * The controller is pure and deterministic: it is a function of the sequence
  * of `record`/`forceWindowClose` calls made against it. It performs no I/O and
  * reads no clock, so it can be driven by synthetic traces in tests.
  *
  * Construction (via the companion `apply`) never raises: out-of-range or
  * non-finite parameters are clamped to sane values (pinned by
  * `AdaptiveParallelismSuite.T-AP-02`); the production defaults are exactly the
  * defaults below.
  */
final class AdaptiveParallelism private (
    val min: Int,
    val max: Int,
    val start: Int,
    val windowSize: Int,
    val windowNanos: Long,
    val collapseThreshold: Double,
    val growthThreshold: Double,
    val collapseConfirmationWindows: Int,
    val growthConfirmationWindows: Int,
    val collapseCooldownWindows: Int,
    val emaAlpha: Double,
    val floorAlpha: Double
) {

  private var _current: Int = start

  /** The median completion time (nanos) of the most recently closed window; 0.0
    * before any window has closed.
    */
  @volatile
  private var _lastMedianNanos: Double = 0.0

  /** The ratio `ema / floor` of the most recently closed window; 0.0 before any
    * window has closed or when the floor is 0. Useful for diagnostics: a ratio
    * above `collapseThreshold` is a cache-exhausted regime.
    */
  @volatile
  private var _currentRatio: Double = 0.0

  /** The current concurrency target. */
  def current: Int = _current

  def lastMedianNanos: Double = _lastMedianNanos

  def currentRatio: Double = _currentRatio

  // window accumulation (guarded by `this` monitor)
  private var times: Array[Long] = new Array[Long](windowSize)
  private var count: Int = 0
  private var ema: Double = 0.0
  private var floor: Double = 0.0
  private var initialized: Boolean = false
  private var consecutiveCollapse: Int = 0
  private var consecutiveGrowth: Int = 0
  private var cooldown: Int = 0

  /** Record the completion time (nanos) of one item. When the window fills, the
    * window is evaluated and the concurrency target may change.
    *
    * Safe to call from multiple threads.
    */
  def record(completionNanos: Long): Unit = synchronized {
    if (count == times.length) {
      times = java.util.Arrays.copyOf(times, times.length * 2)
    }
    times(count) = math.max(0L, completionNanos)
    count += 1
    if (count >= windowSize) closeWindow()
  }

  /** Close the current window early (e.g. because a wall-clock timer fired) and
    * evaluate it, even if it has few or no samples.
    */
  def forceWindowClose(): Unit = synchronized {
    closeWindow()
  }

  private def closeWindow(): Unit = {
    if (count == 0) {
      return
    }
    val n = count
    count = 0
    java.util.Arrays.sort(times, 0, n)
    val median: Double =
      if (n % 2 == 1) times(n / 2).toDouble
      else (times(n / 2 - 1).toDouble + times(n / 2).toDouble) / 2.0
    _lastMedianNanos = median

    if (!initialized) {
      ema = median
      floor = median
      initialized = true
    } else {
      ema = emaAlpha * median + (1.0 - emaAlpha) * ema
      val chasing = (1.0 - floorAlpha) * floor + floorAlpha * ema
      floor = math.min(ema, chasing)
    }

    _currentRatio = if (floor > 0.0) ema / floor else 0.0

    // The collapse cooldown is consumed at the top of each subsequent window:
    // the window that fires the collapse sets it, so growth is banned for a
    // full `collapseCooldownWindows` windows after the collapse.
    if (cooldown > 0) cooldown -= 1

    val collapse = floor > 0.0 && ema > collapseThreshold * floor
    if (collapse) {
      consecutiveCollapse += 1
      consecutiveGrowth = 0
      if (consecutiveCollapse >= collapseConfirmationWindows) {
        _current = math.max(min, _current / 2)
        consecutiveCollapse = 0
        cooldown = collapseCooldownWindows
      }
    } else {
      consecutiveCollapse = 0
      val growth = floor == 0.0 || ema < growthThreshold * floor
      if (growth) {
        consecutiveGrowth += 1
        if (
          consecutiveGrowth >= growthConfirmationWindows &&
          cooldown == 0 &&
          _current < max
        ) {
          _current += 1
          consecutiveGrowth = 0
        } else if (cooldown > 0) {
          // do not bank growth credit while the cooldown ban is in effect
          consecutiveGrowth = 0
        }
      } else {
        consecutiveGrowth = 0
      }
    }
  }
}

object AdaptiveParallelism {

  private def clampInt(lo: Int, hi: Int, v: Int): Int =
    math.max(lo, math.min(hi, v))

  private def clampedDouble(
      v: Double,
      lo: Double,
      hi: Double,
      dflt: Double
  ): Double = {
    if (java.lang.Double.isFinite(v) && v >= lo && v <= hi) v else dflt
  }

  /** Build a controller with clamped parameters (never raises). See the class
    * documentation for the meaning of each parameter.
    */
  def apply(
      min: Int = 1,
      max: Int = 32,
      start: Int = 2,
      windowSize: Int = 128,
      windowNanos: Long = 1000L * 1000L * 1000L,
      collapseThreshold: Double = 3.0,
      growthThreshold: Double = 1.5,
      collapseConfirmationWindows: Int = 2,
      growthConfirmationWindows: Int = 3,
      collapseCooldownWindows: Int = 10,
      emaAlpha: Double = 0.5,
      floorAlpha: Double = 0.1
  ): AdaptiveParallelism = {
    val cMin = clampInt(1, 4096, min)
    val cMax = clampInt(cMin, 4096, max)
    val cStart = clampInt(cMin, cMax, start)
    val cWindowSize = clampInt(2, 1 << 24, windowSize)
    val cWindowNanos =
      if (windowNanos > 0) windowNanos else 1000L * 1000L * 1000L
    val cCollapseThreshold =
      clampedDouble(collapseThreshold, 1.0, Double.MaxValue, 3.0)
    val cGrowthThreshold =
      clampedDouble(growthThreshold, 1.0, Double.MaxValue, 1.5)
    val cCollapseConfirmation = clampInt(1, 4096, collapseConfirmationWindows)
    val cGrowthConfirmation = clampInt(1, 4096, growthConfirmationWindows)
    val cCooldown = clampInt(0, 1 << 20, collapseCooldownWindows)
    val cEmaAlpha = clampedDouble(emaAlpha, 0.0, 1.0, 0.5)
    val cFloorAlpha = clampedDouble(floorAlpha, 0.0, 1.0, 0.1)
    new AdaptiveParallelism(
      cMin,
      cMax,
      cStart,
      cWindowSize,
      cWindowNanos,
      cCollapseThreshold,
      cGrowthThreshold,
      cCollapseConfirmation,
      cGrowthConfirmation,
      cCooldown,
      cEmaAlpha,
      cFloorAlpha
    )
  }
}
