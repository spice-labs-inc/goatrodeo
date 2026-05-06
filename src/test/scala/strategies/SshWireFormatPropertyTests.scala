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
import munit.ScalaCheckSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalacheck.Prop.propBoolean

/** Property-based tests for the RFC 4251 SSH wire-format reader.
  *
  * ## What these tests test
  *
  * Phase-5 gap analysis G5: example-based tests can't cover the full input
  * space of byte-pumping code; properties can. These test the invariants of the
  * wire reader against generated input:
  *
  *   1. *uint32 round-trip*: encoder ∘ decoder = id for any unsigned 32-bit
  *      value. 2. *uint64 round-trip*: same for 64-bit including the sentinel
  *      values `0` and `0xFFFFFFFFFFFFFFFFL` that broke G1. 3. *string
  *      round-trip*: writeString → readString returns the original bytes for
  *      any byte-array length 0..32k. 4. *boundary-throw*: any reader fed bytes
  *      shorter than the claimed length throws (no silent truncation). 5.
  *      *mpint bit-length consistency*: for any non-negative BigInt n,
  *      `mpintBitLength(SshMpint(n)) == n.bitLength` modulo SSH's zero-pad
  *      convention. 6. *string-list round-trip*: write+read of a list of UTF-8
  *      strings returns the same list.
  *
  * ## Why this matters
  *
  * G1 (`valid_before` sentinel wrapping to 1969) was a uint64 bug that no
  * example-based test caught because no fixture happened to have that value.
  * Property #2 — "uint64 round-trip including sentinels" — would have caught
  * it. The other properties guard related classes of bugs in the byte-pumping
  * path that drives all of Phase 5's fingerprinting and metadata extraction.
  */
class SshWireFormatPropertyTests extends ScalaCheckSuite {

  // --- byte writers used to build wire blobs from generated values ---

  private def writeUInt32(out: java.io.ByteArrayOutputStream, v: Long): Unit = {
    out.write(((v >>> 24) & 0xff).toInt)
    out.write(((v >>> 16) & 0xff).toInt)
    out.write(((v >>> 8) & 0xff).toInt)
    out.write((v & 0xff).toInt)
  }

  private def writeUInt64(out: java.io.ByteArrayOutputStream, v: Long): Unit = {
    out.write(((v >>> 56) & 0xff).toInt)
    out.write(((v >>> 48) & 0xff).toInt)
    out.write(((v >>> 40) & 0xff).toInt)
    out.write(((v >>> 32) & 0xff).toInt)
    out.write(((v >>> 24) & 0xff).toInt)
    out.write(((v >>> 16) & 0xff).toInt)
    out.write(((v >>> 8) & 0xff).toInt)
    out.write((v & 0xff).toInt)
  }

  private def writeString(
      out: java.io.ByteArrayOutputStream,
      b: Array[Byte]
  ): Unit = {
    writeUInt32(out, b.length.toLong)
    out.write(b)
  }

  // --- generators ---

  private val genUInt32: Gen[Long] = Gen.choose(0L, 0xffffffffL)

  // include the two sentinels that broke G1
  private val genUInt64: Gen[Long] = Gen.frequency(
    1 -> Gen.const(0L),
    1 -> Gen.const(-1L), // 0xFFFFFFFFFFFFFFFFL as Long
    8 -> Arbitrary.arbitrary[Long]
  )

  private val genBytes: Gen[Array[Byte]] =
    Gen
      .choose(0, 32 * 1024)
      .flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[Byte]))
      .map(_.toArray)

  /** Encode a non-negative BigInt as an SSH `mpint`: minimal-octet
    * two's-complement BE, with a single leading 0x00 if the high bit would
    * otherwise be set.
    */
  private def writeMpintBytes(n: BigInt): Array[Byte] = {
    require(n.signum >= 0, "non-negative only for this property")
    if (n == 0) Array.emptyByteArray
    else {
      val raw = n.toByteArray
      // Java BigInt.toByteArray already does minimal-byte two's-complement.
      // For SSH mpint of a non-negative value, this is the right encoding
      // (it includes the leading 0 byte if needed).
      raw
    }
  }

  // --- properties ---

  property("[PROP] uint32 round-trip (G5 #1)") {
    forAll(genUInt32) { v =>
      val out = new java.io.ByteArrayOutputStream()
      writeUInt32(out, v)
      val r = new SshWireReader(out.toByteArray)
      r.readUInt32() == v
    }
  }

  property(
    "[PROP] uint64 round-trip including sentinels 0 and 0xFFFF…FFFF (G5 #2 / G1 regression guard)"
  ) {
    forAll(genUInt64) { v =>
      val out = new java.io.ByteArrayOutputStream()
      writeUInt64(out, v)
      val r = new SshWireReader(out.toByteArray)
      r.readUInt64() == v
    }
  }

  property("[PROP] string round-trip preserves bytes (G5 #3)") {
    forAll(genBytes) { b =>
      val out = new java.io.ByteArrayOutputStream()
      writeString(out, b)
      val r = new SshWireReader(out.toByteArray)
      r.readString().toSeq == b.toSeq
    }
  }

  property("[PROP] readString throws on truncated input (G5 #4)") {
    forAll(Gen.choose(1, 1024)) { claimedLen =>
      val out = new java.io.ByteArrayOutputStream()
      writeUInt32(out, claimedLen.toLong) // claim N bytes of payload
      // intentionally write fewer than claimedLen bytes
      val r = new SshWireReader(out.toByteArray)
      val threw = scala.util.Try(r.readString()).isFailure
      threw :| s"reading $claimedLen bytes from header-only buffer must throw"
    }
  }

  property(
    "[PROP] mpintBitLength matches BigInt.bitLength for non-negative inputs (G5 #5)"
  ) {
    forAll(Gen.choose(0, 4096), Arbitrary.arbitrary[Long]) { (bits, seed) =>
      val n =
        if (bits == 0) BigInt(0)
        else BigInt(bits, new scala.util.Random(seed)).abs
      val mpint = writeMpintBytes(n)
      val ours = SshWireReader.mpintBitLength(mpint)
      val expected = n.bitLength
      ours == expected
    }
  }

  property("[PROP] string-list round-trip preserves elements (G5 #6)") {
    val genStr = Gen.alphaNumStr // ASCII, no embedded whitespace surprises
    val genList = Gen.choose(0, 16).flatMap(n => Gen.listOfN(n, genStr))
    forAll(genList) { ss =>
      val inner = new java.io.ByteArrayOutputStream()
      ss.foreach(s => writeString(inner, s.getBytes("UTF-8")))
      val outer = new java.io.ByteArrayOutputStream()
      writeString(outer, inner.toByteArray)
      val r = new SshWireReader(outer.toByteArray)
      r.readStringList().toList == ss
    }
  }
}
