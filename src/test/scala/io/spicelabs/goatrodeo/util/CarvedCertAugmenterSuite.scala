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
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

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
class CarvedCertAugmenterSuite extends GoatRodeoFunSuite {

  private val dir = "test_data/carved-certs"

  private def fixture(name: String): File = new File(dir, name)

  // A-1 — the rule is two-sided: it refuses the families a DER certificate cannot
  // live in, AND it refuses an artifact whose MIME set holds no carrier type at
  // all, even when nothing is contradicted.
  //
  // WHAT: `CarvedCertAugmenter.mimeRule` refuses class files (a constant pool is
  // not a DER object), text/XML/JSON (a DER certificate is binary), and archives
  // (a JAR is walked into children, so a stored certificate arrives as its own
  // artifact rather than being carved out of the archive blob); it accepts the
  // binary carrier types whose sections hold arbitrary bytes, including the
  // parameterised PE spelling this repository observes.
  //
  // WHY: the augmenter costs a bounded probe per artifact, so the rule has to
  // answer both questions cheaply: "could this possibly be mine?" and "is there
  // anything here that proves it is not?". A one-sided rule answers only the
  // second, and therefore probes every font, every image and every PDF.
  //
  // THEORY: the two predicates are ANDed. The contradiction list may be long and
  // aggressive because a wrong entry costs only a lost optimisation on something
  // that could never have worked; the admission list may not be wrong in the
  // restrictive direction, because a miss there is a silent false negative.
  //
  // Changed 2026-10-28 with David's explicit approval: this test previously
  // asserted that every type it did not explicitly block was admitted, which is
  // the superseded one-sided contract.
  test("A-1 rule refuses contradictions and non-admissible types") {
    // contradicted: a DER certificate is binary, so none of these can hold one
    assert(!CarvedCertAugmenter.mimeRule(Set("application/java-vm")))
    assert(!CarvedCertAugmenter.mimeRule(Set("text/plain")))
    assert(!CarvedCertAugmenter.mimeRule(Set("application/xml")))
    assert(!CarvedCertAugmenter.mimeRule(Set("application/json")))
    // contradicted: archives are walked into children, never carved as one blob
    assert(!CarvedCertAugmenter.mimeRule(Set("application/java-archive")))
    // admissible: the binary carrier types, including the parameterised PE form
    assert(CarvedCertAugmenter.mimeRule(Set("application/octet-stream")))
    assert(CarvedCertAugmenter.mimeRule(Set("application/x-sharedlib")))
    assert(CarvedCertAugmenter.mimeRule(Set("application/x-executable")))
    assert(
      CarvedCertAugmenter.mimeRule(Set("application/x-msdownload; format=pe32"))
    )
    // neither contradicted nor admissible: nothing this augmenter could use, so
    // it is skipped without a read
    assert(!CarvedCertAugmenter.mimeRule(Set("application/x-saffron-elf")))
    assert(!CarvedCertAugmenter.mimeRule(Set("font/ttf")))
  }

  /** The SPKI needle the carver looks for (rsaEncryption OID, DER-encoded). */
  private val rsaSpkiNeedle: Array[Byte] = Array[Byte](
    0x06,
    0x09,
    0x2a,
    0x86.toByte,
    0x48,
    0x86.toByte,
    0xf7.toByte,
    0x0d,
    0x01,
    0x01,
    0x01
  )

  /** A DER SEQUENCE of 32 bytes whose body contains the needle. */
  private def derWithNeedle(): Array[Byte] = {
    val body = rsaSpkiNeedle ++ Array.fill[Byte](21)('x'.toByte)
    Array[Byte](0x30, 0x82.toByte, 0x00, 0x20) ++ body
  }

  // A-3 — a DER object with an SPKI needle in the first block is KNOWLEDGE, so
  // the MIME is asserted and the strategy never probes that artifact again.
  test("A-3 a known DER carrier gets the carved MIME and short-circuits") {
    val bytes = derWithNeedle() ++ Array.fill[Byte](4096)(0.toByte)
    val w = ByteWrapper(bytes, "carrier.bin", None)
    assert(
      w.mimeType.contains(CarvedCertAugmenter.CarvedMime),
      s"a DER+SPKI object in the first block must assert the MIME, got ${w.mimeType}"
    )
    val carriers = w.mimeTypeEvidence
      .getOrElse(CarvedCertAugmenter.CarvedMime, Vector.empty)
      .collect { case c: CarvedCertAugmenter.Carrier => c }
    assertEquals(carriers.length, 1)
    assertEquals(carriers.head.derPrefixSeen, true)
  }

