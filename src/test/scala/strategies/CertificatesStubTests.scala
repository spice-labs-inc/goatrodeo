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

import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
import io.spicelabs.goatrodeo.omnibor.MemStorage
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.strategies.Certificates
import io.spicelabs.goatrodeo.omnibor.strategies.CertificatesState
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.ByteWrapper
import io.spicelabs.goatrodeo.util.CryptoDetector
import io.spicelabs.goatrodeo.util.Gitoid
import munit.FunSuite

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Direct unit tests for Phase 1 stubs.
  *
  * Phase 1 of the Certificates strategy ships:
  *   - `CryptoDetector.mimeTypeAugmenter` — pass-through stub
  *   - `Certificates.computeCertificateFiles` — claims nothing
  *   - `CertificatesState`'s 5 `ProcessingState` methods — all pass-through
  *
  * These tests assert the stub contracts directly. They guard the Phase-1
  * invariant "no behavior change" against accidental regressions during Phase
  * 2/3+ rewrites. They are a regression net, not an acceptance gate — Phase 2
  * will replace `CryptoDetector.mimeTypeAugmenter` with content sniffing, so
  * that test will need to update (per invariant #4 — discuss before changing).
  *
  * ## Trace to plan
  *
  * Plan: `certificates-strategy/phases-1-2-foundation-detector.md` Phase 1 task
  * #3 (CryptoDetector stub) and task #5 (Certificates skeleton with five
  * ProcessingState methods).
  *
  * ## LLM-friendly summary
  *
  * | Test                                                                     | Phase 1 contract verified                                             |
  * |:-------------------------------------------------------------------------|:----------------------------------------------------------------------|
  * | `CryptoDetector returns currentMimes unchanged for empty set`            | augmenter passes through ∅                                            |
  * | `... for a small text-MIME set`                                          | augmenter doesn't strip text-prefixed MIMEs like SaffronDetector does |
  * | `... for a typical Tika-ish set`                                         | no MIME types added or removed                                        |
  * | `... is purely additive in form`                                         | output ⊇ input (subset relation)                                      |
  * | `Certificates.computeCertificateFiles returns empty + unchanged + label` | claim-nothing dispatcher                                              |
  * | `... preserves a single-entry byUUID map identity`                       | no map mutation                                                       |
  * | `... preserves a single-entry byName map identity`                       | no map mutation                                                       |
  * | `CertificatesState.beginProcessing returns this`                         | identity                                                              |
  * | `... getPurls returns empty Vector + this`                               | empty pURL contract                                                   |
  * | `... getMetadata returns empty TreeMap + this`                           | empty metadata contract                                               |
  * | `... finalAugmentation returns input Item + this`                        | no Item mutation                                                      |
  * | `... postChildProcessing returns this`                                   | identity                                                              |
  */
class CertificatesStubTests extends FunSuite {

  // --- helpers ------------------------------------------------------------

  private def syntheticArtifact(name: String = "stub.bin"): ArtifactWrapper =
    ByteWrapper("hello goat rodeo".getBytes("UTF-8"), name, None)

  private def syntheticItem(): Item = Item(
    identifier = Gitoid("gitoid:blob:sha256:phase1-stub"),
    connections = TreeSet.empty,
    bodyMimeType = Some(ItemMetaData.mimeType),
    body = Some(
      ItemMetaData(
        fileNames = TreeSet.empty,
        mimeType = TreeSet.empty,
        fileSize = 0L,
        extra = TreeMap.empty
      )
    )
  )

  // === SECTION A — Phase-INVARIANT contracts ==============================
  //
  // These tests encode contracts that hold for EVERY phase from Phase 1
  // through the strategy's final form. Phase 2/3+ rewrites must keep them
  // green. If a future phase needs to weaken any of them, that requires
  // invariant-#4 discussion BEFORE the test is changed.

