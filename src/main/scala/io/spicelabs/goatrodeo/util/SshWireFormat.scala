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

import java.nio.charset.StandardCharsets
import scala.util.Try

/** RFC 4251 / SSH wire-format reader. Out-of-bounds reads throw
  * `IndexOutOfBoundsException`.
  */
final class SshWireReader(val bytes: Array[Byte]) {
  private var pos: Int = 0

  def remaining: Int = bytes.length - pos

  def position: Int = pos

  def readByte(): Int = {
    require(
      remaining >= 1,
      s"SshWireReader: short read at $pos (need 1, have $remaining)"
    )
    val b = bytes(pos) & 0xff
    pos += 1
    b
  }

  def readUInt32(): Long = {
    require(
      remaining >= 4,
      s"SshWireReader: short read at $pos (need 4, have $remaining)"
    )
    val v =
      ((bytes(pos) & 0xffL) << 24) |
        ((bytes(pos + 1) & 0xffL) << 16) |
        ((bytes(pos + 2) & 0xffL) << 8) |
        ((bytes(pos + 3) & 0xffL))
    pos += 4
    v
  }

  def readUInt64(): Long = {
    require(
      remaining >= 8,
      s"SshWireReader: short read at $pos (need 8, have $remaining)"
    )
    val hi = readUInt32()
    val lo = readUInt32()
    (hi << 32) | (lo & 0xffffffffL)
  }

  /** Length-prefixed byte string. */
  def readString(): Array[Byte] = {
    val len = readUInt32()
    require(
      len >= 0 && len <= remaining,
      s"SshWireReader: string length $len exceeds remaining $remaining at $pos"
    )
    val out = new Array[Byte](len.toInt)
    System.arraycopy(bytes, pos, out, 0, len.toInt)
    pos += len.toInt
    out
  }

  def readUtf8String(): String =
    new String(readString(), StandardCharsets.UTF_8)

  /** SSH `mpint` — big-endian two's-complement integer bytes per RFC 4251. */
  def readMpint(): Array[Byte] = readString()

  /** Read a sequence of length-prefixed strings packed inside an outer `string`
    * (used for OpenSSH cert principals / critical options / extensions).
    */
  def readStringList(): Vector[String] = {
    val inner = readString()
    val r = new SshWireReader(inner)
    val acc = scala.collection.mutable.ListBuffer[String]()
    while (r.remaining > 0) acc += r.readUtf8String()
    acc.toVector
  }

  /** Read (name, data) pairs from an outer `string`, per OpenSSH cert
    * critical-options/extensions format.
    */
  def readNameDataList(): Vector[(String, Array[Byte])] = {
    val inner = readString()
    val r = new SshWireReader(inner)
    val acc = scala.collection.mutable.ListBuffer[(String, Array[Byte])]()
    while (r.remaining > 0) {
      val n = r.readUtf8String()
      val d = r.readString()
      acc += ((n, d))
    }
    acc.toVector
  }
}

object SshWireReader {

  /** Number of significant bits in a non-negative SSH `mpint` magnitude. Strips
    * leading `0x00` zero-padding byte.
    */
  def mpintBitLength(mpint: Array[Byte]): Int = {
    if (mpint.length == 0) 0
    else {
      val (offset, head) =
        if (mpint(0) == 0.toByte)
          (1, if (mpint.length >= 2) mpint(1) & 0xff else 0)
        else (0, mpint(0) & 0xff)
      if (offset >= mpint.length) 0
      else {
        val bytesAfterPad = mpint.length - offset
        val highByte = head
        // bit length = (bytes - 1) * 8 + bit-length-of-high-byte
        var hb = highByte
        var hbBits = 0
        while (hb != 0) { hbBits += 1; hb >>>= 1 }
        (bytesAfterPad - 1) * 8 + hbBits
      }
    }
  }

  /** Parse an OpenSSH public-key line: `algo-name base64(wire) [comment...]`.
    * Returns `(algoName, wireBytes, optComment)` or `None` on parse failure.
    * Strips BOM, skips blank/comment lines, takes first non-blank line.
    */
  def parseFirstKeyLine(
      content: String
  ): Option[(String, Array[Byte], Option[String])] = {
    val stripped = content
      .stripPrefix("\uFEFF")
      .stripPrefix("\u00EF\u00BB\u00BF")
    val firstLine = stripped.linesIterator.find { l =>
      val t = l.trim
      t.nonEmpty && !t.startsWith("#")
    }
    firstLine.flatMap { rawLine =>
      val line = rawLine.trim
      val parts = line.split("\\s+", 3)
      if (parts.length < 2) None
      else
        Try {
          val b64 = parts(1)
          // OpenSSH uses standard base64 with padding. Some tools strip
          // padding; tolerate both by re-padding.
          val padded =
            if (b64.length % 4 == 0) b64
            else b64 + "=" * (4 - (b64.length % 4))
          val wire = java.util.Base64.getDecoder.nn.decode(padded).nn
          val comment =
            if (parts.length >= 3 && parts(2).nonEmpty) Some(parts(2)) else None
          (parts(0), wire, comment)
        }.toOption
    }
  }
}
