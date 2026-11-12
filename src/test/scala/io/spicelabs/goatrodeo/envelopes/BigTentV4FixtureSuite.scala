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

package io.spicelabs.goatrodeo.envelopes

import io.bullet.borer.{Cbor, Dom}
import io.bullet.borer.Dom.Element
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.io.File
import java.nio.charset.StandardCharsets

/** The version 4 format as BigTent actually writes it.
  *
  * WHAT: the two clusters under `test_data/bigtent_v4` — copied verbatim from the
  * BigTent repository at a pinned commit — are decoded with this project's envelope
  * code, and the `BLAKE3[0..16]` index keys in each `.gri` are recomputed from the
  * item identifiers in the `.grd` files and compared byte for byte.
  *
  * WHY: a writer and a reader that agree with each other and disagree with the
  * format pass every round-trip test. These fixtures are bytes Rust wrote, so they
  * are the only check available at unit-test speed that our understanding of the
  * envelope versions, the encoding declaration and the key algorithm is the same as
  * BigTent's.
  *
  * THEORY: three independent things are asserted from the same bytes. (a) The
  * envelope versions and the declared encodings are what the writer emits — cluster
  * 4 with `encoding`, index 1 with `encoding`, data 2. (b) The declared encoding is
  * the algorithm that was actually used, proved by recomputing every index key from
  * the identifier that produced it. (c) Nothing about our own `Item` model is
  * involved in (a) or (b), because the identifiers are read straight out of the CBOR
  * with the generic element API — so a later change to `Item` cannot make this test
  * pass or fail for the wrong reason.
  *
  * LLM note: test ids are V4F-n. Fixture provenance and per-file hashes are in
  * `test_data/bigtent_v4/SOURCES.md`.
  */
class BigTentV4FixtureSuite extends GoatRodeoFunSuite {

  import ClusterFileEnvelope.given

  private val root = new File("test_data/bigtent_v4")

  private def clusterDirs: Vector[File] =
    Option(root.listFiles()).map(_.toVector.filter(_.isDirectory)).getOrElse(Vector.empty).sortBy(_.getName)

  private def filesOf(dir: File, suffix: String): Vector[File] =
    Option(dir.listFiles()).map(_.toVector).getOrElse(Vector.empty)
      .filter(_.getName.endsWith(suffix)).sortBy(_.getName)

  private def bytesOf(f: File): Array[Byte] =
    java.nio.file.Files.readAllBytes(f.toPath)

  /** magic (u32 big endian), envelope length (u16 big endian), envelope. */
  private def header(bytes: Array[Byte]): (Int, Array[Byte]) = {
    val magic = ((bytes(0) & 0xff) << 24) | ((bytes(1) & 0xff) << 16) |
      ((bytes(2) & 0xff) << 8) | (bytes(3) & 0xff)
    val len = ((bytes(4) & 0xff) << 8) | (bytes(5) & 0xff)
    (magic, java.util.Arrays.copyOfRange(bytes, 6, 6 + len))
  }

  /** The identifiers in a data file, read out of the CBOR without materializing an
    * `Item`: this test must not depend on the item model.
    */
  private def identifiers(dataFile: File): Vector[String] = {
    val all = bytesOf(dataFile)
    val (_, envelope) = header(all)
    var pos = 6 + envelope.length
    val out = Vector.newBuilder[String]
    // Every fixture data file ends with a 10-byte trailing marker
    // (`ffff0000 00000000 0123`) after the last item. BigTent's own reader is
    // position-based and never scans, so the marker is inert for lookups; the
    // scan here stops when the next "length" cannot be an item.
    while (pos + 4 <= all.length) {
      val len = ((all(pos) & 0xff) << 24) | ((all(pos + 1) & 0xff) << 16) |
        ((all(pos + 2) & 0xff) << 8) | (all(pos + 3) & 0xff)
      if (len <= 0 || len > all.length - pos - 4) pos = all.length
      else {
      val itemBytes = java.util.Arrays.copyOfRange(all, pos + 4, pos + 4 + len)
      pos += 4 + len
      Cbor.decode(itemBytes).to[Element].value match {
        case m: Dom.MapElem =>
          m.members.collectFirst {
            case (Dom.StringElem(k), Dom.StringElem(v)) if k == "identifier" => v
          }.foreach(out += _)
        case _ => ()
      }
      }
    }
    out.result()
  }

  /** The raw 16-byte keys and their (data file, offset) positions. */
  private def indexEntries(indexFile: File): Vector[(Array[Byte], Long, Long)] = {
    val all = bytesOf(indexFile)
    val (_, envelopeBytes) = header(all)
    val env = Cbor.decode(envelopeBytes).to[IndexFileEnvelope].value
    var pos = 6 + envelopeBytes.length
    val out = Vector.newBuilder[(Array[Byte], Long, Long)]
    var i = 0
    while (i < env.size && pos + 32 <= all.length) {
      val key = java.util.Arrays.copyOfRange(all, pos, pos + 16)
      val dataFileHash = java.nio.ByteBuffer.wrap(all, pos + 16, 8).getLong()
      val offset = java.nio.ByteBuffer.wrap(all, pos + 24, 8).getLong()
      out += ((key, dataFileHash, offset))
      pos += 32
      i += 1
    }
    out.result()
  }

