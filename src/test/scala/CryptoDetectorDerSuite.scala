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

import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.CryptoDetector
import munit.FunSuite

/** Phase 0 (0.6) — CryptoDetector DER/PKCS#12 graceful failure paths.
  *
  * ## What this tests
  *
  * The `readDerLength` and `looksLikePkcs12` methods are private internals of
  * CryptoDetector, but their behavior is observable through the public `detect`
  * method. This suite verifies that malformed DER bytes, empty input, and
  * non-PKCS12 data are handled without crashes — returning safe (possibly
  * empty) MIME sets rather than throwing exceptions.
  *
  * ## Why this matters
  *
  * Before the Phase 0 remediation, a malformed DER length field could cause an
  * ArrayIndexOutOfBoundsException or negative-size allocation in
  * `readDerLength`. The `looksLikePkcs12` method now uses `readDerLength` which
  * returns `Option[(Int, Int)]`, so truncation is handled as None rather than
  * an exception.
  *
  * ## Requirement trace
  *
  * Phase 0 item 0.6: readDerLength returns None for invalid DER (zero-length
  * long form, offset overflow); looksLikePkcs12 returns false for non-PKCS12
  * and empty data; detect never throws on malformed input.
  *
  * ## LLM-friendly summary
  *
  * | Test                                | Input                     | Expected             |
  * |:------------------------------------|:--------------------------|:---------------------|
  * | empty bytes                         | Array[Byte]()             | Set.empty, no crash  |
  * | malformed DER (0x80 long-form, n=0) | Array(0x80)               | Set.empty, no crash  |
  * | DER offset overflow                 | Array(0x30,0x82,0xFF,...) | no pkcs12, no crash  |
  * | random non-PKCS12 data              | random bytes              | Set.empty, no pkcs12 |
  * | valid PKCS#12 via .p12 extension    | 0x30 0x82 + .p12 name     | contains pkcs12      |
  */
class CryptoDetectorDerSuite extends FunSuite {

  private def detect(
      bytes: Array[Byte],
      name: String = "test.bin"
  ): Set[String] =
    CryptoDetector.detect(ByteWrapper(bytes, name, None))

  test("CryptoDetector - detect handles empty bytes without crash") {

    /** What: Passes an empty byte array to detect. Why: Empty input must not
      * cause any index-out-of-bounds or NPE in readDerLength or
      * looksLikePkcs12. Requirement: Phase 0 §0.6 — detect never throws on
      * empty input.
      */
    val result = detect(Array[Byte](), "empty.bin")
    assertEquals(
      result,
      Set.empty[String],
      "Empty input should yield empty MIME set"
    )
  }

  test(
    "CryptoDetector - detect handles malformed DER long-form length (0x80, n=0) without crash"
  ) {

    /** What: Creates bytes where the first byte is 0x80, indicating a DER
      * long-form length with n=0 subsequent bytes — an illegal encoding that
      * readDerLength must reject with None. Why: A 0x80 byte with no following
      * length bytes is the canonical "zero-length long form" case;
      * readDerLength should return None, and looksLikePkcs12 should return
      * false, so detect should not crash. Requirement: Phase 0 §0.6 —
      * readDerLength returns None for n=0.
      */
    val bytes = Array[Byte](0x80.toByte)
    val result = detect(bytes, "malformed.bin")
    assert(
      !result.contains("application/pkcs12"),
      "Malformed DER should not be classified as PKCS#12"
    )
  }

  test("CryptoDetector - detect handles DER offset overflow without crash") {

    /** What: Creates bytes with a DER SEQUENCE header (0x30 0x82) declaring a
      * large length but with insufficient actual bytes, triggering the
      * offset-overflow guard in readDerLength. Why: If readDerLength did not
      * check bounds, the long-form length decoder would read past the array.
      * The None return prevents this. Requirement: Phase 0 §0.6 — readDerLength
      * returns None for offset overflow.
      */
    val bytes = Array[Byte](
      0x30,
      0x82.toByte,
      0x10,
      0x00
    )
    val result = detect(bytes, "overflow.bin")
    assert(
      !result.contains("application/pkcs12"),
      "DER with offset overflow should not be classified as PKCS#12"
    )
  }

  test("CryptoDetector - detect returns no pkcs12 for random non-PKCS12 data") {

    /** What: Feeds random-looking bytes (not DER-structured) to detect. Why:
      * looksLikePkcs12 should return false for data that doesn't start with a
      * DER SEQUENCE, so no pkcs12 MIME should appear. Requirement: Phase 0 §0.6
      * — looksLikePkcs12 returns false for non-PKCS12 data.
      */
    val bytes = Array[Byte](0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42)
    val result = detect(bytes, "random.bin")
    assert(
      !result.contains("application/pkcs12"),
      "Random data should not be classified as PKCS#12"
    )
  }

  test(
    "CryptoDetector - detect classifies .p12 extension with DER prefix as pkcs12"
  ) {

    /** What: Creates bytes with 0x30 0x82 DER prefix and a .p12 filename. Why:
      * The .p12 extension hint combined with the DER prefix should produce
      * application/pkcs12, proving the positive path works alongside the
      * negative tests. Requirement: Phase 0 §0.6 — detect correctly identifies
      * PKCS#12 when extension and structure agree.
      */
    val bytes = Array[Byte](0x30, 0x82.toByte, 0x01, 0x00, 0x00, 0x00)
    val result = detect(bytes, "keystore.p12")
    assert(
      result.contains("application/pkcs12"),
      s"DER prefix + .p12 extension should yield pkcs12; got $result"
    )
  }
}
