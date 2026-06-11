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

package strategies

import io.spicelabs.goatrodeo.util.SshWireReader
import munit.FunSuite

/** Unit tests for the RFC 4251 SSH wire-format reader.
  *
  * Updated for Phase 0.9: SshWireReader methods now return Option instead of
  * throwing on short reads. Tests adapted accordingly.
  *
  * ## What these tests test
  *
  *   1. Boundary-correct integer reads (`uint32`, `uint64`) 2. Length-prefixed
  *      `string` reads, including zero-length strings 3. Out-of-bounds reads
  *      return None instead of throwing 4. `mpint` bit-length helper handles
  *      SSH's zero-padding convention 5. `parseFirstKeyLine` strips comments
  *      and BOMs 6. Wire reader's `string` content is faithful round-trip
  */
class SshWireFormatTests extends FunSuite {

  test("[INVARIANT] readUInt32 reads 4 big-endian bytes") {
    val r = new SshWireReader(Array[Byte](0x00, 0x00, 0x00, 0x05))
    assertEquals(r.readUInt32(), Some(5L))
    assertEquals(r.remaining, 0)
  }

  test("[INVARIANT] readUInt32 handles values above 2^31 as unsigned") {
    val bytes = Array[Byte](0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte)
    val r = new SshWireReader(bytes)
    assertEquals(r.readUInt32(), Some(0xffffffffL))
  }

  test("[INVARIANT] readUInt64 reads 8 big-endian bytes") {
    val bytes = Array[Byte](0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07)
    val r = new SshWireReader(bytes)
    assertEquals(r.readUInt64(), Some(7L))
  }

  test("[INVARIANT] readString reads length-prefixed bytes") {
    val bytes = Array[Byte](0x00, 0x00, 0x00, 0x03, 0x41, 0x42, 0x43)
    val r = new SshWireReader(bytes)
    val s = r.readString()
    assert(s.isDefined)
    assertEquals(s.get.toSeq, Seq[Byte](0x41, 0x42, 0x43))
    assertEquals(r.remaining, 0)
  }

  test("[INVARIANT] readString of length zero returns empty array") {
    val bytes = Array[Byte](0x00, 0x00, 0x00, 0x00)
    val r = new SshWireReader(bytes)
    val s = r.readString()
    assert(s.isDefined)
    assertEquals(s.get.length, 0)
  }

  test("[GUARD] readString returns None when length exceeds remaining bytes") {
    val bytes =
      Array[Byte](0x00, 0x00, 0x00, 0x10, 0x41)
    val r = new SshWireReader(bytes)
    assertEquals(r.readString(), None)
  }

  test("[GUARD] readUInt32 on truncated input returns None") {
    val r = new SshWireReader(Array[Byte](0x01, 0x02))
    assertEquals(r.readUInt32(), None)
  }

  test("[INVARIANT] mpintBitLength: 0 bytes → 0") {
    assertEquals(SshWireReader.mpintBitLength(Array[Byte]()), 0)
  }

  test("[INVARIANT] mpintBitLength: SSH zero-pad stripped before counting") {
    assertEquals(
      SshWireReader.mpintBitLength(Array[Byte](0x00, 0x80.toByte)),
      8
    )
  }

  test("[INVARIANT] mpintBitLength: 2048-bit RSA modulus has 2048 bits") {
    val bytes = (Array[Byte](0x00, 0x80.toByte) ++ Array.fill[Byte](255)(0))
    assertEquals(SshWireReader.mpintBitLength(bytes), 2048)
  }

  test("[INVARIANT] mpintBitLength: high-byte bit count is honored") {
    assertEquals(SshWireReader.mpintBitLength(Array[Byte](0x01)), 1)
    assertEquals(SshWireReader.mpintBitLength(Array[Byte](0x07)), 3)
    assertEquals(SshWireReader.mpintBitLength(Array[Byte](0x40)), 7)
  }

  test("[INVARIANT] parseFirstKeyLine extracts algo, base64, comment") {
    val line =
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIC7ScYYTQq7gc3vqK4JyYx+7tHymW8rlqydjgU3etW+o test\n"
    val parsed = SshWireReader.parseFirstKeyLine(line)
    assert(parsed.isDefined)
    val (algo, wire, comment) = parsed.get
    assertEquals(algo, "ssh-ed25519")
    assertEquals(comment, Some("test"))
    val r = new SshWireReader(wire)
    assertEquals(r.readUtf8String(), Some("ssh-ed25519"))
  }

  test("[INVARIANT] parseFirstKeyLine returns None on missing payload") {
    assertEquals(SshWireReader.parseFirstKeyLine("ssh-rsa\n"), None)
    assertEquals(SshWireReader.parseFirstKeyLine(""), None)
  }

  test("[INVARIANT] parseFirstKeyLine strips UTF-8 BOM") {
    val line =
      "\uFEFFssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIC7ScYYTQq7gc3vqK4JyYx+7tHymW8rlqydjgU3etW+o\n"
    assert(SshWireReader.parseFirstKeyLine(line).isDefined)
  }

  test("[INVARIANT] parseFirstKeyLine skips blank and # lines") {
    val line =
      "\n# comment\nssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIC7ScYYTQq7gc3vqK4JyYx+7tHymW8rlqydjgU3etW+o\n"
    val parsed = SshWireReader.parseFirstKeyLine(line)
    assert(parsed.isDefined)
    assertEquals(parsed.get._1, "ssh-ed25519")
  }

  test("[INVARIANT] readStringList unpacks principals") {
    val outer = Array[Byte](0x00, 0x00, 0x00, 0x10) ++
      Array[Byte](0x00, 0x00, 0x00, 0x05) ++ "alice".getBytes("UTF-8") ++
      Array[Byte](0x00, 0x00, 0x00, 0x03) ++ "bob".getBytes("UTF-8")
    val r = new SshWireReader(outer)
    assertEquals(r.readStringList(), Some(Vector("alice", "bob")))
  }

  test("[INVARIANT] readNameDataList unpacks (name,data) pairs") {
    val pair1Name = "permit-pty".getBytes("UTF-8")
    val pair2Name = "permit-x11".getBytes("UTF-8")
    val pair1 =
      Array[Byte](0, 0, 0, pair1Name.length.toByte) ++ pair1Name ++ Array[Byte](
        0,
        0,
        0,
        0
      )
    val pair2 =
      Array[Byte](0, 0, 0, pair2Name.length.toByte) ++ pair2Name ++ Array[Byte](
        0,
        0,
        0,
        0
      )
    val outerLen = pair1.length + pair2.length
    val outer = Array[Byte](
      (outerLen >>> 24).toByte,
      (outerLen >>> 16).toByte,
      (outerLen >>> 8).toByte,
      outerLen.toByte
    ) ++ pair1 ++ pair2
    val r = new SshWireReader(outer)
    val list = r.readNameDataList()
    assert(list.isDefined)
    assertEquals(list.get.length, 2)
    assertEquals(list.get(0)._1, "permit-pty")
    assertEquals(list.get(1)._1, "permit-x11")
  }
}