  test(
    "[INVARIANT] CryptoDetector.mimeTypeAugmenter never strips MIME types beginning with `text/`"
  ) {
    // Plan task #2 detection-signature table footnote: contrasting
    // SaffronDetector, the Crypto augmenter is purely additive.
    val input = Set("text/plain", "text/html")
    val out = CryptoDetector.mimeTypeAugmenter(syntheticArtifact(), input)
    val stripped = input.filterNot(out.contains)
    assert(
      stripped.isEmpty,
      s"text-prefixed MIMEs were stripped: $stripped (output=$out)"
    )
  }

  test(
    "[INVARIANT] CryptoDetector.mimeTypeAugmenter output is always a superset of input (additive)"
  ) {
    // Phase 2 will replace the augmenter body with content sniffing.
    // The body changes; the additive-contract invariant doesn't.
    val cases = Seq(
      Set.empty[String],
      Set("text/plain"),
      Set("application/octet-stream", "application/json"),
      Set("application/x-pem-file"),
      Set("text/plain", "application/x-x509-ca-cert")
    )
    for (input <- cases) {
      val out = CryptoDetector.mimeTypeAugmenter(syntheticArtifact(), input)
      assert(
        input.subsetOf(out),
        s"output $out must be a superset of input $input " +
          "(augmenter is purely additive)"
      )
    }
  }

  // === SECTION B — Phase-1-STUB-specific contracts =======================
  //
  // These tests encode the SPECIFIC claim-nothing pass-through behavior of
  // Phase 1. They WILL fail by design when their corresponding Phase 2/3+
  // behavior lands:
  //   - Phase 2 makes `CryptoDetector` content-sniff → the
  //     "returns currentMimes unchanged" tests change to assert the
  //     specific MIMEs the augmenter adds for each signature.
  //   - Phase 3 makes `Certificates.computeCertificateFiles` claim X.509
  //     → "claim-nothing" tests change to assert which artifacts get
  //     claimed.
  //   - Phase 3-7 fill the `CertificatesState` methods → identity
  //     pass-through tests change to assert per-phase behavior.
  //
  // Per CLAUDE.md invariant #4, every change to these tests in subsequent
  // phases requires explicit user approval before it lands.

  test(
    "CryptoDetector.mimeTypeAugmenter returns currentMimes unchanged for empty set"
  ) {
    val out = CryptoDetector.mimeTypeAugmenter(syntheticArtifact(), Set.empty)
    assertEquals(out, Set.empty[String])
  }

  test(
    "CryptoDetector.mimeTypeAugmenter on a typical Tika-ish set returns it identically (Phase 1 only — Phase 2 will add MIMEs for cert-shaped fixtures)"
  ) {
    val input = Set(
      "application/octet-stream",
      "application/x-pem-file",
      "application/x-x509-ca-cert",
      "text/plain"
    )
    assertEquals(
      CryptoDetector.mimeTypeAugmenter(syntheticArtifact(), input),
      input
    )
  }

  // === SECTION B continued — Phase-1-STUB-specific (Certificates) ========

  test(
    "Certificates.computeCertificateFiles returns (empty Vector, byUUID, byName, \"Certificates\") at Phase 1 (claim-nothing dispatcher)"
  ) {
    val byUUID: io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID = Map.empty
    val byName: io.spicelabs.goatrodeo.omnibor.ToProcess.ByName = Map.empty
    val (claimed, returnedByUUID, returnedByName, label) =
      Certificates.computeCertificateFiles(byUUID, byName)
    assertEquals(claimed, Vector.empty, "Phase-1 stub must claim nothing.")
    assertEquals(
      returnedByUUID,
      byUUID,
      "Phase-1 stub must return byUUID unchanged."
    )
    assertEquals(
      returnedByName,
      byName,
      "Phase-1 stub must return byName unchanged."
    )
    assertEquals(
      label,
      "Certificates",
      "Dispatch label must be 'Certificates'."
    )
  }

  test(
    "Certificates.computeCertificateFiles preserves a single-entry byUUID map identity (claim-nothing → no map mutation)"
  ) {
    val art = syntheticArtifact()
    val byUUID: io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID =
      Map(art.uuid -> art)
    val byName: io.spicelabs.goatrodeo.omnibor.ToProcess.ByName = Map.empty
    val (_, returnedByUUID, _, _) =
      Certificates.computeCertificateFiles(byUUID, byName)
    assertEquals(returnedByUUID, byUUID)
    assert(
      returnedByUUID.contains(art.uuid),
      "byUUID must still contain the artifact unchanged."
    )
  }

