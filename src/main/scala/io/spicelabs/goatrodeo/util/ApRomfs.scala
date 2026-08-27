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

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater
import scala.util.Try
import scala.util.Using

/** Reader for the ArduPilot `AP_ROMFS` embedded file store.
  *
  * ArduPilot compiles its `ROMFS/` directory into a generated C data table:
  *
  * struct embedded_file { const char *filename; // pointer to a NUL-terminated
  * path string uint32_t compressed_size; uint32_t decompressed_size; uint32_t
  * crc; const uint8_t *contents; // pointer to a raw-DEFLATE byte stream };
  *
  * The on-disk layout is a contiguous array of such structs; pointer width
  * depends only on the ELF class (32-bit: 20-byte structs; 64-bit: 32-byte),
  * and pointers are absolute virtual addresses mapped to file offsets through
  * the ELF program headers.
  *
  * Two-phase discipline. `read` first runs a cheap gate through
  * `ArtifactWrapper.withStream` that reads only the ELF head and rejects any
  * artifact that is not an ELF (magic + class). Only if that gate passes is the
  * balance of the work performed, and then through `ArtifactWrapper .withFile`
  * so that pointer resolution gets cheap random access. Nothing is loaded whole
  * into memory: the anchor/pointer scan is a bounded forward-stream pass, and
  * struct/name/content reads are seek-and-read regions. Non-ELF artifacts never
  * trigger a `withFile`.
  */
object ApRomfs {

  /** Maximum number of ROMFS files to read. */
  val MaxFiles: Int = 4096

  /** Maximum decompressed size of a single ROMFS file. */
  val MaxFileBytes: Int = 64 * 1024 * 1024

  /** Bytes at the start of the image needed for the ELF header + program
    * headers (phdrs sit near the start in firmware builds).
    */
  private val HeadBytes: Int = 8192

  /** Streaming scan chunk size. */
  private val ChunkSize: Int = 1 << 20

  /** Filenames that appear in most AP_ROMFS builds, used as anchors to locate
    * the `files[]` table.
    */
  private val AnchorNames: Vector[String] = Vector(
    "etc/ssl/certs/root-ca.crt",
    "etc/ssl/certs/update-signer.crt",
    "models/Callisto.json",
    "models/plane.parm",
    "defaults.parm",
    "apm.version",
    "locations.txt"
  )

  private final case class Seg(vaddr: Long, offset: Long, filesz: Long)

  private final case class Elf(
      ptr: Int,
      stride: Int,
      contentsOff: Int,
      segs: Vector[Seg]
  ) {
    def v2o(v: Long): Long = {
      var i = 0
      var r = v
      var found = false
      while (i < segs.length && !found) {
        val s = segs(i)
        if (v >= s.vaddr && v < s.vaddr + s.filesz) {
          r = s.offset + (v - s.vaddr)
          found = true
        }
        i += 1
      }
      r
    }
    def o2v(o: Long): Long = {
      var i = 0
      var r = o
      var found = false
      while (i < segs.length && !found) {
        val s = segs(i)
        if (o >= s.offset && o < s.offset + s.filesz) {
          r = s.vaddr + (o - s.offset)
          found = true
        }
        i += 1
      }
      r
    }
  }

  /** Read an AP_ROMFS embedded file store from the artifact's stream. Returns
    * the decompressed ROMFS files (each bounded by [[MaxFileBytes]]), or None
    * if the artifact is not an AP_ROMFS-bearing ELF.
    */
  def read(artifact: ArtifactWrapper): Option[Vector[(String, Array[Byte])]] = {
    gateElf(artifact).flatMap { elf =>
      artifact.withFile { f =>
        Using.resource(new RandomAccessFile(f, "r")) { raf =>
          scanFile(raf, elf).flatMap { case (vaddr, candidates) =>
            locateTableFile(raf, elf, vaddr, candidates).map { base =>
              readTableFile(raf, elf, base)
            }
          }
        }
      }
    }
  }

  // --- ELF gate (one forward read via withStream) --------------------------

  private def gateElf(artifact: ArtifactWrapper): Option[Elf] = {
    val head = readAt(artifact, 0, HeadBytes)
    val isElf =
      head.length >= 16 && head(0) == 0x7f && head(1) == 'E' &&
        head(2) == 'L' && head(3) == 'F'
    if (!isElf) {
      None
    } else {
      val cls = head(4) & 0xff
      val le = (head(5) & 0xff) == 1
      if (cls != 1 && cls != 2) {
        None
      } else {
        val ptr = if (cls == 1) 4 else 8
        val stride = if (cls == 1) 20 else 32
        val contentsOff = if (cls == 1) 16 else 24
        val phoff = if (cls == 1) u32(head, 0x1c, le) else u64(head, 0x20, le)
        val phentsize =
          if (cls == 1) u16(head, 0x2a, le) else u16(head, 0x36, le)
        val phnum = if (cls == 1) u16(head, 0x2c, le) else u16(head, 0x38, le)
        if (phoff <= 0 || phnum <= 0) {
          None
        } else {
          val segs = Vector.newBuilder[Seg]
          var i = 0L
          var stop = false
          while (!stop && i < phnum) {
            val off = (phoff + i * phentsize).toInt
            if (off + 40 > head.length) stop = true
            else {
              val (p_offset, p_vaddr, p_filesz) =
                if (cls == 1)
                  (
                    u32(head, off + 4, le).toLong,
                    u32(head, off + 8, le).toLong,
                    u32(head, off + 16, le).toLong
                  )
                else
                  (
                    u64(head, off + 8, le),
                    u64(head, off + 16, le),
                    u64(head, off + 32, le)
                  )
              if (p_offset >= 0 && p_filesz >= 0 && p_filesz < (1L << 40)) {
                segs += Seg(p_vaddr, p_offset, p_filesz)
              }
              i += 1
            }
          }
          Some(Elf(ptr, stride, contentsOff, segs.result()))
        }
      }
    }
  }

