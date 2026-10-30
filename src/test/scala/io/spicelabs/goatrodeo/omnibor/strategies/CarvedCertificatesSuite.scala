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
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.StringOf
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.testing.GoatRodeoScalaCheckSuite
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.CarvedCertAugmenter
import io.spicelabs.goatrodeo.util.FileWrapper
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.io.File
import java.nio.file.Files
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Tests for the [[CarvedCertificatesStrategy]] — carved DER cert emission.
  *
  * WHAT: binary artifacts tagged with the carved-cert MIME are claimed and,
  * during processing, scanned (16 MiB cap) for embedded DER X.509 certs; each
  * parsed cert emits the existing per-cert metadata block
  * (`Certificates:Cert:<idx>:*`), so KeySize (including 1024) flows to the
  * CBOM.
  *
  * WHY: the phase exists to surface firmware certs — an RSA-1024 cert baked
  * into an ELF must appear as a certificate component with KeySize 1024.
  *
  * THEORY: fixtures are docker-built ELFs with openssl-verified certs; the
  * carve scanner is a pure byte function, so unit tests drive it with synthetic
  * batteries (truncated DER, lying lengths, duplicates, caps).
  *
  * LLM note: C-x = test id.
  */
class CarvedCertificatesSuite extends GoatRodeoScalaCheckSuite {

  private val dir = "test_data/carved-certs"

  private def fixture(name: String): File = new File(dir, name)

  private def testItem(id: String): Item = Item(
    id,
    TreeSet(),
    Some(ItemMetaData.mimeType),
    Some(
      ItemMetaData(
        fileNames = TreeSet(id),
        mimeType = TreeSet("application/octet-stream"),
        fileSize = 100,
        extra = TreeMap()
      )
    )
  )

  private def metadataFor(
      name: String
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val wrapper = FileWrapper(fixture(name), name, None)
    val state = new CarvedCertificatesState(wrapper)
    val (md, _) = state.getMetadata(wrapper, testItem("x"), SingleMarker())
    md
  }

  private def mdString(
      md: TreeMap[String, TreeSet[StringOrPair]],
      key: String
  ): Option[String] =
    md.get(key).flatMap(_.headOption).collect { case StringOf(s) => s }

  /** The carrier record the augmenter produced, read before the strategy wipes
    * it.
    */
  private def carrierOf(
      w: ArtifactWrapper
  ): Option[CarvedCertAugmenter.Carrier] = {
    w.mimeType
    w.mimeTypeEvidence
      .getOrElse(CarvedCertAugmenter.CarvedMime, Vector.empty)
      .collectFirst { case c: CarvedCertAugmenter.Carrier => c }
  }

  /** The spans the carrier's own tables point at. */
  private def spansOf(
      w: ArtifactWrapper
  ): Vector[CarvedCertificatesStrategy.CarvedSpan] =
    carrierOf(w)
      .map(c => CarvedCertificatesStrategy.carrierSpans(w, c))
      .getOrElse(Vector.empty)

  /** Run the strategy's selection over a single artifact. */
  private def claimAll(w: ArtifactWrapper): Vector[ToProcess] = {
    val (claimed, _, _, _) = CarvedCertificatesStrategy
      .computeCarvedCertificateFiles(
        Map(w.uuid -> w),
        Map(w.path() -> Vector(w))
      )
    claimed
  }

  // C-1 — the carve scanner parses only real certs, dedupes, and honours caps.
  test("C-1 carveCertificates parses, dedupes, and caps") {
    val f = fixture("elf-two-certs")
    assert(
      f.exists(),
      "carved corpus fixtures required — run gen_carved_elf_corpus.sh"
    )
    val bytes = Files.readAllBytes(f.toPath())
    val (certs, cap) = CarvedCertAugmenter.carveCertificates(
      bytes,
      CarvedCertAugmenter.MaxScanBytes,
      CarvedCertAugmenter.MaxCerts
    )
    assertEquals(certs.length, 2)
    assert(!cap)
    assert(
      certs
        .map(_.getSubjectX500Principal.getName)
        .toSet
        .contains("CN=carved-rsa1024")
    )
    assert(
      certs
        .map(_.getSubjectX500Principal.getName)
        .toSet
        .contains("CN=carved-rsa2048")
    )
    // dedupe: same bytes twice yields one cert
    val doubled = bytes ++ bytes
    val (certs2, _) = CarvedCertAugmenter.carveCertificates(
      doubled,
      CarvedCertAugmenter.MaxScanBytes,
      CarvedCertAugmenter.MaxCerts
    )
    assertEquals(certs2.length, 2)
    // cap: maxCerts=1 flags the second cert
    val (_, cap2) = CarvedCertAugmenter.carveCertificates(
      bytes,
      CarvedCertAugmenter.MaxScanBytes,
      1
    )
    assert(cap2)
  }