  test(
    "Certificates.computeCertificateFiles preserves a single-entry byName map identity (claim-nothing → no map mutation)"
  ) {
    val art = syntheticArtifact("foo.pem")
    val byUUID: io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID = Map.empty
    val byName: io.spicelabs.goatrodeo.omnibor.ToProcess.ByName =
      Map("foo.pem" -> Vector(art))
    val (_, _, returnedByName, _) =
      Certificates.computeCertificateFiles(byUUID, byName)
    assertEquals(returnedByName, byName)
  }

  // === SECTION B continued — Phase-1-STUB-specific (CertificatesState) ===

  test(
    "CertificatesState.beginProcessing returns this (identity pass-through; Phase 3+ will use this stage to cache parsed cert)"
  ) {
    val art = syntheticArtifact()
    val state = new CertificatesState(art)
    val out = state.beginProcessing(art, syntheticItem(), SingleMarker())
    assert(out eq state, "beginProcessing must return the same state instance.")
  }

  test(
    "CertificatesState.getPurls returns (empty Vector, this) at Phase 1 (Phase 3+ emits per-cert pURLs)"
  ) {
    val art = syntheticArtifact()
    val state = new CertificatesState(art)
    val (purls, returned) =
      state.getPurls(art, syntheticItem(), SingleMarker())
    assertEquals(purls, Vector.empty)
    assert(returned eq state)
  }

  test(
    "CertificatesState.getMetadata returns (empty TreeMap, this) at Phase 1 (Phase 3+ emits per-cert metadata)"
  ) {
    val art = syntheticArtifact()
    val state = new CertificatesState(art)
    val (md, returned) =
      state.getMetadata(art, syntheticItem(), SingleMarker())
    assert(md.isEmpty, "Phase-1 metadata contract is empty TreeMap.")
    assert(returned eq state)
  }

  test(
    "CertificatesState.finalAugmentation returns the input Item unchanged at Phase 1 (Phase 3+ runs the leak sweep here)"
  ) {
    val art = syntheticArtifact()
    val state = new CertificatesState(art)
    val item = syntheticItem()
    val (returnedItem, returnedState) =
      state.finalAugmentation(
        art,
        item,
        SingleMarker(),
        ParentScope.forAndWith("test-scope", None, Map.empty),
        MemStorage(None)
      )
    assert(returnedItem eq item, "finalAugmentation must not mutate the Item.")
    assert(returnedState eq state)
  }

  test(
    "[INVARIANT] CertificatesState.postChildProcessing returns this — the Certificates strategy never recurses into child Items (Hard rule #2)"
  ) {
    val art = syntheticArtifact()
    val state = new CertificatesState(art)
    val out = state.postChildProcessing(
      None,
      MemStorage(None),
      SingleMarker()
    )
    assert(out eq state)
  }

  // === SECTION A continued — Phase-INVARIANT (registration) =============

  /** Phase-1 plan task #6 + claim #8 in `docs/certificates/phase-1-claims.md`
    * say
    * `Class.forName("io.spicelabs.goatrodeo.omnibor.strategies.Certificates")`
    * must resolve at runtime — that's the gate `CertificatesSuite` reads to
    * decide whether to skip per-fixture tests. Prior to this test, claim #8 was
    * only verified indirectly via observing CertificatesSuite's gate flip. This
    * locks the contract directly with no observation chain.
    */
  test("[INVARIANT] Certificates strategy class is reflectively loadable") {
    val cls = Class.forName(
      "io.spicelabs.goatrodeo.omnibor.strategies.Certificates"
    )
    assert(
      cls != null,
      "io.spicelabs.goatrodeo.omnibor.strategies.Certificates must " +
        "resolve via Class.forName so CertificatesSuite's strategyPresent " +
        "gate flips to active."
    )
  }
}
