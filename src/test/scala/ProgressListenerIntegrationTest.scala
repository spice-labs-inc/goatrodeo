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

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/** End-to-end check that a real `Howdy.run` invocation emits progress events
  * in the documented order (`Scanning → Processing(>=1) → Writing → Done`).
  * The payload is synthetic — just over a thousand tiny text files, sized to
  * clear the production-cadence throttle of 1,000 items between
  * `Processing` emissions.
  */
class ProgressListenerIntegrationTest extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(2, "minutes")

  private class Recorder extends ProgressListener {
    private val events = new ConcurrentLinkedQueue[(Phase, Long, Long)]()
    override def onProgress(phase: Phase, current: Long, total: Long): Unit =
      events.add((phase, current, total))
    def recorded: List[(Phase, Long, Long)] = events.iterator().asScala.toList
  }

  private def writeTinyFiles(dir: File, count: Int): Unit = {
    for (i <- 0 until count) {
      val f = new File(dir, f"f$i%05d.txt")
      Files.writeString(f.toPath, s"hello $i\n")
    }
  }

  test("Howdy.run emits Scanning → Processing(>=1) → Writing → Done") {
    val payloadDir = Files.createTempDirectory("gr-progress-payload").toFile
    val outputDir = Files.createTempDirectory("gr-progress-output").toFile
    try {
      // Just over the 1,000-item Processing throttle so at least one
      // Processing event is guaranteed to fire.
      writeTinyFiles(payloadDir, 1050)

      val recorder = new Recorder
      GoatRodeo
        .builder()
        .withPayload(payloadDir.getAbsolutePath)
        .withOutput(outputDir.getAbsolutePath)
        .withThreads(2)
        .withProgressListener(recorder)
        .run()

      val events = recorder.recorded
      val phases = events.map(_._1)

      // Boundary phases land in the documented order.
      assertEquals(phases.head, Phase.Scanning, s"first event should be Scanning, got: $phases")
      assertEquals(phases.last, Phase.Done, s"last event should be Done, got: $phases")
      assert(
        phases.contains(Phase.Writing),
        s"Writing event missing from sequence: $phases"
      )
      assert(
        phases.indexOf(Phase.Writing) < phases.lastIndexOf(Phase.Done),
        s"Writing must precede Done, got: $phases"
      )

      // At least one Processing event with sane counts.
      val processing = events.collect { case (Phase.Processing, c, t) => (c, t) }
      assert(
        processing.nonEmpty,
        s"expected at least one Processing event for a 1050-file payload, got: $phases"
      )
      processing.foreach { case (c, t) =>
        assert(c >= 1L, s"Processing.current must be >= 1, got $c")
        assert(t >= c, s"Processing.total ($t) must be >= current ($c)")
      }

      // Every Processing event must precede the Writing transition.
      val firstWriting = phases.indexOf(Phase.Writing)
      val lastProcessing = phases.lastIndexOf(Phase.Processing)
      assert(
        lastProcessing < firstWriting,
        s"Processing must not arrive after Writing, got: $phases"
      )
    } finally {
      deleteRecursively(payloadDir)
      deleteRecursively(outputDir)
    }
  }

  private def deleteRecursively(f: File): Unit = {
    if (f.isDirectory) {
      Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    }
    val _ = f.delete()
  }
}
