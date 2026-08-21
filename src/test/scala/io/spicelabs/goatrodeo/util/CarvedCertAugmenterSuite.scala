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

import munit.FunSuite

import java.io.File

/** Tests for [[CarvedCertAugmenter]] — the DER-cert carving MIME augmenter.
  *
  * WHAT: the MIME pass probes a bounded window of binary artifacts for DER
  * X.509 candidates (SEQUENCE header + SPKI OID needle) and emits
  * `application/x-goatrodeo-carved-x509`; the full carve happens during
  * strategy processing.
  *
  * WHY: firmware certs are DER byte arrays at arbitrary offsets — the
  * PEM-marker detectors never see them. These tests pin the probe-window
  * doctrine (in-window certs detected, deep ones missed, same as
  * CryptoFootprint) and the block-shaped rule.
  *
  * THEORY: fixtures are docker-built ELFs with known DER certs (see
  * `test_data/carved-certs/README.md`); offsets verified at generation time.
  *
  * LLM note: A-x = test id.
  */
class CarvedCertAugmenterSuite extends FunSuite {

  private val dir = "test_data/carved-certs"

  private def fixture(name: String): File = new File(dir, name)

  // A-1 — the rule blocks text/XML/JSON/class files only.
  test("A-1 rule blocks only declared families") {
    assert(!CarvedCertAugmenter.mimeRule(Set("application/java-vm")))
    assert(!CarvedCertAugmenter.mimeRule(Set("text/plain")))
    assert(!CarvedCertAugmenter.mimeRule(Set("application/xml")))
    assert(!CarvedCertAugmenter.mimeRule(Set("application/json")))
    assert(CarvedCertAugmenter.mimeRule(Set("application/octet-stream")))
    assert(CarvedCertAugmenter.mimeRule(Set("application/x-sharedlib")))
    assert(CarvedCertAugmenter.mimeRule(Set("application/x-saffron-elf")))
  }

  // A-2 — the 256 KB probe detects in-window certs and misses deep ones.
  test("A-2 probe window: in-window certs detected, deep certs missed") {
    val one = FileWrapper(
      fixture("elf-rsa1024-cert"),
      "elf-rsa1024-cert",
      None
    )
    assert(one.mimeType.contains(CarvedCertAugmenter.CarvedMime))

    val two = FileWrapper(fixture("elf-two-certs"), "elf-two-certs", None)
    assert(two.mimeType.contains(CarvedCertAugmenter.CarvedMime))

    val deep = FileWrapper(fixture("elf-deep-cert"), "elf-deep-cert", None)
    assert(
      !deep.mimeType.contains(CarvedCertAugmenter.CarvedMime),
      "cert beyond the 256 KB probe window must not be detected"
    )

    val none = FileWrapper(fixture("elf-no-certs"), "elf-no-certs", None)
    assert(!none.mimeType.contains(CarvedCertAugmenter.CarvedMime))
  }

  // A-3 — derObjectLength validates short/long forms and rejects lies.
  test("A-3 DER length parsing validates bounds") {
    // short form: 30 05 xx xx xx xx xx (5 content bytes)
    val short = Array[Byte](0x30, 0x05, 1, 2, 3, 4, 5)
    assertEquals(CarvedCertAugmenter.derObjectLength(short, 0), Some(7))
    // long form 2-byte: 30 82 01 00 + 256 bytes
    val long = Array.fill[Byte](4 + 256)(0x41)
    long(0) = 0x30; long(1) = 0x82.toByte; long(2) = 0x01; long(3) = 0x00
    assertEquals(CarvedCertAugmenter.derObjectLength(long, 0), Some(260))
    // declared length beyond the buffer is rejected
    val lying = Array[Byte](0x30, 0x82.toByte, 0x7f, 0x7f)
    assertEquals(CarvedCertAugmenter.derObjectLength(lying, 0), None)
    // not a SEQUENCE
    val notSeq = Array[Byte](0x31, 0x05, 1, 2, 3, 4, 5)
    assertEquals(CarvedCertAugmenter.derObjectLength(notSeq, 0), None)
  }
}
