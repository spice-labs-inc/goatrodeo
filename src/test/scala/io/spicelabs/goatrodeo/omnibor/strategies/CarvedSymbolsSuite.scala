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

package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.CarvedCertAugmenter
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File

/** Tests for symbol-directed certificate carving.
  *
  * WHAT: the augmenter records, in one block, where the carrier's link-time
  * tables are (`e_shoff`, entry size, section count, and the PE COFF symbol
  * table pointer and count). The strategy then walks those tables through fresh
  * streams — `skip` is an lseek, so reaching a table at the end of the file and
  * the section data before it costs no bytes — and claims the artifact when a
  * symbol named like a certificate points at a plausible span. The carve reads
  * exactly that span.
  *
  * WHY: a certificate embedded in firmware sits wherever the linker put it. In
  * this corpus it is in `.data`, at offset 12,320 in the ~16 KiB fixtures and
  * at 319,520 in `elf-deep-cert`. A sequential probe has to read toward it: 16
  * KiB for the small ones and past any affordable window for the deep one,
  * which the old 256 KiB probe missed outright. The symbol table says exactly
  * where the bytes are, so the strategy jumps instead of scanning, and the
  * distance from the front of the file stops mattering.
  *
  * THEORY: no needle fallback for a stripped binary — reasonable effort, not
  * exhaustive discovery. A carrier is claimed when its own tables name a
  * certificate-named symbol, or when its first block already held a DER object
  * with an SPKI needle. Otherwise it is not claimed, and the strategy says so
  * by claiming nothing rather than by scanning hopefully.
  *
  * LLM note: test ids are SY-n. SY-1..SY-5 pin the evidence and the span
  * arithmetic; SY-6 and SY-7 pin the selection and the evidence wipe; SY-8 pins
  * that a span really contains the certificate it claims to.
  */
class CarvedSymbolsSuite extends GoatRodeoFunSuite {

  private def fixture(name: String): File =
    new File(s"test_data/carved-certs/$name")

  private def wrapper(name: String): ArtifactWrapper =
    FileWrapper(fixture(name), name, None)

  private def carrierOf(
      w: ArtifactWrapper
  ): Option[CarvedCertAugmenter.Carrier] = {
    w.mimeType
    w.mimeTypeEvidence
      .getOrElse(CarvedCertAugmenter.CarvedMime, Vector.empty)
      .collectFirst { case c: CarvedCertAugmenter.Carrier => c }
  }

  private def spansOf(
      w: ArtifactWrapper
  ): Vector[CarvedCertificatesStrategy.CarvedSpan] =
    carrierOf(w)
      .map(c => CarvedCertificatesStrategy.carrierSpans(w, c))
      .getOrElse(Vector.empty)

  private def claimAll(w: ArtifactWrapper): Vector[ToProcess] = {
    val (claimed, _, _, _) = CarvedCertificatesStrategy
      .computeCarvedCertificateFiles(
        Map(w.uuid -> w),
        Map(w.path() -> Vector(w))
      )
    claimed
  }

  // SY-1 — the augmenter records where the tables are, from one block, and does
  // not read the file to do it. The values are the corpus ground truth.
  test("SY-1 the carrier records the ELF table pointers within one block") {
    val w = wrapper("elf-rsa1024-cert")
    val c = carrierOf(w).getOrElse(fail("no Carrier evidence recorded"))
    assertEquals(c.isElf, true)
    assertEquals(c.sectionTableOffset, 14608L)
    assertEquals(c.sectionEntrySize, 64)
    assertEquals(c.sectionCount, 31)
    assert(
      c.probedBytes <= 4096,
      s"the probe must stay within one block, was ${c.probedBytes}"
    )
    assertEquals(c.tablesKnown, true)
  }

  // SY-2 — the symbol names give exactly the certificate spans, and nothing else.
  test("SY-2 symbol names yield exactly the certificate spans") {
    val one = spansOf(wrapper("elf-rsa1024-cert"))
    assertEquals(one.length, 1, s"exactly one certificate symbol, got $one")
    assertEquals(one.head.offset, 12320L)
    assertEquals(one.head.length, 530L)

    val two = spansOf(wrapper("elf-two-certs"))
    assertEquals(two.length, 2, s"exactly two certificate symbols, got $two")
    assertEquals(
      two.map(s => (s.offset, s.length)).toSet,
      Set((12320L, 530L), (12864L, 791L))
    )
  }

  // SY-3 — a carrier with no certificate-named symbol yields no span, and is not
  // claimed. `elf-no-certs` has symbols; none of them is a certificate.
  test("SY-3 a carrier with no certificate symbol is not claimed") {
    val w = wrapper("elf-no-certs")
    assertEquals(spansOf(w), Vector.empty)
    assertEquals(claimAll(w).length, 0)
  }

