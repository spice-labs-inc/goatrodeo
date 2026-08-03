package io.spicelabs.goatrodeo.testsupport

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/** Shared helper for suites that assert on what was (or was not) logged.
  *
  * Attaching an appender to the *root* logger and raising the root level are
  * process-global mutations. Two suites do it, so rather than duplicating the
  * dance, capture through here — it adds two safeguards that make the pattern
  * safe even if test classes are ever run concurrently within one JVM:
  *
  *   1. captures are serialised on a single lock, so two suites cannot save and
  *      restore the root level in an interleaved order; and
  *   2. only events emitted by the capturing thread are returned, so log records
  *      from suites running in parallel cannot leak into a "nothing was logged"
  *      assertion.
  *
  * Consequence for callers: assertions must be about logging done on the calling
  * thread. A body that logs from worker threads it spawns will appear silent.
  */
object LogCapture {

  private val lock = new Object()

  /** Force SLF4J to bind to logback, retrying while it briefly reports a
    * `SubstituteLoggerFactory` during initialisation.
    */
  private def loggerContext(maxRetries: Int = 20): LoggerContext = {
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
    val appender = new ListAppender[ILoggingEvent]()
    appender.setContext(ctx)
    appender.start()
    root.addAppender(appender)
    val priorLevel = root.getLevel
    root.setLevel(Level.ALL)
    val thread = Thread.currentThread().getName
    try {
      val result = body()
      val mine = appender.list.asScala.toVector.filter(_.getThreadName == thread)
      (result, mine)
    } finally {
      root.setLevel(priorLevel)
      root.detachAppender(appender)
      appender.stop()
    }
  }
}
