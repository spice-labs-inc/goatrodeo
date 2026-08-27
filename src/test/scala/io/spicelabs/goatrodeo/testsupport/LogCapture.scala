package io.spicelabs.goatrodeo.testsupport

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicReference

/** Shared helper for suites that assert on what was (or was not) logged.
  *
  * Attaching an appender to the *root* logger and raising the root level are
  * process-global mutations. Two suites do it, so rather than duplicating the
  * dance, capture through here — it adds two safeguards that make the pattern
  * safe even if test classes are ever run concurrently within one JVM:
  *
  *   1. captures are serialised on a single lock, so two suites cannot save and
  *      restore the root level in an interleaved order; and 2. only events
  *      emitted by the capturing thread are returned, so log records from
  *      suites running in parallel cannot leak into a "nothing was logged"
  *      assertion.
  *
  * The capture appender stores events in an immutable `Vector` wrapped in an
  * `AtomicReference`, updated with `getAndUpdate(_ :+ event)`. Because the
  * backing store is immutable and each append is an atomic swap, a thread that
  * logs through the root logger while another thread reads the captured events
  * cannot cause a `ConcurrentModificationException` (no mutable Java collection
  * is ever shared).
  *
  * Consequence for callers: assertions must be about logging done on the
  * calling thread. A body that logs from worker threads it spawns will appear
  * silent.
  */
object LogCapture {

  private val lock = new Object()

  /** A logback appender that captures events into a persistent `Vector` inside
    * an `AtomicReference`. Appends are atomic functional updates, so the event
    * list is safe to read concurrently from another thread.
    */
  final class VectorCaptureAppender extends AppenderBase[ILoggingEvent] {
    private val events: AtomicReference[Vector[ILoggingEvent]] =
      new AtomicReference(Vector())

    /** Snapshot of all events captured so far. */
    def captured(): Vector[ILoggingEvent] = events.get()

    override def append(event: ILoggingEvent): Unit =
      events.getAndUpdate(_ :+ event)
  }

  /** Force SLF4J to bind to logback, retrying while it briefly reports a
    * `SubstituteLoggerFactory` during initialisation. The retry window is
    * deliberately generous (30 s): under a heavily parallel test run the
    * logback binding can take well over a second, and failing here fails the
    * capture spuriously.
    */
  private def loggerContext(maxRetries: Int = 600): LoggerContext = {
    var attempts = 0
    while (attempts < maxRetries) {
      LoggerFactory.getILoggerFactory match {
        case c: LoggerContext => return c
        case _                =>
      }
      Thread.sleep(50)
      attempts += 1
    }
    throw new IllegalStateException(
      "Logback LoggerContext not available after initialization"
    )
  }

  def apply[T](body: () => T): (T, Vector[ILoggingEvent]) = lock.synchronized {
    LoggerFactory.getLogger(getClass)
    val ctx = loggerContext()
    val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val appender = new VectorCaptureAppender()
    appender.setContext(ctx)
    appender.start()
    root.addAppender(appender)
    val priorLevel = root.getLevel
    root.setLevel(Level.ALL)
    val thread = Thread.currentThread().getName
    try {
      val result = body()
      val mine = appender.captured().filter(_.getThreadName == thread)
      (result, mine)
    } finally {
      root.setLevel(priorLevel)
      root.detachAppender(appender)
      appender.stop()
    }
  }
}