  // C-2 — random bytes never yield certificates.
  property("C-2 random bytes yield no carved certificates") {
    forAll(Gen.listOfN(4096, Gen.choose(0, 255))) { ints =>
      val bytes = ints.map(_.toByte).toArray
      val (certs, _) = CarvedCertAugmenter.carveCertificates(
        bytes,
        CarvedCertAugmenter.MaxScanBytes,
        16
      )
      certs.isEmpty
    }
  }

  // C-3 — the RSA-1024 fixture emits a per-cert block with KeySize 1024.
  test("C-3 RSA-1024 fixture emits KeySize 1024 metadata") {
    val md = metadataFor("elf-rsa1024-cert")
    assertEquals(mdString(md, "Certificates:CarvedCertCount"), Some("1"))
    assertEquals(mdString(md, "Certificates:Cert:0:KeySize"), Some("1024"))
    assertEquals(mdString(md, "Certificates:Cert:0:KeyAlgorithm"), Some("rsa"))
    assertEquals(
      mdString(md, "Certificates:Cert:0:SubjectDN"),
      Some("CN=carved-rsa1024")
    )
  }

  // C-4 — the two-cert fixture emits both certs, 1024 and 2048.
  test("C-4 two-cert fixture emits both key sizes") {
    val md = metadataFor("elf-two-certs")
    assertEquals(mdString(md, "Certificates:CarvedCertCount"), Some("2"))
    val sizes = (0 until 2)
      .flatMap(i => mdString(md, s"Certificates:Cert:$i:KeySize"))
      .toSet
    assertEquals(sizes, Set("1024", "2048"))
  }

  // C-5 — the deep certificate IS found. Inverted 2026-10-28 with David's
  // explicit approval.
  //
  // WHAT: `elf-deep-cert` places its certificate at offset 319,520, far beyond
  // any streaming window the old probe could afford. The carrier's symbol table
  // names it (`cert2048_der`, size 791), so the strategy reaches it by skipping
  // to the symbol's span instead of scanning toward it.
  //
  // WHY: this is the whole reason the strategy walks tables rather than bytes. A
  // certificate embedded deep in a firmware image is not rare, and a window-based
  // probe misses it while reporting success.
  //
  // THEORY: the claim now comes from the carrier's own tables, not from a byte
  // window, so the distance from the front of the file is irrelevant; only the
  // span's existence and size matter.
  test("C-5 deep cert beyond any streaming window is found by its symbol") {
    val wrapper = FileWrapper(fixture("elf-deep-cert"), "elf-deep-cert", None)
    val spans = spansOf(wrapper)
    assert(
      spans.exists(s => s.offset == 319520L && s.length == 791L),
      s"the cert2048_der symbol must yield its span, got $spans"
    )
    val claimed = claimAll(wrapper)
    assertEquals(claimed.length, 1, "the deep carrier must be claimed")
  }

  // C-6 — the strategy claims by MIME only, with no reads during selection.
  test("C-6 compute claims by carved MIME only") {
    val a = FileWrapper(fixture("elf-rsa1024-cert"), "elf-rsa1024-cert", None)
    val b = FileWrapper(fixture("elf-no-certs"), "elf-no-certs", None)
    a.mimeType
    b.mimeType
    val byUUID: Map[String, ArtifactWrapper] =
      Map(a.uuid -> a, b.uuid -> b)
    val byName: Map[String, Vector[ArtifactWrapper]] =
      Map(a.path() -> Vector(a), b.path() -> Vector(b))
    val (claimed, _, _, name) =
      CarvedCertificatesStrategy.computeCarvedCertificateFiles(byUUID, byName)
    assertEquals(name, "CarvedCertificates")
    assertEquals(claimed.length, 1)
    assertEquals(claimed.head.main, "elf-rsa1024-cert")
  }
}
