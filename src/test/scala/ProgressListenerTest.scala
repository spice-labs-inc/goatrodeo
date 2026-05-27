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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._

class ProgressListenerTest extends munit.FunSuite {

  /** A listener that records every event it receives. Thread-safe; events
    * land in arrival order because the underlying queue is FIFO.
    */
  private class Recorder extends ProgressListener {
    private val events = new ConcurrentLinkedQueue[(Long, Long)]()
    override def onProgress(current: Long, total: Long): Unit =
      events.add((current, total))
    def recorded: List[(Long, Long)] = events.iterator().asScala.toList
  }

  test("notifier forwards (current, total) to the listener") {
    val rec = new Recorder
    val n = ProgressListener.notifier(Some(rec))
    n.notify(47L, 1200L)
    assertEquals(rec.recorded, List((47L, 1200L)))
  }

  test("notifier with no listener is a silent no-op") {
    val n = ProgressListener.notifier(None)
    n.notify(1L, 10L)
  }

  test("notifier swallows exceptions thrown by the listener") {
    val throwing = new ProgressListener {
      override def onProgress(current: Long, total: Long): Unit =
        throw new RuntimeException("listener exploded")
    }
    val n = ProgressListener.notifier(Some(throwing))
    // Must not propagate — a misbehaving listener should never abort a build.
    n.notify(5L, 10L)
  }

  test("notifier drops non-monotonic (backwards or equal) current") {
    val rec = new Recorder
    val n = ProgressListener.notifier(Some(rec))
    n.notify(100L, 1000L)
    n.notify(50L, 1000L) // backwards — dropped
    n.notify(100L, 1000L) // equal — dropped
    n.notify(101L, 1000L) // forward — delivered
    assertEquals(rec.recorded, List((100L, 1000L), (101L, 1000L)))
  }

  test("notifiers are independent (per-run state, no JVM-global)") {
    // Two notifiers track their own monotonic counters, so a second run
    // starts fresh even when the first ran in the same JVM.
    val recA = new Recorder
    val recB = new Recorder
    val nA = ProgressListener.notifier(Some(recA))
    val nB = ProgressListener.notifier(Some(recB))
    nA.notify(500L, 1000L)
    nB.notify(10L, 1000L) // would be dropped if state were shared with nA
    assertEquals(recA.recorded, List((500L, 1000L)))
    assertEquals(recB.recorded, List((10L, 1000L)))
  }

  test("notifier under concurrent fire delivers strictly-monotonic current") {
    // Each worker thread fires a unique sequence of current values; with N
    // threads racing, the listener must see a strictly increasing sequence
    // (some ticks are dropped by design when a later thread CAS-wins).
    val rec = new Recorder
    val n = ProgressListener.notifier(Some(rec))
    val threadCount = 16
    val itemsPerThread = 200
    val start = new CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(threadCount)
    try {
      val futures = (0 until threadCount).map { t =>
        pool.submit(new Runnable {
          def run(): Unit = {
            start.await()
            for (i <- 0 until itemsPerThread) {
              // Interleave values across threads so races are likely.
              val current = (i.toLong * threadCount) + t
              n.notify(current, 100_000L)
            }
          }
        })
      }
      start.countDown()
      futures.foreach(_.get())
    } finally {
      pool.shutdown()
      val _ = pool.awaitTermination(10, TimeUnit.SECONDS)
    }

    val delivered = rec.recorded.map(_._1)
    assert(delivered.nonEmpty, "expected some deliveries under concurrent fire")
    assertEquals(
      delivered,
      delivered.sorted.distinct,
      s"deliveries must be strictly increasing; got: $delivered"
    )
  }
}
