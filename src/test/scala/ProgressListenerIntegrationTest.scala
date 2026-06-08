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

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** End-to-end check that a real `Howdy.run` delivers progress events to a
  * caller-supplied listener: at least one event, strictly increasing `current`,
  * and `current <= total` invariant maintained across deliveries. The payload
  * is synthetic — just over a thousand tiny text files, sized to clear the
  * production-cadence throttle of 1,000 items between emissions.
  */
class ProgressListenerIntegrationTest extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(2, "minutes")

  private class Recorder extends ProgressListener {
    private val events = new ConcurrentLinkedQueue[(Long, Long)]()
    override def onProgress(current: Long, total: Long): Unit =
      events.add((current, total))
    def recorded: List[(Long, Long)] = events.iterator().asScala.toList
  }

  private def writeTinyFiles(dir: File, count: Int): Unit = {
    for (i <- 0 until count) {
      val f = new File(dir, f"f$i%05d.txt")
      Files.writeString(f.toPath, s"hello $i\n")
    }
  }

  test("Howdy.run delivers monotonic progress to a ProgressListener") {
    val payloadDir = Files.createTempDirectory("gr-progress-payload").toFile
    val outputDir = Files.createTempDirectory("gr-progress-output").toFile
    try {
      // Just over the 1,000-item throttle so at least one event is guaranteed.
      writeTinyFiles(payloadDir, 1050)

      val recorder = new Recorder
      GoatRodeo
        .builder()
        .withPayload(payloadDir.getAbsolutePath)
        .withOutput(outputDir.getAbsolutePath)
        .withThreads(4)
        .withProgressListener(recorder)
        .run()

      val events = recorder.recorded
      assert(
        events.nonEmpty,
        "expected at least one progress event for a 1050-file payload"
      )

      val currents = events.map(_._1)
      assertEquals(
        currents,
        currents.sorted.distinct,
        s"delivered current values must be strictly increasing; got: $currents"
      )
      events.foreach { case (c, t) =>
        assert(c >= 1L, s"current must be >= 1, got $c")
        assert(t >= c, s"total ($t) must be >= current ($c)")
      }
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
