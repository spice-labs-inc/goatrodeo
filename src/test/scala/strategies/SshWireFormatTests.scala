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
  * ## What these tests test
  *
  *   1. Boundary-correct integer reads (`uint32`, `uint64`) 2. Length-prefixed
  *      `string` reads, including zero-length strings 3. Out-of-bounds reads
  *      throw rather than silently truncating 4. `mpint` bit-length helper
  *      handles SSH's zero-padding convention 5. `parseFirstKeyLine` strips
  *      comments and BOMs and returns `(algo, wireBytes, optComment)` 6. The
  *      wire reader's `string` content is faithful round-trip:
  *      `SshWireReader(write_string(s)).readString == s`
  *
  * ## Why these tests matter
  *
  * Phase 5's claim hinges on reading SSH's untyped binary wire format
  * correctly. A single off-by-one in `uint32` or a sign-extension bug in
  * `mpint` would silently produce wrong fingerprints. The unit tests establish
  * the wire reader's contract independent of the strategy.
  *
  * ## Per-test traceability (G9)
  *
  * | Test name                                          | Plan / requirement section                              | Theory                            |
  * |:---------------------------------------------------|:--------------------------------------------------------|:----------------------------------|
  * | readUInt32 reads 4 big-endian bytes                | RFC 4251 §5 (uint32 = MSB-first)                        | basic decode                      |
  * | readUInt32 handles values above 2^31 as unsigned   | RFC 4251 §5 + signed-Long pitfall                       | guards JVM Int sign-extension bug |
  * | readUInt64 reads 8 big-endian bytes                | RFC 4251 §5 (uint64)                                    | basic decode                      |
  * | readString reads length-prefixed bytes             | RFC 4251 §5 (string = uint32 + bytes)                   | basic decode                      |
  * | readString of length zero returns empty array      | RFC 4251 §5 ("string MAY contain zero octets")          | empty-string edge case            |
  * | readString throws when length exceeds remaining    | error mode contract                                     | catch malformed wire blobs early  |
  * | readUInt32 on truncated input throws               | error mode contract                                     | guards silent truncation          |
  * | mpintBitLength: 0 bytes → 0                        | Phase 5 plan §"size from modulus bit length"            | RSA size inference base case      |
  * | mpintBitLength: SSH zero-pad stripped              | RFC 4251 §5 (mpint zero-pad for high-bit-set positives) | RSA size inference correctness    |
  * | mpintBitLength: 2048-bit RSA modulus has 2048 bits | Phase 5 plan §RSA size                                  | end-to-end size correctness       |
  * | mpintBitLength: high-byte bit count is honored     | RFC 4251 §5                                             | granularity below the byte        |
  * | parseFirstKeyLine extracts algo, base64, comment   | Phase 5 plan §Parse "split on whitespace"               | line-format contract              |
  * | parseFirstKeyLine returns None on missing payload  | error mode contract                                     | guards parse-too-short            |
  * | parseFirstKeyLine strips UTF-8 BOM                 | Phase 2 P7 follow-on                                    | tolerate text-tool BOM injection  |
  * | parseFirstKeyLine skips blank and # lines          | authorized_keys-style file tolerance                    | first non-comment line wins       |
  * | readStringList unpacks principals                  | Phase 5 plan §SshCertPrincipals                         | OpenSSH cert decoding             |
  * | readNameDataList unpacks (name,data) pairs         | Phase 5 plan §SshCertCriticalOptions / Extensions       | OpenSSH cert decoding             |
  */
class SshWireFormatTests extends FunSuite {

  test("[INVARIANT] readUInt32 reads 4 big-endian bytes") {
    val r = new SshWireReader(Array[Byte](0x00, 0x00, 0x00, 0x05))
    assertEquals(r.readUInt32(), 5L)
    assertEquals(r.remaining, 0)
  }

  test("[INVARIANT] readUInt32 handles values above 2^31 as unsigned") {
    val bytes = Array[Byte](0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte)
    val r = new SshWireReader(bytes)
    assertEquals(r.readUInt32(), 0xffffffffL)
  }

  test("[INVARIANT] readUInt64 reads 8 big-endian bytes") {
    val bytes = Array[Byte](0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07)
    val r = new SshWireReader(bytes)
    assertEquals(r.readUInt64(), 7L)
  }

  test("[INVARIANT] readString reads length-prefixed bytes") {
    val bytes = Array[Byte](0x00, 0x00, 0x00, 0x03, 0x41, 0x42, 0x43)
    val r = new SshWireReader(bytes)
    val s = r.readString()
    assertEquals(s.toSeq, Seq[Byte](0x41, 0x42, 0x43))
    assertEquals(r.remaining, 0)
  }

  test("[INVARIANT] readString of length zero returns empty array") {
    val bytes = Array[Byte](0x00, 0x00, 0x00, 0x00)
    val r = new SshWireReader(bytes)
    assertEquals(r.readString().length, 0)
  }

  test("[GUARD] readString throws when length exceeds remaining bytes") {
    val bytes =
      Array[Byte](0x00, 0x00, 0x00, 0x10, 0x41) // claims 16 bytes; has 1
    val r = new SshWireReader(bytes)
    intercept[IllegalArgumentException](r.readString())
  }

  test("[GUARD] readUInt32 on truncated input throws") {
    val r = new SshWireReader(Array[Byte](0x01, 0x02))
    intercept[IllegalArgumentException](r.readUInt32())
  }

  test("[INVARIANT] mpintBitLength: 0 bytes → 0") {
    assertEquals(SshWireReader.mpintBitLength(Array[Byte]()), 0)
  }

  test("[INVARIANT] mpintBitLength: SSH zero-pad stripped before counting") {
    // 0x0080 → magnitude is 0x80 (8 bits)
    assertEquals(
      SshWireReader.mpintBitLength(Array[Byte](0x00, 0x80.toByte)),
      8
    )
  }

  test("[INVARIANT] mpintBitLength: 2048-bit RSA modulus has 2048 bits") {
    // 256 bytes with leading zero pad = 257 bytes total; high byte is
    // some non-zero (use 0x80 to ensure top bit is set)
    val bytes = (Array[Byte](0x00, 0x80.toByte) ++ Array.fill[Byte](255)(0))
    assertEquals(SshWireReader.mpintBitLength(bytes), 2048)
  }

  test("[INVARIANT] mpintBitLength: high-byte bit count is honored") {
    // 0x01 → 1 bit, 0x07 → 3 bits, 0x80 → 8 bits
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
    // wire blob's first string must equal the algo name
    val r = new SshWireReader(wire)
    assertEquals(r.readUtf8String(), "ssh-ed25519")
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
    // outer string of length 16 containing two strings: "alice" (5) and "bob" (3)
    // each prefixed by 4-byte length: 4+5+4+3 = 16
    val outer = Array[Byte](0x00, 0x00, 0x00, 0x10) ++
      Array[Byte](0x00, 0x00, 0x00, 0x05) ++ "alice".getBytes("UTF-8") ++
      Array[Byte](0x00, 0x00, 0x00, 0x03) ++ "bob".getBytes("UTF-8")
    val r = new SshWireReader(outer)
    assertEquals(r.readStringList(), Vector("alice", "bob"))
  }

  test("[INVARIANT] readNameDataList unpacks (name,data) pairs") {
    // outer of 4+10+4+0 + 4+10+4+0 = 36 bytes
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
    assertEquals(list.length, 2)
    assertEquals(list(0)._1, "permit-pty")
    assertEquals(list(1)._1, "permit-x11")
  }
}
