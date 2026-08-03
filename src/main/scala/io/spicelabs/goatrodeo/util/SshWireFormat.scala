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

/** RFC 4251 / SSH wire-format reader. Out-of-bounds reads return None instead
  * of throwing. Positions only advance on successful reads.
  */
final class SshWireReader(val bytes: Array[Byte]) {
  private var pos: Int = 0

  def remaining: Int = bytes.length - pos

  def position: Int = pos

  def readByte(): Option[Int] = {
    if (remaining < 1) None
    else {
      val b = bytes(pos) & 0xff
      pos += 1
      Some(b)
    }
  }

  def readUInt32(): Option[Long] = {
    if (remaining < 4) None
    else {
      val v =
        ((bytes(pos) & 0xffL) << 24) |
          ((bytes(pos + 1) & 0xffL) << 16) |
          ((bytes(pos + 2) & 0xffL) << 8) |
          ((bytes(pos + 3) & 0xffL))
      pos += 4
      Some(v)
    }
  }

  def readUInt64(): Option[Long] = {
    for {
      hi <- readUInt32()
      lo <- readUInt32()
    } yield (hi << 32) | (lo & 0xffffffffL)
  }

  /** Length-prefixed byte string. */
  def readString(): Option[Array[Byte]] = {
    readUInt32().flatMap { len =>
      if (len < 0 || len > remaining) None
      else {
        val out = new Array[Byte](len.toInt)
        System.arraycopy(bytes, pos, out, 0, len.toInt)
        pos += len.toInt
        Some(out)
      }
    }
  }

  def readUtf8String(): Option[String] =
    readString().map(s => new String(s, StandardCharsets.UTF_8))

  /** SSH `mpint` — big-endian two's-complement integer bytes per RFC 4251. */
  def readMpint(): Option[Array[Byte]] = readString()

  /** Read a sequence of length-prefixed strings packed inside an outer `string`
    * (used for OpenSSH cert principals / critical options / extensions).
    */
  def readStringList(): Option[Vector[String]] = {
    readString().flatMap { inner =>
      val r = new SshWireReader(inner)
      val acc = Vector.newBuilder[String]
      var ok = true
      while (r.remaining > 0 && ok) {
        r.readUtf8String() match {
          case Some(s) => acc += s
          case None    => ok = false
        }
      }
      if (ok) Some(acc.result()) else None
    }
  }

  /** Read (name, data) pairs from an outer `string`, per OpenSSH cert
    * critical-options/extensions format.
    */
  def readNameDataList(): Option[Vector[(String, Array[Byte])]] = {
    readString().flatMap { inner =>
      val r = new SshWireReader(inner)
      val acc = Vector.newBuilder[(String, Array[Byte])]
      var ok = true
      while (r.remaining > 0 && ok) {
        val n = r.readUtf8String()
        val d = r.readString()
        (n, d) match {
          case (Some(name), Some(data)) => acc += ((name, data))
          case _                        => ok = false
        }
      }
      if (ok) Some(acc.result()) else None
    }
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
          val padded =
            if (b64.length % 4 == 0) b64
            else b64 + "=" * (4 - (b64.length % 4))
          val wire = java.util.Base64.getDecoder.decode(padded)
          val comment =
            if (parts.length >= 3 && parts(2).nonEmpty) Some(parts(2)) else None
          (parts(0), wire, comment)
        }.toOption
    }
  }
}
