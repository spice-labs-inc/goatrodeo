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

package io.spicelabs.goatrodeo.testsupport

import io.bullet.borer.Cbor
import io.spicelabs.goatrodeo.envelopes.ClusterFileEnvelope
import io.spicelabs.goatrodeo.omnibor.GRDWalker
import io.spicelabs.goatrodeo.omnibor.GraphManager
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.util.Helpers

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Comparator
import scala.util.Try

/** Shared helpers for suites that assert on what a Goat Rodeo run writes.
  *
  * A run's output directory is a public interface: the Spice CLI, BigTent,
  * Surveyor's integration runner and end users all read these files. These
  * helpers exist so every suite that checks that interface agrees on what the
  * files are called, how a cluster file is decoded, and how a batch boundary
  * relocates output — rather than each suite re-deriving the rules.
  *
  * ==Why the naming helpers compute the rule themselves==
  *
  * A test that asserts `name ==
  * Helpers.toHex(Helpers.byteArrayToLong63Bits(sha256(file)))` is a tautology:
  * the expectation is the production expression, so a change to `Helpers` moves
  * both sides and the test still passes. [[expectedHashName]] therefore
  * performs the bit arithmetic itself, so the test fails if the masking
  * changes.
  *
  * ==Why batch directories are discovered rather than named==
  *
  * A run that exceeds `maxRecords` splits into batches, and each batch writes
  * its own directory. The project position is that the location does not matter
  * as long as nothing is left behind, so [[batchDirectories]] accepts a batch
  * directory as a sibling or a child of the output directory and every
  * assertion reads through it. What matters — that the union of the batches is
  * the whole graph — is asserted by the tests, not by the helper.
  */
object RunOutputs {

  /** Temporary-file name prefixes `GraphManager` uses before renaming an
    * artifact into place. A file with one of these prefixes in an output
    * directory means a rename did not happen: the temporary file survived and
    * the final artifact is missing.
    */
  val temporaryNamePrefixes: Vector[String] =
    Vector("goat_rodeo_data_", "goat_rodeo_cluster_")

  /** The final, content-addressed name for a file with the given digest.
    *
    * The rule: take the 8 most significant bytes of the SHA-256, clear the top
    * bit, and render the resulting 8 bytes big-endian as 16 hex characters.
    * Clearing the top bit is deliberate (`Helpers.byteArrayToLong63Bits`) so
    * the value is a non-negative `Long`; the consequence is that the first hex
    * character is always `0`-`7`.
    *
    * @param digest
    *   the file's SHA-256
    * @return
    *   the 16-hex-character name the file should have
    */
  def expectedHashName(digest: Array[Byte]): String = {
    require(digest.length >= 8, "a SHA-256 digest has at least 8 bytes")
    val sb = new StringBuilder(16)
    var i = 0
    while (i < 8) {
      // The first byte is masked; the rest are taken verbatim. This is the
      // whole of the rule, written out so the test does not borrow the
      // implementation it is checking.
      val b = if (i == 0) (digest(0) & 0x7f).toByte else digest(i)
      sb.append(Character.forDigit((b >> 4) & 0xf, 16))
      sb.append(Character.forDigit(b & 0xf, 16))
      i += 1
    }
    sb.toString
  }

  /** SHA-256 of a file, read in bounded chunks so a large artifact is never
    * held in memory whole.
    *
    * @param file
    *   the file to hash
    * @return
    *   the 32-byte digest
    */
  def sha256(file: File): Array[Byte] = {
    val md = MessageDigest.getInstance("SHA-256")
    val in = new FileInputStream(file)
    try {
      val buf = new Array[Byte](64 * 1024)
      var read = in.read(buf)
      while (read > 0) {
        md.update(buf, 0, read)
        read = in.read(buf)
      }
    } finally in.close()
    md.digest()
  }

  /** Decode a `.grc` file's `ClusterFileEnvelope`.
    *
    * The cluster file layout is magic (4 bytes) + envelope length (2 bytes) +
    * CBOR envelope. The magic is asserted here so a caller that hands over a
    * non-cluster file gets a clear failure rather than a CBOR decode error.
    *
    * @param file
    *   the `.grc` file
    * @return
    *   the decoded envelope
    */
  def readClusterEnvelope(file: File): ClusterFileEnvelope = {
    val dfp = new FileInputStream(file).getChannel()
    try {
      val magic = Helpers.readInt(dfp)
      require(
        magic == GraphManager.Consts.ClusterFileMagicNumber,
        s"${file.getName} is not a cluster file: magic 0x${magic.toHexString}"
      )
      val len = Helpers.readShort(dfp)
      val bytes = new Array[Byte](len)
      var off = 0
      while (off < len) {
        val r = dfp.read(ByteBuffer.wrap(bytes, off, len - off))
        if (r < 0) throw new Exception("short read on cluster envelope")
        off += r
      }
      Cbor.decode(bytes).to[ClusterFileEnvelope].value
    } finally {
      dfp.close()
    }
  }

  /** Every directory that holds a batch's output: the output directory itself,
    * plus any sibling or child that contains a `.grc`.
    *
    * A batch directory is identified by containing a cluster file, because that
    * is what makes it a complete, self-describing batch. The location is
    * deliberately not asserted: the project position is that it does not matter
    * where the batch directory is, only that nothing is left behind.
    *
    * @param output
    *   the run's output directory
    * @return
    *   the batch directories, the output directory first
    */
  def batchDirectories(output: File): Vector[File] = {
    val parent = Option(output.getParentFile)
    val siblings = parent
      .flatMap(p => Option(p.listFiles()))
      .getOrElse(Array.empty[File])
      .filter(f =>
        f.isDirectory &&
          f.getName.startsWith(output.getName + "_")
      )
      .toVector
    val children = Option(output.listFiles())
      .getOrElse(Array.empty[File])
      .filter(f => f.isDirectory && hasClusterFile(f))
      .toVector
    (output +: (siblings ++ children))
      .filter(hasClusterFile)
      .distinct
  }

