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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import io.spicelabs.goatrodeo.omnibor.GRDWalker
import io.spicelabs.goatrodeo.omnibor.GraphManager
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.testsupport.LogCapture
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite

import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Phase 0 (0.8) — GRDWalker.readNext returns None for corrupt CBOR entry.
  *
  * ## What this tests
  *
  * After opening a valid GRD file, if a CBOR entry is corrupt (e.g., bytes that
  * do not decode to a valid Item), `readNext()` must return `None` instead of
  * throwing an exception or crashing the process.
  *
  * ## Why this matters
  *
  * GRD files may be partially corrupted by disk errors, truncated writes, or
  * version mismatches. The walker must tolerate corrupt entries gracefully,
  * allowing the rest of the file to be processed.
  *
  * ## Requirement trace
  *
  * Phase 0 item 0.8: GRDWalker.readNext returns None for corrupt CBOR entry
  * rather than throwing.
  *
  * ## LLM-friendly summary
  *
  * | Test               | Setup                                | Expected              |
  * |:-------------------|:-------------------------------------|:----------------------|
  * | corrupt CBOR entry | write valid GRD, corrupt entry bytes | readNext returns None |
  */
class GraphManagerReadNextSuite extends FunSuite {

  private def createTestItem(id: String): Item = {
    Item(
      id,
      TreeSet(),
      Some(ItemMetaData.mimeType),
      Some(
        ItemMetaData(
          fileNames = TreeSet(id),
          mimeType = TreeSet("application/octet-stream"),
          fileSize = 100,
          extra = TreeMap()
        )
      )
    )
  }

