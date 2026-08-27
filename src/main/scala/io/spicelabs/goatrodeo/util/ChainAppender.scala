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

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** A logback appender that writes a hash-chained, tamper-evident log.
  *
  * Each emitted line is prefixed with the cumulative digest of the chain:
  *
  * digest_1 = SHA256(payload_1) digest_N = SHA256(digest_{N-1} || payload_N)
  *
  * and written as `<digest_N> <payload_N>`. The payload is the rendered log
  * event (its exact text is recovered verbatim by a verifier, so no pattern
  * knowledge is required to re-derive the digests). `AppenderBase` serializes
  * `append` calls, so this appender is the single point at which a total order
  * is established over the log lines even under multi-threaded logging.
  *
  * The current chain head is exposed via [[currentChainHead]] so the caller can
  * snapshot it into ADG files and a final checksum. Unkeyed by design: chaining
  * detects tampering; it does not resist an adversary who can rewrite the file
  * and the chain together.
  */
final class ChainAppender extends AppenderBase[ILoggingEvent] {

  private var file: Option[File] = None
  private var pattern: String =
    "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} -- %msg%n"
  private var layout: Option[PatternLayout] = None
  private var writer: Option[BufferedWriter] = None
  private var prevDigest: Array[Byte] = Array.emptyByteArray
  @volatile private var head: String = ""

  def setFile(f: File): Unit = file = Some(f)

  def setPattern(p: String): Unit = pattern = p

  /** The cumulative digest (hex) of the last line emitted, or "" if none. */
  def currentChainHead(): String = head

  override def start(): Unit = {
    file match {
      case None => addError("ChainAppender requires a file")
      case Some(f) =>
        val l = new PatternLayout()
        l.setContext(getContext())
        l.setPattern(pattern)
        l.start()
        layout = Some(l)
        writer = Some(
          new BufferedWriter(
            new OutputStreamWriter(
              new FileOutputStream(f, true),
              StandardCharsets.UTF_8
            )
          )
        )
        super.start()
    }
  }

  override def append(event: ILoggingEvent): Unit = {
    val l = layout.get
    val w = writer.get
    val payload = l.doLayout(event).stripLineEnd
    val bytes = payload.getBytes(StandardCharsets.UTF_8)
    val digest =
      if (prevDigest.isEmpty) Helpers.computeSHA256(bytes)
      else Helpers.computeSHA256(prevDigest ++ bytes)
    val hex = Helpers.toHex(digest)
    w.write(hex)
    w.write(' ')
    w.write(payload)
    w.write('\n')
    w.flush()
    prevDigest = digest
    head = hex
  }

  override def stop(): Unit = {
    writer.foreach { w =>
      w.flush()
      w.close()
    }
    super.stop()
  }
}
