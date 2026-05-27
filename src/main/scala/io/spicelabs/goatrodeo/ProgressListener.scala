/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo

import com.typesafe.scalalogging.Logger

/** A callback for receiving progress notifications during a Goat Rodeo run.
  *
  * The listener is invoked periodically while items flow through the build
  * pipeline, at the same cadence as the existing "Processed N of M" log
  * emission (every 1,000 items or 30 seconds, whichever fires first).
  *
  * No phase or boundary signals are exposed — Goat Rodeo's filesystem walk,
  * artifact processing, and per-batch output writing all overlap freely on
  * separate threads, so any "phase boundary" would be a fiction. A caller
  * that needs phase-like UI states can synthesize them from `current/total`
  * (e.g. "Starting" before the first event, "Processing N of M" while
  * `current < total`, "Finalizing" once `current == total` and `onProgress`
  * stops firing) and from the synchronous return of `Howdy.run` /
  * `GoatRodeoBuilder.run`, which signals overall completion.
  *
  * Events arrive from worker threads — typically several in parallel — so
  * implementations must be thread-safe. Goat Rodeo enforces monotonic
  * `current` per run (out-of-order ticks from racing threads are dropped)
  * so the listener never sees backwards progress within a run, but two
  * callbacks can still overlap in time; serialize on the listener side if
  * that matters.
  *
  * Exceptions thrown by the listener are caught and logged; they never
  * abort the build.
  *
  * The trait has a single abstract method, so Java callers may pass a
  * lambda directly:
  *
  * {{{
  * goatRodeo.withProgressListener((current, total) -> {
  *     // ...
  * });
  * }}}
  *
  * @see [[GoatRodeoBuilder.withProgressListener]]
  */
trait ProgressListener {

  /** Receive a progress notification.
    *
    * `current` is the number of top-level artifacts processed so far;
    * `total` is the number discovered by the filesystem walk and may
    * continue to grow until the walk completes. Both are non-negative;
    * `current <= total` always holds.
    *
    * @param current items processed so far
    * @param total   items discovered so far (may still be growing)
    */
  def onProgress(current: Long, total: Long): Unit
}

object ProgressListener {

  /** Build a per-run dispatcher that wraps `listener` with monotonic-current
    * enforcement and exception safety. Internal — Goat Rodeo's pipeline
    * instantiates one per `Howdy.run` invocation and forwards every tick
    * through it from inside the worker-thread callback.
    */
  private[goatrodeo] def notifier(listener: Option[ProgressListener]): Notifier =
    new Notifier(listener)

  /** Per-run dispatcher. Holds the monotonic-current state for one
    * `Howdy.run` invocation so each run starts fresh and concurrent fires
    * from worker threads can't deliver backwards ticks. Safe to call from
    * multiple threads.
    *
    * Delivery is serialized under an intrinsic lock so the wrapped
    * listener observes calls in strict `current`-increasing order — a
    * weaker guarantee (monotonic CAS without serialized delivery) would
    * still let two threads race past their state update and call
    * `onProgress` in the wrong order, which is exactly the backwards
    * progress the listener wants to never see. The lock is uncontended
    * outside the rare moments when multiple worker threads cross the
    * "Processed N of M" emission cadence (every 1,000 items or 30s)
    * simultaneously, so the cost is negligible.
    */
  private[goatrodeo] final class Notifier(listener: Option[ProgressListener]) {

    private val logger = Logger(getClass())
    private var lastReportedCurrent = -1L

    /** Forward a tick to the wrapped listener if and only if `current`
      * strictly exceeds every value previously forwarded by this notifier.
      * Listener exceptions are caught and logged.
      */
    def notify(current: Long, total: Long): Unit = synchronized {
      if (current > lastReportedCurrent) {
        lastReportedCurrent = current
        listener.foreach { l =>
          try {
            l.onProgress(current, total)
          } catch {
            case t: Throwable =>
              logger.warn(s"ProgressListener threw on current=$current total=$total", t)
          }
        }
      }
    }
  }
}