  /** Whether a directory holds a `.grc`, i.e. whether it is a batch directory.
    *
    * @param dir
    *   the directory to test
    * @return
    *   true when at least one `.grc` is present
    */
  def hasClusterFile(dir: File): Boolean =
    Option(dir.listFiles())
      .getOrElse(Array.empty[File])
      .exists(f => f.isFile && f.getName.endsWith(".grc"))

  /** Files in a directory with the given suffix.
    *
    * @param dir
    *   the directory
    * @param suffix
    *   the file-name suffix to match
    * @return
    *   the matching files, sorted by name
    */
  def filesWithSuffix(dir: File, suffix: String): Vector[File] =
    Option(dir.listFiles())
      .getOrElse(Array.empty[File])
      .filter(f => f.isFile && f.getName.endsWith(suffix))
      .sortBy(_.getName)
      .toVector

  /** Every `items_*.json` dump in a directory, sorted by name.
    *
    * @param jsonDir
    *   the directory passed to `emitJsonDir`
    * @return
    *   the dump files
    */
  def dumpFiles(jsonDir: File): Vector[File] =
    Option(jsonDir.listFiles())
      .getOrElse(Array.empty[File])
      .filter(f => f.isFile && f.getName.startsWith("items_"))
      .sortBy(_.getName)
      .toVector

  /** Read every item from every `.grd` in a directory.
    *
    * @param dir
    *   the batch directory
    * @return
    *   the items the directory's data files hold
    */
  def readItemsFromGrd(dir: File): Vector[Item] = {
    val items = Vector.newBuilder[Item]
    filesWithSuffix(dir, ".grd").foreach { grd =>
      val channel = new FileInputStream(grd).getChannel
      try {
        val walker = new GRDWalker(channel)
        walker.open()
        items ++= walker.items()
      } finally channel.close()
    }
    items.result()
  }

  /** Write a UTF-8 text file, creating parent directories.
    *
    * @param file
    *   the file to write
    * @param content
    *   the content
    */
  def writeText(file: File, content: String): Unit = {
    Option(file.getParentFile).foreach(_.mkdirs())
    val out = new FileOutputStream(file)
    try out.write(content.getBytes("UTF-8"))
    finally out.close()
  }

  /** Delete a directory tree, tolerating files that cannot be deleted.
    *
    * Output directories hold content-addressed files created by the run under
    * test, and a failure part-way through must not mask the assertion that
    * failed, so deletion is best-effort.
    *
    * @param dir
    *   the directory to remove
    */
  def deleteTree(dir: File): Unit = {
    if (dir != null && dir.exists()) {
      Try {
        Files
          .walk(dir.toPath)
          .sorted(Comparator.reverseOrder())
          .forEach(p => Files.deleteIfExists(p))
        ()
      }
      ()
    }
  }

  /** A run's output directory and its JSON dump directory, plus a cleanup that
    * removes both, every batch directory beside them, and any extra file the
    * run was configured to write (e.g. the tamper-evident checksum).
    *
    * @param output
    *   the output directory
    * @param json
    *   the `emitJsonDir` directory
    * @param extra
    *   additional paths this run wrote, removed by [[cleanup]]
    */
  case class Run(
      output: File,
      json: File,
      extra: Vector[File] = Vector.empty
  ) {

    /** Every batch directory this run wrote. */
    def batches: Vector[File] = batchDirectories(output)

    /** Every `.grc` across every batch directory. */
    def clusterFiles: Vector[File] =
      batches.flatMap(d => filesWithSuffix(d, ".grc"))

    /** Every `.grd` across every batch directory. */
    def dataFiles: Vector[File] =
      batches.flatMap(d => filesWithSuffix(d, ".grd"))

    /** Every `.gri` across every batch directory. */
    def indexFiles: Vector[File] =
      batches.flatMap(d => filesWithSuffix(d, ".gri"))

    /** Every `purls.txt` line across every batch directory. */
    def purls: Set[String] =
      batches
        .flatMap(d => filesWithSuffix(d, "purls.txt"))
        .flatMap(f =>
          Files.readAllLines(f.toPath).toArray(new Array[String](0))
        )
        .map(_.trim)
        .filter(_.nonEmpty)
        .toSet

    /** Every item identifier in the run's JSON dumps. */
    def dumpIdentifiers: Set[String] =
      dumpFiles(json).flatMap { f =>
        val text = new String(Files.readAllBytes(f.toPath), "UTF-8")
        """\"identifier\"\s*:\s*\"([^\"]+)\"""".r
          .findAllMatchIn(text)
          .map(m => Option(m.group(1)).getOrElse(""))
          .filter(_.nonEmpty)
          .toVector
      }.toSet

    /** Every item identifier in the run's `.grd` files. */
    def grdIdentifiers: Set[String] =
      batches.flatMap(d => readItemsFromGrd(d)).map(_.identifier).toSet

    /** Remove the output directory, the dump directory, every batch directory
      * beside the output directory, and any extra file the run wrote.
      */
    def cleanup(): Unit = {
      val siblings = Option(output.getParentFile)
        .flatMap(p => Option(p.listFiles()))
        .getOrElse(Array.empty[File])
        .filter(f =>
          f.isDirectory && f.getName.startsWith(output.getName + "_")
        )
      siblings.foreach(deleteTree)
      deleteTree(output)
      deleteTree(json)
      extra.foreach { f =>
        if (f.isDirectory) deleteTree(f) else Try(f.delete())
      }
      ()
    }
  }
}
