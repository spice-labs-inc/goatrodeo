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
import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import scala.util.Try

/** Structural-integrity assertions on the Certificates fixture corpus.
  *
  * ## LLM-friendly summary
  *
  * These tests do NOT run the pipeline. They check the shape of
  * `test_data/certificates/`:
  *
  *   - **`corpus contains at least 200 fixtures`** — the corpus floor is 200
  *     fixtures. Fails loudly when the corpus is smaller so the strategy cannot
  *     silently ship under-tested.
  *
  *   - **`no orphan sidecars`** — every `.expected.json` must have a matching
  *     fixture file beside it. An orphan sidecar usually means a fixture was
  *     deleted without its sidecar; the test turns that into a red so the next
  *     CI run catches it.
  *
  *   - **`no orphan fixtures`** — every fixture must have a sidecar. An orphan
  *     fixture usually means someone committed a new file but forgot the
  *     sidecar.
  *
  *   - **`every sidecar parses and declares required fields`** — every sidecar
  *     is structurally valid per [[CertificatesSidecar.parse]]. The parser
  *     enforces that required fields (`description`, `source`, `retrievedAt`,
  *     `itemCount`, `mimeTypes.mustContain`, `purls.mustContain`,
  *     `metadata.mustContain`, `forbiddenMetadataPatterns`) are present; this
  *     test surfaces parse errors for every bad sidecar in one run rather than
  *     failing on the first.
  *
  * ## Why this is separate from `CertificatesSuite`
  *
  * Per-fixture pipeline tests in `CertificatesSuite` iterate over a possibly
  * empty list. When the corpus is empty, that suite emits zero tests. Integrity
  * tests here run unconditionally so the build still fails when the corpus is
  * missing.
  *
  * ## Phase traceability
  *
  *   - Test `corpus contains at least 200 fixtures` → sub-goal #2
  *     (phase-0-corpus.md).
  *   - Test `no orphan sidecars` → phase-0-corpus.md task #4 sourcing protocol.
  *   - Test `no orphan fixtures` → phase-0-corpus.md task #4 sourcing protocol.
  *   - Test `every sidecar parses` → schema conformance.
  */
class CertificatesCorpusIntegritySuite extends GoatRodeoFunSuite {

  test("corpus root exists") {
    assert(
      CertificatesFixtureInventory.corpusRoot.exists(),
      s"Fixture corpus root ${CertificatesFixtureInventory.corpusRoot.getPath} " +
        s"does not exist. The corpus requires this directory to be created with " +
        s"category subdirectories."
    )
    assert(
      CertificatesFixtureInventory.corpusRoot.isDirectory(),
      s"${CertificatesFixtureInventory.corpusRoot.getPath} exists but is " +
        s"not a directory."
    )
  }

  test("corpus contains at least 200 fixtures") {
    val count = CertificatesFixtureInventory.totalCount
    assert(
      count >= 200,
      s"Certificates fixture corpus has $count paired (fixture, sidecar) " +
        s"pairs; the requirement is at least 200. See " +
        s"certificates-strategy/phase-0-corpus.md sub-goal #2. Per-category " +
        s"counts: ${CertificatesFixtureInventory.countByCategory}"
    )
  }

  test("no orphan sidecars") {
    val orphans = CertificatesFixtureInventory.orphanSidecars
    assert(
      orphans.isEmpty,
      s"Found ${orphans.size} orphan sidecar(s) — a sidecar without a " +
        s"matching fixture file: " +
        orphans.map(_.getPath).mkString("\n  ", "\n  ", "")
    )
  }

  test("no orphan fixtures") {
    val orphans = CertificatesFixtureInventory.orphanFixtures
    assert(
      orphans.isEmpty,
      s"Found ${orphans.size} orphan fixture(s) — a fixture without a " +
        s"matching `.expected.json` sidecar: " +
        orphans.map(_.getPath).mkString("\n  ", "\n  ", "")
    )
  }

  test(
    "every sidecar id, when present, is its corpus-relative path, and unique"
  ) {
    // The id is the test ID the integration tests share with this suite
    // (tests/README.md): `certificates/<category>/.../<fixture>`. An absent
    // id means the fixture is deliberately not shared; only declared ids are
    // validated.
    val root =
      CertificatesFixtureInventory.corpusRoot.getParentFile.toPath.toAbsolutePath
    val ids = CertificatesFixtureInventory.allSidecars.flatMap { s =>
      val expected = root
        .relativize(s.toPath.toAbsolutePath)
        .toString
        .replace(java.io.File.separatorChar, '/')
        .stripSuffix(".expected.json")
      scala.util
        .Try(CertificatesSidecar.parse(s).id)
        .toOption
        .flatten
        .map(id => (s.getPath, expected, id))
    }
    val wrong = ids.filter { case (_, e, a) => e != a }
    assert(
      wrong.isEmpty,
      s"${wrong.size} sidecar(s) have an id that is not their path:" +
        wrong
          .map { case (p, e, a) => s"$p: id=$a expected=$e" }
          .mkString("\n  ", "\n  ", "")
    )
    val dupes = ids.groupBy(_._3).collect { case (id, xs) if xs.size > 1 => id }
    assert(dupes.isEmpty, s"duplicate sidecar ids: ${dupes.mkString(", ")}")
  }

  test("every sidecar parses and declares required fields") {
    val sidecars = CertificatesFixtureInventory.allSidecars
    val errors = sidecars.flatMap { s =>
      Try(CertificatesSidecar.parse(s)).failed.toOption.map { e =>
        s"${s.getPath}: ${e.getMessage}"
      }
    }
    assert(
      errors.isEmpty,
      s"${errors.size} sidecar(s) failed to parse:" +
        errors.mkString("\n  ", "\n  ", "")
    )
  }
}