  test("GRDWalker - readNext returns None for corrupt CBOR entry") {

    /** What: Creates a valid GRD file with one item, then corrupts the CBOR
      * entry bytes (overwrites with garbage) while keeping the header intact.
      * Opens the file with GRDWalker and calls readNext(). Why: Corrupt CBOR
      * entries must be handled gracefully — returning None rather than
      * propagating a CborDecodingException. Requirement: Phase 0 §0.8 —
      * readNext returns None for corrupt CBOR.
      */
    val tempDir = Files.createTempDirectory("corruptcbor").toFile()
    try {
      val item = createTestItem(
        "gitoid:blob:sha256:aaaaaaaabbbbbbbbccccccccddddddddeeeeeeeeffffffff0000000011111111"
      )
      val (_, _) = GraphManager.writeEntries(tempDir, Vector(item).iterator)

      val grdFiles = tempDir.listFiles().filter(_.getName.endsWith(".grd"))
      assume(grdFiles.nonEmpty, "writeEntries should produce a .grd file")
      val grdFile = grdFiles.head

      val originalBytes = Files.readAllBytes(grdFile.toPath)

      val magicBytes = new Array[Byte](4)
      System.arraycopy(originalBytes, 0, magicBytes, 0, 4)
      val magic = ByteBuffer.wrap(magicBytes).getInt

      assume(
        magic == GraphManager.Consts.DataFileMagicNumber,
        "GRD file should start with correct magic number"
      )

      val corruptFrom = math.min(20, originalBytes.length - 4)
      for (
        i <- corruptFrom until math.min(corruptFrom + 16, originalBytes.length)
      ) {
        originalBytes(i) = 0xde.toByte
      }
      Files.write(grdFile.toPath, originalBytes)

      val channel = new FileInputStream(grdFile).getChannel()
      try {
        val walker = new GRDWalker(channel)
        val openResult = Try { walker.open() }
        assume(
          openResult.isSuccess,
          "Header should still be valid after corruption"
        )

        val nextItem = walker.readNext()
        assert(
          nextItem.isEmpty,
          "readNext should return None for corrupt CBOR entry, not throw"
        )
      } finally {
        channel.close()
      }
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  test("GRDWalker - readNext returns Some for valid entry") {

    /** What: Creates a valid GRD file with one item, opens it, and reads the
      * entry. Should return Some(item). Why: Normal operation must still work
      * after the pattern-match change. Requirement: Phase 0 §0.8 — readNext
      * returns Some for valid entry.
      */
    val tempDir = Files.createTempDirectory("validcbor").toFile()
    try {
      val item = createTestItem(
        "gitoid:blob:sha256:aaaaaaaabbbbbbbbccccccccddddddddeeeeeeeeffffffff0000000011111111"
      )
      val (_, _) = GraphManager.writeEntries(tempDir, Vector(item).iterator)

      val grdFiles = tempDir.listFiles().filter(_.getName.endsWith(".grd"))
      assume(grdFiles.nonEmpty, "writeEntries should produce a .grd file")
      val grdFile = grdFiles.head

      val channel = new FileInputStream(grdFile).getChannel()
      try {
        val walker = new GRDWalker(channel)
        val openResult = Try { walker.open() }
        assume(openResult.isSuccess, "GRD file should open successfully")

        val nextItem = walker.readNext()
        assert(
          nextItem.isDefined,
          "readNext should return Some for valid CBOR entry"
        )
      } finally {
        channel.close()
      }
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }

  // Root-logger capture, shared with PrivateKeyLogCaptureTests, which ran an
  // identical copy of this dance. See LogCapture.
  private def runWithCapture[T](
      body: () => T
  ): (T, Vector[ILoggingEvent]) = LogCapture(body)

  test("GRDWalker - readNext logs WARN on corrupt CBOR entry") {

    /** What: Creates a valid GRD file with one item, corrupts the CBOR entry
      * bytes (overwrites with garbage) while keeping the header intact. Opens
      * the file with GRDWalker and calls readNext() while capturing log output.
      * Verifies a WARN-level log message from the GRDWalker logger is emitted.
      * Why: When a CBOR entry is corrupt, readNext returns None silently.
      * Operators must have visibility into when and why entries are skipped.
      * The WARN log provides this visibility, indicating the position and error
      * message of the corrupt entry. Requirement: Phase 0 §0.8 — readNext logs
      * warning on corrupt entry.
      */
    val tempDir = Files.createTempDirectory("corruptcbor-warn").toFile()
    try {
      val item = createTestItem(
        "gitoid:blob:sha256:aaaaaaaabbbbbbbbccccccccddddddddeeeeeeeeffffffff0000000011111111"
      )
      val (_, _) = GraphManager.writeEntries(tempDir, Vector(item).iterator)

      val grdFiles = tempDir.listFiles().filter(_.getName.endsWith(".grd"))
      assume(grdFiles.nonEmpty, "writeEntries should produce a .grd file")
      val grdFile = grdFiles.head

      val originalBytes = Files.readAllBytes(grdFile.toPath)

      val magicBytes = new Array[Byte](4)
      System.arraycopy(originalBytes, 0, magicBytes, 0, 4)
      val magic = ByteBuffer.wrap(magicBytes).getInt

      assume(
        magic == GraphManager.Consts.DataFileMagicNumber,
        "GRD file should start with correct magic number"
      )

      val corruptFrom = math.min(20, originalBytes.length - 4)
      for (
        i <- corruptFrom until math.min(corruptFrom + 16, originalBytes.length)
      ) {
        originalBytes(i) = 0xde.toByte
      }
      Files.write(grdFile.toPath, originalBytes)

      val channel = new FileInputStream(grdFile).getChannel()
      try {
        val walker = new GRDWalker(channel)
        val openResult = Try { walker.open() }
        assume(
          openResult.isSuccess,
          "Header should still be valid after corruption"
        )

        val (nextItem, events) = runWithCapture(() => walker.readNext())
        assert(
          nextItem.isEmpty,
          "readNext should return None for corrupt CBOR entry"
        )

        val warnEvents = events.filter { e =>
          e.getLevel == Level.WARN &&
          Option(e.getLoggerName).exists(_.contains("GRDWalker"))
        }
        assert(
          warnEvents.nonEmpty,
          "At least one WARN log from GRDWalker logger expected for corrupt entry"
        )
        val msg = warnEvents.head.getFormattedMessage
        assert(
          msg.contains("Corrupt") || msg.contains("corrupt") || msg.contains(
            "CBOR"
          ),
          s"WARN message should reference corrupt/CBOR entry; got: $msg"
        )
      } finally {
        channel.close()
      }
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }
}