  // --- Single forward read -------------------------------------------------

  private def readAt(
      artifact: ArtifactWrapper,
      offset: Long,
      count: Int
  ): Array[Byte] = {
    val buf = new Array[Byte](count)
    artifact.withStream { s =>
      var need = offset
      var skipStop = false
      while (need > 0 && !skipStop) {
        val n = s.skip(need)
        if (n <= 0) skipStop = true else need -= n
      }
      var total = 0
      var readStop = false
      while (total < count && !readStop) {
        val r = s.read(buf, total, count - total)
        if (r <= 0) readStop = true else total += r
      }
    }
    buf
  }

  /** Seek-and-read `len` bytes at `offset` from a random-access file. If the
    * read runs short (EOF), the remainder of the returned buffer stays zeroed.
    */
  private def rafRead(
      raf: RandomAccessFile,
      offset: Long,
      len: Int
  ): Array[Byte] = {
    raf.seek(offset)
    val buf = new Array[Byte](len)
    var total = 0
    var stop = false
    while (total < len && !stop) {
      val r = raf.read(buf, total, len - total)
      if (r <= 0) stop = true else total += r
    }
    buf
  }

  // --- Byte/pointer helpers -------------------------------------------------

  private def u16(b: Array[Byte], off: Int, le: Boolean): Long = {
    if (off + 1 >= b.length) 0L
    else if (le) ((b(off) & 0xff) | ((b(off + 1) & 0xff) << 8)).toLong
    else ((b(off) & 0xff) << 8 | (b(off + 1) & 0xff)).toLong
  }

  private def u32(b: Array[Byte], off: Int, le: Boolean): Long = {
    if (off + 3 >= b.length) 0L
    else if (le)
      ((b(off) & 0xff) | ((b(off + 1) & 0xff) << 8) | ((b(
        off + 2
      ) & 0xff) << 16) |
        ((b(off + 3) & 0xffL) << 24)).toLong
    else
      ((b(off) & 0xffL) << 24 | ((b(off + 1) & 0xff) << 16) | ((b(
        off + 2
      ) & 0xff) << 8) |
        (b(off + 3) & 0xff)).toLong
  }

  private def u64(b: Array[Byte], off: Int, le: Boolean): Long = {
    if (off + 7 >= b.length) 0L
    else {
      var v = 0L
      if (le) {
        var k = 0
        while (k < 8) {
          v |= ((b(off + k) & 0xffL) << (8 * k))
          k += 1
        }
      } else {
        var k = 0
        while (k < 8) {
          v = (v << 8) | (b(off + k) & 0xffL)
          k += 1
        }
      }
      v
    }
  }

  private def readValue(
      buf: Array[Byte],
      i: Int,
      ptr: Int,
      le: Boolean
  ): Long = {
    var v = 0L
    if (le) {
      var k = 0
      while (k < ptr) {
        v |= ((buf(i + k) & 0xffL) << (8 * k))
        k += 1
      }
    } else {
      var k = 0
      while (k < ptr) {
        v = (v << 8) | (buf(i + k) & 0xffL)
        k += 1
      }
    }
    v
  }

  // --- Combined forward scan (anchor + pointer matches in one pass) ---------