  // SY-4 — the deep certificate is one skip away, not a 319 KB scan. Its span is
  // the whole reason the strategy walks tables instead of bytes.
  test("SY-4 the deep certificate span is reached without scanning") {
    val w = wrapper("elf-deep-cert")
    val spans = spansOf(w)
    assertEquals(spans.length, 1, s"one certificate symbol, got $spans")
    assertEquals(spans.head.offset, 319520L)
    assertEquals(spans.head.length, 791L)
  }

  // SY-5 — with no section table there is no symbol table, so no span: this is
  // the stripped-binary case, and the answer is "not claimed" rather than a
  // hopeful scan. The fixture's `e_shoff` is zeroed to model a stripped image.
  test("SY-5 a carrier with no section table is not claimed") {
    val bytes =
      java.nio.file.Files.readAllBytes(fixture("elf-rsa1024-cert").toPath)
    var i = 40 // e_shoff in ELF64
    while (i < 48) { bytes(i) = 0; i += 1 }
    val w = io.spicelabs.goatrodeo.util.ByteWrapper(bytes, "stripped-elf", None)
    val c = carrierOf(w).getOrElse(fail("the carrier record must exist"))
    assertEquals(c.isElf, true)
    assertEquals(c.tablesKnown, false)
    assertEquals(spansOf(w), Vector.empty)
    assertEquals(claimAll(w).length, 0)
  }

  // SY-6 — selection claims the artifact, and then the evidence is discarded:
  // the spans are already in hand and nothing downstream consults a Carrier, so
  // keeping one record per artifact alive for the whole run is waste.
  test("SY-6 evidence is wiped once the claims are made") {
    val w = wrapper("elf-rsa1024-cert")
    assert(carrierOf(w).isDefined, "evidence exists before selection")
    val claimed = claimAll(w)
    assertEquals(claimed.length, 1)
    assertEquals(
      w.mimeTypeEvidence,
      Map.empty[String, Vector[
        io.spicelabs.goatrodeo.util.AugmentationEvidence
      ]],
      "evidence must be released after the strategy claims the artifact"
    )
    assert(
      w.mimeType.contains("application/x-sharedlib"),
      "wiping evidence must not disturb the MIME set"
    )
  }

  // SY-7 — claiming by the first block still works and still carves: a carrier
  // whose front holds a DER object with an SPKI needle carries the MIME outright
  // and takes the flat carve path.
  test("SY-7 a first-block DER claim still carves") {
    val cert =
      java.nio.file.Files.readAllBytes(fixture("elf-rsa1024-cert").toPath)
    val der = java.util.Arrays.copyOfRange(cert, 12320, 12320 + 530)
    // A carrier, not a bare certificate: a bare DER blob is typed
    // `application/x-x509-cert` by Tika and belongs to the Certificates strategy,
    // which is why the carve augmenter's admission list refuses that family. The
    // case this test pins is a binary carrier whose front happens to hold a DER
    // object, which is the `derPrefixSeen` claim.
    val elfHeader =
      Array[Byte](0x7f, 'E'.toByte, 'L'.toByte, 'F'.toByte, 2, 1, 1)
    val bytes =
      elfHeader ++ Array.fill[Byte](100)(0.toByte) ++ der ++
        Array.fill[Byte](4096)(0.toByte)
    val w = io.spicelabs.goatrodeo.util.ByteWrapper(bytes, "carrier.bin", None)
    assert(
      w.mimeType.contains(CarvedCertAugmenter.CarvedMime),
      s"a DER object in the first block must claim the MIME, got ${w.mimeType}"
    )
    val claimed = claimAll(w)
    assertEquals(claimed.length, 1)
    val spans = claimed.head.asInstanceOf[CarvedCertificatesToProcess].spans
    assertEquals(
      spans,
      Vector.empty,
      "a first-block claim carries no symbol spans and takes the flat carve"
    )
  }

  // SY-8 — the span we computed actually holds the certificate it claims to.
  // Offset arithmetic that is off by a section's virtual address would still
  // produce a plausible-looking span, so the bytes are parsed.
  test("SY-8 the computed span parses as an X.509 certificate") {
    val w = wrapper("elf-rsa1024-cert")
    val span = spansOf(w).head
    val bytes = CarvedCertificatesStrategy.readSpan(w, span)
    assertEquals(bytes.length, 530)
    val (certs, _) =
      CarvedCertAugmenter.carveCertificates(bytes, bytes.length, 8)
    assertEquals(certs.length, 1, "the span must hold exactly one certificate")
    assertEquals(
      certs.head.getPublicKey
        .asInstanceOf[java.security.interfaces.RSAPublicKey]
        .getModulus
        .bitLength(),
      1024
    )
  }
}