  private def hashName(hash: Long): String = String.format("%016x", hash)

  // V4F-1 — the envelopes of both fixtures declare the version 4 format.
  test("V4F-1 the fixtures are version 4 clusters with the BLAKE3 declaration") {
    val dirs = clusterDirs
    assert(dirs.nonEmpty, s"fixtures missing under ${root.getAbsolutePath}")
    dirs.foreach { dir =>
      val clusterFiles = filesOf(dir, ".grc")
      assertEquals(clusterFiles.length, 1, s"one cluster file in ${dir.getName}")
      val (magic, envelopeBytes) = header(bytesOf(clusterFiles.head))
      assertEquals(magic, 0xba4a4a, "cluster magic")
      val env = Cbor.decode(envelopeBytes).to[ClusterFileEnvelope].value
      assertEquals(env.version, 4, s"cluster version in ${dir.getName}")
      assertEquals(env.magic, 0xba4a4a)
      assertEquals(env.encoding, Blake3IndexKey.encoding)
      assert(env.dataFiles.nonEmpty, "a cluster names its data files")
      assert(env.indexFiles.nonEmpty, "a cluster names its index files")
    }
  }

  // V4F-2 — every index file declares version 1 and the same algorithm, and every
  // data file declares envelope version 2.
  test("V4F-2 index and data envelopes match the version 4 contract") {
    clusterDirs.foreach { dir =>
      filesOf(dir, ".gri").foreach { gri =>
        val (magic, envelopeBytes) = header(bytesOf(gri))
        assertEquals(magic, 0x54154170, "index magic")
        val env = Cbor.decode(envelopeBytes).to[IndexFileEnvelope].value
        assertEquals(env.version, 1, s"index envelope version in ${gri.getName}")
        assertEquals(env.magic, 0x54154170)
        assertEquals(env.encoding, Blake3IndexKey.encoding, s"encoding in ${gri.getName}")
        assert(env.size > 0, "an index file declares its entry count")
        assert(env.dataFiles.nonEmpty, "an index file names its data files")
      }
      filesOf(dir, ".grd").foreach { grd =>
        val (magic, envelopeBytes) = header(bytesOf(grd))
        assertEquals(magic, 0x00be1100, "data magic")
        val env = Cbor.decode(envelopeBytes).to[DataFileEnvelope].value
        assertEquals(env.version, 2, s"data envelope version in ${grd.getName}")
        assertEquals(env.magic, 0x00be1100)
        assertEquals(env.previous, 0L, "chain fields are inert in version 4")
        assert(env.dependsOn.isEmpty, "depends_on is empty in version 4")
      }
    }
  }

  // V4F-3 — the declared algorithm is the algorithm that was used: every index key
  // equals BLAKE3[0..16] of an identifier that appears in the data file it points at.
  test("V4F-3 every index key is BLAKE3[0..16] of its identifier") {
    var checked = 0
    clusterDirs.foreach { dir =>
      val identifiersByFile: Map[Long, Vector[String]] =
        filesOf(dir, ".grd").map { grd =>
          val all = bytesOf(grd)
          val (_, envelopeBytes) = header(all)
          val env = Cbor.decode(envelopeBytes).to[DataFileEnvelope].value
          // the file's name is the top 8 bytes of its SHA-256; the index refers to it
          // by that number, which is the name parsed back out of the filename
          val nameHash = java.lang.Long.parseUnsignedLong(grd.getName.dropRight(4), 16)
          nameHash -> identifiers(grd)
        }.toMap

      filesOf(dir, ".gri").foreach { gri =>
        val entries = indexEntries(gri)
        assert(entries.nonEmpty, s"${gri.getName} has entries")
        entries.foreach { case (key, dataFileHash, _) =>
          val identifiersHere = identifiersByFile.getOrElse(
            dataFileHash,
            fail(
              s"${gri.getName} points at data file ${hashName(dataFileHash)} " +
                s"which is not present in ${dir.getName}; present: " +
                identifiersByFile.keys.map(hashName).mkString(", ")
            )
          )
          assertEquals(key.length, Blake3IndexKey.KeyBytes)
          val matching = identifiersHere.filter(id => java.util.Arrays.equals(Blake3IndexKey.key(id), key))
          assertEquals(
            matching.length,
            1,
            s"index key ${BigTentV4FixtureSuite.hex(key)} must be BLAKE3[0..16] of exactly one identifier " +
              s"in ${hashName(dataFileHash)}; matched ${matching.length}"
          )
          checked += 1
        }
      }
    }
    assert(checked > 0, "the fixtures must contain index entries")
  }
}

object BigTentV4FixtureSuite {
  private def hex(b: Array[Byte]): String =
    b.map(x => String.format("%02x", x)).mkString
}
