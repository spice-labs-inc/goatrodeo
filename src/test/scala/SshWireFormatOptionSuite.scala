/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors
   Apache 2.0. */

package io.spicelabs.goatrodeo.util

import munit.FunSuite

import java.nio.charset.StandardCharsets

/** Phase 0.9 — SshWireReader methods return Option instead of throwing.
  *
  * REQUIREMENT: No exceptions for flow control. Short reads or buffer overflows
  * on untrusted SSH wire data return None instead of throwing
  * IllegalArgumentException via require().
  *
  * ==LLM-Readable Section==
  *
  * This suite tests that SshWireReader's four core methods (readByte,
  * readUInt32, readUInt64, readString) return Option instead of throwing when
  * given insufficient data. Derived methods (readUtf8String, readMpint,
  * readStringList, readNameDataList) are also tested.
  *
  * On successful reads, positions advance. On failed reads (None return),
  * positions must NOT advance (reader stays in consistent state).
  */
class SshWireFormatOptionSuite extends FunSuite {

  private def sshUint32(v: Long): Array[Byte] = Array(
    ((v >> 24) & 0xff).toByte,
    ((v >> 16) & 0xff).toByte,
    ((v >> 8) & 0xff).toByte,
    (v & 0xff).toByte
  )

  private def sshString(s: String): Array[Byte] = {
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    sshUint32(bytes.length) ++ bytes
  }

  /** Test: readByte returns None on empty buffer.
    *
    * WHAT: Empty buffer → None WHAT NOT: Does not throw
    *
    * WHY: Malformed/truncated SSH data is an expected failure when parsing
    * untrusted input. The reader must not crash.
    *
    * REQUIREMENT: Empty buffer returns None.
    */
  test("SshWireReader - readByte returns None on empty buffer") {
    val r = new SshWireReader(Array.emptyByteArray)
    assertEquals(r.readByte(), None)
  }

  /** Test: readByte returns Some on valid data.
    *
    * WHAT: Single byte → Some(value)
    *
    * WHY: Normal operation must still work.
    *
    * REQUIREMENT: Valid data returns Some(value).
    */
  test("SshWireReader - readByte returns Some on valid data") {
    val r = new SshWireReader(Array(0x42.toByte))
    assertEquals(r.readByte(), Some(0x42))
  }

  /** Test: readUInt32 returns None on insufficient bytes.
    *
    * WHAT: Less than 4 bytes → None
    *
    * WHY: Truncated wire data is an expected failure case.
    *
    * REQUIREMENT: Less than 4 bytes returns None.
    */
  test("SshWireReader - readUInt32 returns None on insufficient bytes") {
    val r = new SshWireReader(Array(0x01.toByte, 0x02.toByte))
    assertEquals(r.readUInt32(), None)
  }

  test("SshWireReader - readUInt32 returns Some on valid data") {
    val r = new SshWireReader(sshUint32(0x01020304L))
    assertEquals(r.readUInt32(), Some(0x01020304L))
  }

  /** Test: readUInt64 returns None on insufficient bytes. */
  test("SshWireReader - readUInt64 returns None on insufficient bytes") {
    val r = new SshWireReader(Array(0x01.toByte, 0x02.toByte, 0x03.toByte))
    assertEquals(r.readUInt64(), None)
  }

  test("SshWireReader - readUInt64 returns Some on valid data") {
    val hi = 0x01020304L
    val lo = 0x05060708L
    val bytes = sshUint32(hi) ++ sshUint32(lo)
    val r = new SshWireReader(bytes)
    assertEquals(r.readUInt64(), Some((hi << 32) | lo))
  }

  /** Test: readString returns None when length exceeds remaining.
    *
    * WHAT: Declared string length exceeds available bytes → None
    *
    * WHY: Malformed length field in untrusted SSH data.
    *
    * REQUIREMENT: Malformed length field returns None.
    */
  test(
    "SshWireReader - readString returns None when length exceeds remaining"
  ) {
    val len = sshUint32(100L)
    val r = new SshWireReader(len ++ Array(0x41.toByte))
    assertEquals(r.readString(), None)
  }

  test("SshWireReader - readString returns Some on valid data") {
    val r = new SshWireReader(sshString("hello"))
    assertEquals(
      r.readString().map(s => new String(s, StandardCharsets.UTF_8)),
      Some("hello")
    )
  }

  /** Test: position does not advance on failed read.
    *
    * WHAT: After a read returns None, position stays the same.
    *
    * WHY: If position advanced on failure, subsequent reads would be
    * misaligned, potentially causing cascading errors.
    *
    * REQUIREMENT: Implied by Option contract — reader stays consistent.
    */
  test("SshWireReader - position does not advance on failed read") {
    val r = new SshWireReader(Array(0x01.toByte))
    assertEquals(r.position, 0)
    assertEquals(r.readUInt32(), None)
    assertEquals(r.position, 0)
    assertEquals(r.readByte(), Some(1))
    assertEquals(r.position, 1)
  }

  /** Test: readUtf8String returns None on truncated data. */
  test("SshWireReader - readUtf8String returns None on truncated data") {
    val r = new SshWireReader(Array.emptyByteArray)
    assertEquals(r.readUtf8String(), None)
  }

  /** Test: readMpint returns None on truncated data. */
  test("SshWireReader - readMpint returns None on truncated data") {
    val r = new SshWireReader(Array.emptyByteArray)
    assertEquals(r.readMpint(), None)
  }

  /** Test: readStringList returns None on malformed inner data. */
  test("SshWireReader - readStringList returns None on malformed inner data") {
    val r = new SshWireReader(sshString("incomplete"))
    val result = r.readStringList()
    assert(
      result.isEmpty,
      "readStringList must return None for malformed inner data"
    )
  }

  /** Test: readNameDataList returns None on truncated inner data. */
  test("SshWireReader - readNameDataList returns None on empty buffer") {
    val r = new SshWireReader(Array.emptyByteArray)
    assertEquals(r.readNameDataList(), None)
  }
}
