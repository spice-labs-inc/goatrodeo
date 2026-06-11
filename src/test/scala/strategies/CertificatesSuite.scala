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

import munit.FunSuite

/** Per-fixture parameterized test suite for the Certificates strategy.
  *
  * ## LLM-friendly summary
  *
  *   - Discovers every `(fixture, sidecar)` pair under
  *     `test_data/certificates/` via [[CertificatesFixtureInventory.pairs]].
  *   - For each pair, emits one test that:
  *     1. Parses the sidecar via [[CertificatesSidecar.parse]]. 2. Runs the
  *        fixture through the full Goat Rodeo pipeline via
  *        [[CertificatesPipelineRunner.runGoatRodeoOnSingleFile]]. 3. Asserts
  *        `itemCount` exactly, then assertions from [[CertificatesAssertions]]
  *        on the first Item.
  *
  * ## Phase-0 state
  *
  * Phase 0 of the Certificates strategy specifies the harness-is-present state.
  * Phase 0 has split into (a) infrastructure — this file and its siblings — and
  * (b) corpus population (the 200+ fixture download). Until (b) lands,
  * [[CertificatesFixtureInventory.pairs]] is empty and this suite emits zero
  * per-fixture tests.
  *
  * Corpus-integrity assertions (the 200-floor, orphan detection, sidecar schema
  * validation) live in [[CertificatesCorpusIntegritySuite]] — that suite runs
  * every time even when no fixtures exist, so the build fails until the corpus
  * is populated. Per the acceptance criteria ("mark all pending OR fail loudly
  * — pick one"), we fail loudly via the integrity suite and emit passing
  * pending placeholders here when fixtures exist but the strategy is not yet
  * producing the expected Items.
  */
class CertificatesSuite extends FunSuite {

  /** Is the Certificates strategy class registered on the classpath? Phase 1's
    * first commit creates
    * `io.spicelabs.goatrodeo.omnibor.strategies.Certificates`; until then this
    * suite's per-fixture assertions cannot be meaningfully evaluated (the
    * pipeline uses GenericFile as the fallback and emits no crypto pURLs /
    * metadata). To keep the state uniform per Phase 0's "all pending OR all
    * fail loudly — no partial mix" rule, every per-fixture test is marked
    * `.ignore` until the class exists.
    */
  private val strategyPresent: Boolean = {
    try {
      Class.forName(
        "io.spicelabs.goatrodeo.omnibor.strategies.Certificates"
      )
      true
    } catch {
      case _: ClassNotFoundException => false
    }
  }

  private val pairs = CertificatesFixtureInventory.pairs

  pairs.foreach { pair =>
    val name = s"Certificates: ${pair.category}/${pair.fixture.getName}"
    val opts: munit.TestOptions =
      if (strategyPresent) new munit.TestOptions(name)
      else new munit.TestOptions(name).tag(munit.Ignore)
    test(opts) {
      val sidecar = CertificatesSidecar.parse(pair.sidecar)
      val items =
        CertificatesPipelineRunner.runGoatRodeoOnSingleFile(pair.fixture)

      assertEquals(
        items.size,
        sidecar.itemCount,
        s"$name: expected itemCount=${sidecar.itemCount} but pipeline " +
          s"emitted ${items.size} Items. If the Certificates strategy is " +
          s"not yet implemented, this red failure is expected per the " +
          s"red-to-green discipline (see certificates-strategy/" +
          s"phases-1-2-foundation-detector.md)."
      )

      val item = items.head
      CertificatesAssertions.assertMimeTypesContain(
        item,
        sidecar.mimeTypes.mustContain,
        name
      )
      CertificatesAssertions.assertMimeTypesAbsent(
        item,
        sidecar.mimeTypes.mustNotContain,
        name
      )
      CertificatesAssertions.assertPurlsContain(
        item,
        sidecar.purls.mustContain,
        name
      )
      CertificatesAssertions.assertPurlsAbsent(
        item,
        sidecar.purls.mustNotContain,
        name
      )
      CertificatesAssertions.assertMetadataContains(
        item,
        sidecar.metadata.mustContain,
        name
      )
      CertificatesAssertions.assertMetadataRanges(
        item,
        sidecar.metadata.mustContainRanges,
        name
      )
      CertificatesAssertions.assertMetadataKeysAbsent(
        item,
        sidecar.forbiddenMetadataKeys,
        name
      )
      CertificatesAssertions.assertNoForbiddenPatterns(
        item,
        sidecar.forbiddenMetadataPatterns,
        name
      )
    }
  }
}
