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

import io.spicelabs.goatrodeo.ProgressListener.Phase

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

class ProgressListenerTest extends munit.FunSuite {

  /** A listener that records every event it receives. */
  private class Recorder extends ProgressListener {
    private val events = new ConcurrentLinkedQueue[(Phase, Long, Long)]()
    override def onProgress(phase: Phase, current: Long, total: Long): Unit =
      events.add((phase, current, total))
    def recorded: List[(Phase, Long, Long)] = events.iterator().asScala.toList
  }

  test("safeNotify forwards (phase, current, total) verbatim to the listener") {
    val rec = Recorder()
    ProgressListener.safeNotify(Some(rec), Phase.Processing, 47L, 1200L)
    assertEquals(rec.recorded, List((Phase.Processing, 47L, 1200L)))
  }

  test("safeNotify defaults current/total to 0 for phase-boundary calls") {
    val rec = Recorder()
    ProgressListener.safeNotify(Some(rec), Phase.Scanning)
    ProgressListener.safeNotify(Some(rec), Phase.Writing)
    ProgressListener.safeNotify(Some(rec), Phase.Done)
    assertEquals(
      rec.recorded,
      List(
        (Phase.Scanning, 0L, 0L),
        (Phase.Writing, 0L, 0L),
        (Phase.Done, 0L, 0L)
      )
    )
  }

  test("safeNotify with None is a silent no-op") {
    // Just shouldn't throw — no listener attached.
    ProgressListener.safeNotify(None, Phase.Processing, 1L, 10L)
  }

  test("safeNotify swallows exceptions thrown by the listener") {
    val throwing = new ProgressListener {
      override def onProgress(phase: Phase, current: Long, total: Long): Unit =
        throw new RuntimeException("listener exploded")
    }
    // Must not propagate the RuntimeException — a misbehaving listener should
    // never abort a multi-hour goat-rodeo run.
    ProgressListener.safeNotify(Some(throwing), Phase.Processing, 5L, 10L)
  }

  test("Phase enum exposes all four cases in declared order") {
    assertEquals(
      Phase.values.toList,
      List(Phase.Scanning, Phase.Processing, Phase.Writing, Phase.Done)
    )
  }
}