  private def scanFile(
      raf: RandomAccessFile,
      e: Elf
  ): Option[(Long, Vector[Long])] = {
    var out = Option.empty[(Long, Vector[Long])]
    var anchorDone = false
    val maxKeep = math.max(AnchorNames.map(_.length).max - 1, e.ptr - 1)
    val buf = new Array[Byte](ChunkSize + maxKeep)
    var fileOffset = 0L
    var carry = 0
    var stop = false
    while (!stop) {
      val read = raf.read(buf, carry, ChunkSize)
      if (read <= 0) stop = true
      else {
        val n = carry + read
        if (!anchorDone) {
          var a = 0
          var anchorFound = false
          while (!anchorFound && a < AnchorNames.length) {
            val needle = AnchorNames(a).getBytes("UTF-8")
            val limit = n - needle.length
            var i = 0
            while (!anchorFound && i <= limit) {
              var j = 0
              var ok = true
              while (ok && j < needle.length) {
                if (buf(i + j) != needle(j)) ok = false
                j += 1
              }
              if (ok) {
                val strOff = fileOffset + i
                out = Some((e.o2v(strOff), Vector.empty[Long]))
                anchorDone = true
                anchorFound = true
              }
              i += 1
            }
            a += 1
          }
        }
        val vaddr = out.map(_._1)
        vaddr.foreach { v =>
          val cands = out.get._2
          if (cands.length < 4096) {
            var i = 0
            while (i + e.ptr <= n) {
              if (readValue(buf, i, e.ptr, le = true) == v) {
                out = Some((v, cands :+ (fileOffset + i)))
              }
              i += 1
            }
          }
        }
        if (n > maxKeep) {
          var k = 0
          while (k < maxKeep) {
            buf(k) = buf(n - maxKeep + k)
            k += 1
          }
          carry = maxKeep
          fileOffset += n - maxKeep
        } else {
          stop = true
        }
      }
    }
    if (anchorDone) out else None
  }

  // --- Table location ------------------------------------------------------

  private def locateTableFile(
      raf: RandomAccessFile,
      e: Elf,
      vaddr: Long,
      candidates: Vector[Long]
  ): Option[Long] = {
    var base = Option.empty[Long]
    var ci = 0
    while (base.isEmpty && ci < candidates.length) {
      val p = candidates(ci)
      if (entryValidFile(raf, e, p)) {
        var b = p
        var prev = p - e.stride
        var walked = true
        while (walked && prev >= 0 && entryValidFile(raf, e, prev)) {
          b = prev
          prev = prev - e.stride
        }
        base = Some(b)
      }
      ci += 1
    }
    base
  }

  private def entryValidFile(
      raf: RandomAccessFile,
      e: Elf,
      struct: Long
  ): Boolean = {
    if (struct < 0) false
    else {
      val fields = rafRead(raf, struct, e.contentsOff + e.ptr)
      val cp = readValue(fields, e.contentsOff, e.ptr, le = true)
      val ds = u32(fields, 12, le = true).toInt
      cp > 0 && ds > 0 && ds <= MaxFileBytes && e.v2o(cp) >= 0
    }
  }

  private def readTableFile(
      raf: RandomAccessFile,
      e: Elf,
      base: Long
  ): Vector[(String, Array[Byte])] = {
    val files = Vector.newBuilder[(String, Array[Byte])]
    var done = false
    var i = 0
    while (!done && i < MaxFiles) {
      val off = base + i * e.stride
      val fields = rafRead(raf, off, e.contentsOff + e.ptr)
      val fnPtr =
        if (e.ptr == 4) u32(fields, 0, le = true) else u64(fields, 0, le = true)
      val fnOff = e.v2o(fnPtr)
      val cp = readValue(fields, e.contentsOff, e.ptr, le = true)
      val ds = u32(fields, 12, le = true).toInt
      val cpOff = e.v2o(cp)
      if (fnOff < 0 || cpOff < 0 || ds <= 0) done = true
      else i += 1
    }
    var fi = 0
    var fdone = false
    while (!fdone && fi < i) {
      val off = base + fi * e.stride
      val fields = rafRead(raf, off, e.contentsOff + e.ptr)
      val fnPtr =
        if (e.ptr == 4) u32(fields, 0, le = true) else u64(fields, 0, le = true)
      val fnOff = e.v2o(fnPtr)
      val cp = readValue(fields, e.contentsOff, e.ptr, le = true)
      val ds = u32(fields, 12, le = true).toInt
      val cpOff = e.v2o(cp)
      if (fnOff >= 0 && cpOff >= 0) {
        val rawName = rafRead(raf, fnOff, 256)
        val name = {
          var k = 0
          while (k < rawName.length && rawName(k) != 0) k += 1
          if (k == 0) "" else new String(rawName, 0, k, "UTF-8")
        }
        val input = rafRead(raf, cpOff, math.min(ds + 64, MaxFileBytes))
        inflateMem(input, ds) match {
          case Some(dec) if dec.length == ds => files += (name -> dec)
          case _                             =>
        }
      }
      fi += 1
    }
    files.result()
  }

  private def inflateMem(
      input: Array[Byte],
      expected: Int
  ): Option[Array[Byte]] = {
    Try {
      val inflater = new Inflater(true)
      inflater.setInput(input, 0, input.length)
      val out = new ByteArrayOutputStream()
      val buf = new Array[Byte](8192)
      var ok = true
      var fin = false
      while (!fin) {
        val produced = inflater.inflate(buf)
        if (produced > 0) {
          if (out.size() + produced > MaxFileBytes) {
            ok = false
            fin = true
          } else {
            out.write(buf, 0, produced)
          }
        }
        if (inflater.finished()) fin = true
        if (!inflater.finished() && inflater.needsInput()) {
          ok = false
          fin = true
        }
      }
      inflater.end()
      if (!ok) None else Some(out.toByteArray)
    }.toOption.flatten
  }
}
