/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.{Item, ItemMetaData, SingleMarker}
import io.spicelabs.goatrodeo.util.FileWrapper
import munit.FunSuite

import java.io.File
import scala.collection.immutable.{TreeMap, TreeSet}

/** Phase 7 plan §"Acceptance":
  *
  * > No fixture in this phase triggers a password prompt, a decryption
  * > attempt, or a log message about encryption status beyond what's
  * > in the metadata. **Verify via log capture in tests.**
  *
  * This suite installs a Logback `ListAppender` on the root logger,
  * runs the full Phase-7 corpus through `classifyAndParse` +
  * `getPurls` + `getMetadata`, and asserts:
  *
  *   1. NO log record originates from
  *      `io.spicelabs.goatrodeo.omnibor.strategies.Certificates` —
  *      there are zero log calls in the strategy code (verified by
  *      static grep, but this dynamic test catches a future
  *      regression where someone adds a log statement on a
  *      private-key path).
  *   2. NO log record at any level mentions "decrypt", "password",
  *      "passphrase", or "BadPadding" — proving the encrypted-path
  *      parsers never invoked any decryption call.
  *
  * If either property fails, the relevant log records are dumped so
  * the failing call can be located.
  */
class PrivateKeyLogCaptureTests extends FunSuite {

  import ch.qos.logback.classic.{Level, Logger as LbLogger, LoggerContext}
  import ch.qos.logback.classic.spi.ILoggingEvent
  import ch.qos.logback.core.read.ListAppender
  import org.slf4j.LoggerFactory
  import scala.jdk.CollectionConverters.*

  private def runWithCapture[T](body: () => T): (T, Vector[ILoggingEvent]) = {
    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val appender = new ListAppender[ILoggingEvent]()
    appender.setContext(ctx)
    appender.start()
    root.addAppender(appender)
    val priorLevel = root.getLevel
    root.setLevel(Level.ALL)
    try {
      val result = body()
      (result, appender.list.asScala.toVector)
    } finally {
      root.setLevel(priorLevel)
      root.detachAppender(appender)
      appender.stop()
    }
  }

  private def stubItem(): Item = Item(
    identifier = "gitoid:blob:sha256:phase7-log-capture-stub",
    connections = TreeSet.empty,
    bodyMimeType = Some(ItemMetaData.mimeType),
    body = Some(
      ItemMetaData(
        fileNames = TreeSet.empty,
        mimeType = TreeSet.empty,
        fileSize = 0L,
        extra = TreeMap.empty,
      )
    ),
  )

  private def runEveryPhase7Fixture(): Unit = {
    val root = java.nio.file.Paths.get("test_data/certificates/private-keys")
    if (!java.nio.file.Files.exists(root)) return
    val files = java.nio.file.Files.walk(root).iterator().asScala
      .filter(p => java.nio.file.Files.isRegularFile(p))
      .filter(p => !p.toString.endsWith(".expected.json"))
      .filter(p => !p.toString.endsWith("/generate.sh"))
      .filter(p => !p.toString.endsWith("/SOURCES.md"))
      .toVector
    files.foreach { p =>
      val w = FileWrapper(new File(p.toString), p.toString, None)
      Certificates.classifyAndParse(w).foreach { claim =>
        val state = new CertificatesState(w, Some(claim))
        val _ = state.getPurls(w, stubItem(), SingleMarker())
        val _ = state.getMetadata(w, stubItem(), SingleMarker())
      }
    }
  }

  test("[HARD RULE Phase 7] no log records from Certificates strategy across the entire Phase-7 corpus") {
    val (_, events) = runWithCapture(() => runEveryPhase7Fixture())
    val fromCert = events.filter { e =>
      Option(e.getLoggerName).exists(
        _.startsWith("io.spicelabs.goatrodeo.omnibor.strategies.Certificates"))
    }
    if (fromCert.nonEmpty) {
      val sample = fromCert.take(5).map(e =>
        s"  [${e.getLevel}] ${e.getLoggerName}: ${e.getFormattedMessage}").mkString("\n")
      fail(
        s"Phase 7 hard rule: zero log records from Certificates expected; " +
        s"got ${fromCert.length}:\n$sample")
    }
  }

  test("[HARD RULE Phase 7] no log record mentions decrypt/password/passphrase/BadPadding (decryption-attempt smoke test)") {
    val (_, events) = runWithCapture(() => runEveryPhase7Fixture())
    val suspicious = events.filter { e =>
      val msg = Option(e.getFormattedMessage).getOrElse("").toLowerCase
      msg.contains("decrypt") || msg.contains("password") ||
        msg.contains("passphrase") || msg.contains("badpadding")
    }
    if (suspicious.nonEmpty) {
      val sample = suspicious.take(5).map(e =>
        s"  [${e.getLevel}] ${e.getLoggerName}: ${e.getFormattedMessage}").mkString("\n")
      fail(
        s"Phase 7 hard rule: no log record may suggest a decryption " +
        s"attempt; got ${suspicious.length}:\n$sample")
    }
  }
}
