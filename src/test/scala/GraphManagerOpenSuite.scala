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

import io.spicelabs.goatrodeo.omnibor.GRDWalker
import io.spicelabs.goatrodeo.omnibor.GraphManager
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.util.Helpers
import munit.FunSuite

import java.io.FileInputStream
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Phase 0 (0.7) — GRDWalker.open returns Failure (not thrown exception) for
  * wrong magic number.
  *
  * ## What this tests
  *
  * `GRDWalker.open()` returns `Try[DataFileEnvelope]`. When the file has an
  * incorrect magic number, the result must be a `Failure` — not a thrown
  * exception that propagates out of `open()`. The caller can pattern-match on
  * the Try to handle the error gracefully.
  *
  * ## Why this matters
  *
  * Before the Phase 0 remediation, `open()` might have thrown directly in some
  * code paths, forcing callers to use try/catch. Returning `Failure` is the
  * idiomatic Scala approach and allows composable error handling.
  *
  * ## Requirement trace
  *
  * Phase 0 item 0.7: GRDWalker.open returns Failure for wrong magic number
  * rather than throwing.
  *
  * ## LLM-friendly summary
  *
  * | Test        | File content | Expected                       |
  * |:------------|:-------------|:-------------------------------|
  * | wrong magic | 0xDEADBEEF   | Failure (not thrown exception) |
  */
class GraphManagerOpenSuite extends FunSuite {

  test("GRDWalker - open returns Failure for wrong magic number") {

    /** What: Creates a temporary file with 4 bytes that do NOT match the GRD
      * magic number (0x00BE1100), opens it via FileChannel, and calls
      * GRDWalker.open(). Why: The open method must return a Try.Failure rather
      * than throwing an exception, allowing the caller to handle format errors
      * gracefully. Requirement: Phase 0 §0.7 — open returns Failure, does not
      * throw.
      */
    val tempFile = Files.createTempFile("wrong-magic-", ".grd").toFile()
    try {
      Files.write(
        tempFile.toPath(),
        Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)
      )

      val channel = new FileInputStream(tempFile).getChannel()
      try {
        val walker = new GRDWalker(channel)
        val result = Try { walker.open() }

        assert(
          result.isFailure,
          "open() should return Failure for file with wrong magic number"
        )
        assert(
          result.asInstanceOf[scala.util.Failure[?]].exception != null,
          "Failure should contain an exception describing the problem"
        )
      } finally {
        channel.close()
      }
    } finally {
      tempFile.delete()
    }
  }

  test("GRDWalker - open Failure message mentions incorrect magic number") {
    val tempFile = Files.createTempFile("wrong-magic-msg-", ".grd").toFile()
    try {
      Files.write(tempFile.toPath(), Array[Byte](0, 0, 0, 1))

      val channel = new FileInputStream(tempFile).getChannel()
      try {
        val walker = new GRDWalker(channel)
        val result = Try { walker.open() }

        assert(result.isFailure)
        val ex = result.asInstanceOf[scala.util.Failure[?]].exception
        assert(
          ex.getMessage.contains("magic") || ex.getMessage.contains("Magic"),
          s"Failure message should mention magic number, got: ${ex.getMessage}"
        )
      } finally {
        channel.close()
      }
    } finally {
      tempFile.delete()
    }
  }

  test("GRDWalker - open returns Failure for short read (truncated file)") {

    /** What: Creates a file with correct magic number but truncated (no
      * envelope bytes after the magic number). Why: Short reads are expected
      * failures for corrupt/truncated files. Must return Failure, not throw.
      * Requirement: Phase 0 §0.7 — open returns Failure for short read.
      */
    val magic = Array[Byte](0x00.toByte, 0xbe.toByte, 0x11.toByte, 0x00.toByte)
    val tempFile = Files.createTempFile("short-read-", ".grd").toFile()
    try {
      Files.write(tempFile.toPath(), magic)

      val channel = new FileInputStream(tempFile).getChannel()
      try {
        val walker = new GRDWalker(channel)
        val result = Try { walker.open() }

        assert(
          result.isFailure,
          "open() should return Failure for truncated file after correct magic"
        )
      } finally {
        channel.close()
      }
    } finally {
      tempFile.delete()
    }
  }

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

  test("GRDWalker - open returns Success for valid GRD file") {

    /** What: Creates a valid GRD file using GraphManager.writeEntries, opens it
      * via FileChannel, and calls GRDWalker.open(). The result must be a
      * Try.Success containing a DataFileEnvelope. Why: The existing tests only
      * verify Failure cases (wrong magic, truncated file). The positive path —
      * opening a valid GRD file — must also be tested to confirm the full
      * open() contract works end-to-end with real data written by the same
      * codebase. Requirement: Phase 0 §0.7 — open returns Success for valid
      * file.
      */
    val tempDir = Files.createTempDirectory("valid-grd-open").toFile()
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
        val result = Try { walker.open() }

        assert(
          result.isSuccess,
          "open() should return Success for a valid GRD file"
        )
        val envelope = result.get
        assert(
          envelope != null,
          "Success value (DataFileEnvelope) must not be null"
        )
      } finally {
        channel.close()
      }
    } finally {
      Helpers.deleteDirectory(tempDir.toPath())
    }
  }
}
