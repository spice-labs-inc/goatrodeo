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

import java.io.File
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
      assert(grdFiles.nonEmpty, "writeEntries should produce a .grd file")
      val grdFile = grdFiles.head

      val originalBytes = Files.readAllBytes(grdFile.toPath)

      val magicBytes = new Array[Byte](4)
      System.arraycopy(originalBytes, 0, magicBytes, 0, 4)
      val magic = ByteBuffer.wrap(magicBytes).getInt

      assert(
        magic == GraphManager.Consts.DataFileMagicNumber,
        "GRD file should start with correct magic number"
      )

      val envLen = ((originalBytes(4) & 0xff) << 8) | (originalBytes(5) & 0xff)
      val entryLenAt = 4 + 2 + envLen
      // corrupt the ENTRY DATA (after the 4-byte length), preserving the
      // length so readNext attempts a CBOR decode and logs the WARN
      val corruptFrom = entryLenAt + 4
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
        assert(
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
      assert(grdFiles.nonEmpty, "writeEntries should produce a .grd file")
      val grdFile = grdFiles.head

      val channel = new FileInputStream(grdFile).getChannel()
      try {
        val walker = new GRDWalker(channel)
        val openResult = Try { walker.open() }
        assert(openResult.isSuccess, "GRD file should open successfully")

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
      assert(grdFiles.nonEmpty, "writeEntries should produce a .grd file")
      val grdFile = grdFiles.head

      val originalBytes = Files.readAllBytes(grdFile.toPath)

      val magicBytes = new Array[Byte](4)
      System.arraycopy(originalBytes, 0, magicBytes, 0, 4)
      val magic = ByteBuffer.wrap(magicBytes).getInt

      assert(
        magic == GraphManager.Consts.DataFileMagicNumber,
        "GRD file should start with correct magic number"
      )

      val envLen = ((originalBytes(4) & 0xff) << 8) | (originalBytes(5) & 0xff)
      val entryLenAt = 4 + 2 + envLen
      // corrupt the ENTRY DATA (after the 4-byte length), preserving the
      // length so readNext attempts a CBOR decode and logs the WARN
      val corruptFrom = entryLenAt + 4
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
        assert(
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

/** Phase 4 — GRD EOF semantics (spec §10; user decision 6; T13.x).
  *
  * WHAT: pins that ANY negative entry length is treated as end-of-file
  * (not just −1), and that a positive length exceeding the remaining
  * bytes is end-of-data (no giant allocation). Real entries are never
  * negative; truncated/foreign files read correctly instead of erroring.
  *
  * WHY: the GRD writer terminates the entry stream with a −1 marker then
  * a back-pointer long; reading past the marker can yield other negative
  * 4-byte values (e.g. −65536) which the old `== -1` check missed and
  * fed to `ByteBuffer.allocate(negative)` → crash. The fix treats any
  * negative length as EOF.
  *
  * LLM note: builds raw GRD-shaped byte streams directly (magic + empty
  * entry list + EOF marker + back-pointer tail) so each negative value is
  * exercised exactly. Real round-trips via `GraphManager.writeEntries`.
  */
class GrdEofSuite extends FunSuite {

  private def validGrdWithFirstEntryLength(entryLen: Int): Array[Byte] = {
    // Build a real GRD with one item, then patch the first entry-length field.
    val tempDir = Files.createTempDirectory("grdeof").toFile()
    try {
      val item = new Item(
        "gitoid:blob:sha256:abcdef0000000000000000000000000000000000000000000000000000000000",
        TreeSet(),
        Some(ItemMetaData.mimeType),
        Some(
          ItemMetaData(
            fileNames = TreeSet("x"),
            mimeType = TreeSet("application/octet-stream"),
            fileSize = 1,
            extra = TreeMap()
          )
        )
      )
      val (_, _) = GraphManager.writeEntries(tempDir, Vector(item).iterator)
      val grd = tempDir.listFiles().filter(_.getName.endsWith(".grd")).head
      val bytes = Files.readAllBytes(grd.toPath)
      // The first entry length sits right after the magic (4) + envelope
      // length (2) + envelope bytes. Find it: the envelope is the 2-byte
      // length + that many bytes; the entry length is the next 4 bytes.
      val envLen = ((bytes(4) & 0xff) << 8) | (bytes(5) & 0xff)
      val entryLenOffset = 4 + 2 + envLen
      val len = ByteBuffer.allocate(4).putInt(entryLen).array()
      System.arraycopy(len, 0, bytes, entryLenOffset, 4)
      bytes
    } finally Helpers.deleteDirectory(tempDir.toPath())
  }

  private def readFirst(bytes: Array[Byte]): Option[Item] = {
    val f = File.createTempFile("grd", ".grd")
    try {
      Files.write(f.toPath, bytes)
      val channel = new FileInputStream(f).getChannel()
      try {
        val walker = new GRDWalker(channel)
        assert(Try { walker.open() }.toOption.isDefined, "envelope must decode")
        walker.readNext()
      } finally channel.close()
    } finally { f.delete(); () }
  }

  test("T13.1 anyNegativeEntryLengthIsEof") {
    Seq(-1, -2, -3, -65536, Int.MinValue).foreach { neg =>
      assertEquals(readFirst(validGrdWithFirstEntryLength(neg)), None, s"negative length $neg must be EOF")
    }
  }

  test("T13.2 positiveLengthPastEofIsEndOfData") {
    // a length larger than the file's remaining bytes → end-of-data
    val bytes = validGrdWithFirstEntryLength(Int.MaxValue)
    assertEquals(readFirst(bytes), None)
  }

  test("T13.3 realEntriesAreNeverNegative — round-trip unchanged") {
    val tempDir = Files.createTempDirectory("grdeof").toFile()
    try {
      val id = "gitoid:blob:sha256:abcdef0000000000000000000000000000000000000000000000000000000000"
      val item = new Item(
        id,
        TreeSet(),
        Some(ItemMetaData.mimeType),
        Some(
          ItemMetaData(
            fileNames = TreeSet(id),
            mimeType = TreeSet("application/octet-stream"),
            fileSize = 4,
            extra = TreeMap()
          )
        )
      )
      val (_, _) = GraphManager.writeEntries(tempDir, Vector(item).iterator)
      val grd = tempDir.listFiles().filter(_.getName.endsWith(".grd")).head
      val channel = new FileInputStream(grd).getChannel()
      try {
        val walker = new GRDWalker(channel)
        walker.open()
        val got = walker.items().toVector
        assertEquals(got.size, 1)
        assertEquals(got.head.identifier, id)
      } finally channel.close()
    } finally Helpers.deleteDirectory(tempDir.toPath())
  }
}
