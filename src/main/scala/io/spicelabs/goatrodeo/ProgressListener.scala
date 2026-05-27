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
  * Implementations are notified when the build crosses a phase boundary
  * ([[ProgressListener.Phase.Scanning]], [[ProgressListener.Phase.Writing]],
  * [[ProgressListener.Phase.Done]]) and periodically during the long-running
  * [[ProgressListener.Phase.Processing]] phase — at the same cadence as the
  * existing "Processed N of M" log emission (every 1,000 items or 30 seconds,
  * whichever fires first).
  *
  * The listener is invoked on whichever thread emitted the event, including
  * worker threads during processing, so implementations must be thread-safe
  * and return quickly. Exceptions thrown by the listener are caught and
  * logged; they never abort the build.
  *
  * The trait has a single abstract method, so Java callers may pass a
  * lambda directly:
  *
  * {{{
  * goatRodeo.withProgressListener((phase, current, total) -> {
  *     // ...
  * });
  * }}}
  *
  * @see [[GoatRodeoBuilder.withProgressListener]]
  */
trait ProgressListener {

  /** Receive a progress notification.
    *
    * For [[ProgressListener.Phase.Processing]], `current` is the number of
    * top-level artifacts processed so far and `total` is the number
    * discovered during the initial filesystem walk. For the other phases
    * both values are `0` — the call marks only the transition into that
    * phase.
    *
    * @param phase   the phase the build is currently in
    * @param current items processed so far (Processing only; 0 otherwise)
    * @param total   items in scope (Processing only; 0 otherwise)
    */
  def onProgress(phase: ProgressListener.Phase, current: Long, total: Long): Unit
}

object ProgressListener {

  /** The phases a Goat Rodeo run passes through, in order:
    * `Scanning` → `Processing` → `Writing` → `Done`. A run may transition
    * straight to `Done` on early failure without entering later phases.
    */
  enum Phase {
    case Scanning, Processing, Writing, Done
  }

  private val logger = Logger(getClass())

  /** Invoke a listener, swallowing and logging any exception. Internal
    * helper used by call sites in [[io.spicelabs.goatrodeo.omnibor.Builder]]
    * and [[Howdy]] so a misbehaving listener can never abort a multi-hour
    * build.
    */
  private[goatrodeo] def safeNotify(
      listener: Option[ProgressListener],
      phase: Phase,
      current: Long = 0L,
      total: Long = 0L
  ): Unit = {
    listener.foreach { l =>
      try l.onProgress(phase, current, total)
      catch {
        case t: Throwable =>
          logger.warn(s"ProgressListener threw on phase=$phase", t)
      }
    }
  }
}