  // A-5 — the ELF magic is proof, so the ELF container type is claimed from the
  // first block even when the binary is far larger than any probe prefix.
  //
  // WHAT: `/bin/ls` (142 KB, larger than Saffron's 128 KiB probe prefix) carries
  // the ELF magic in its first four bytes. `CarvedCertAugmenter` claims
  // `application/x-saffron-elf` for it, from a one-block probe.
  //
  // WHY: `SaffronProbe.detect` needs the whole file to classify an ELF — measured:
  // it returns NONE for `/bin/ls` even when handed the full 131,072-byte prefix,
  // and CONTAINER_ELF only when handed all 142,312 bytes. The ELF magic is
  // nonetheless conclusive at offset 0, so the claim belongs to whichever probe
  // can see it, and it belongs in the MIME set before the probes that would
  // otherwise guess.
  //
  // THEORY: a known type is asserted from the bytes that prove it, and the
  // augmenter that reads those bytes runs early enough for the type to be part of
  // every later decision.
  test(
    "A-5 the ELF container type is claimed from the magic, not from a full read"
  ) {
    val ls = new java.io.File("/bin/ls")
    assert(ls.exists(), "/bin/ls required for this test")
    val w = io.spicelabs.goatrodeo.util.FileWrapper(ls, ls.getPath, None)
    assert(
      w.mimeType.contains(SaffronDetector.ElfMime),
      s"the ELF container type must be claimed, got ${w.mimeType}"
    )
    val carriers = w.mimeTypeEvidence
      .getOrElse(CarvedCertAugmenter.CarvedMime, Vector.empty)
      .collect { case c: CarvedCertAugmenter.Carrier => c }
    assertEquals(carriers.length, 1, "one carrier record per artifact")
    assertEquals(carriers.head.isElf, true)
    assert(
      carriers.head.probedBytes <= MimeProbe.MaxBytes,
      s"the ELF claim must come from one block, saw ${carriers.head.probedBytes}"
    )
  }

  // A-2 — the augmenter records what the carrier is and adds NO MIME.
  //
  // The augmenter no longer scans for certificates. A MIME type is only added
  // when it is known to be true, and "this binary might contain a cert" is not
  // knowledge — so the record says what the carrier is and the carving strategy
  // (which runs only over artifacts nothing else claimed) decides whether a
  // deeper look is worth it.
  test("A-2 the augmenter records the carrier and adds no MIME") {
    val inWindow = FileWrapper(
      fixture("elf-rsa1024-cert"),
      "elf-rsa1024-cert",
      None
    )
    assert(
      !inWindow.mimeType.contains(CarvedCertAugmenter.CarvedMime),
      "the augmenter must not claim carved certificates it has not found"
    )
    val evidence = inWindow.mimeTypeEvidence
      .getOrElse(CarvedCertAugmenter.CarvedMime, Vector.empty)
      .collect { case c: CarvedCertAugmenter.Carrier => c }
    assertEquals(evidence.length, 1, "one carrier record per artifact")
    assertEquals(evidence.head.isElf, true, "the fixture is an ELF")
    assert(
      evidence.head.probedBytes <= MimeProbe.MaxBytes,
      s"the carrier record must come from a one-block probe, saw ${evidence.head.probedBytes}"
    )

    // A carrier with the certificate beyond the probe reports the same facts:
    // the augmenter makes no claim either way.
    val deep = FileWrapper(fixture("elf-deep-cert"), "elf-deep-cert", None)
    assert(!deep.mimeType.contains(CarvedCertAugmenter.CarvedMime))
    assert(
      deep.mimeTypeEvidence.contains(CarvedCertAugmenter.CarvedMime),
      "a deep-cert carrier must still be recorded as a carrier"
    )
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
