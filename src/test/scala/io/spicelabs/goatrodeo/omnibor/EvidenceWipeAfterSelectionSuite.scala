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

package io.spicelabs.goatrodeo.omnibor

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.CarvedCertAugmenter
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File

/** Tests that evidence does not outlive the strategy selection that consumes
  * it.
  *
  * WHAT: once `ToProcess.strategiesForArtifacts` has offered an artifact to
  * every strategy and returned, the artifact's MIME evidence map is empty,
  * whether the artifact was claimed or not.
  *
  * WHY: evidence exists for exactly one purpose — when an augmenter cannot
  * assert a MIME within its budget, it hands the information it did collect to
  * the downstream evaluator (strategy selection or the file walker) as a
  * starting point. That evaluator runs during selection. Afterwards the record
  * is dead weight, and a wrapper lives until the run ends, so keeping it means
  * one record per artifact per augmenter for the whole run.
  *
  * THEORY: the wipe belongs at the end of the selection pass, not on a timer
  * and not per strategy, because only there is it true that nobody will ask
  * again. The test pins both halves: the evidence is present *before* selection
  * (otherwise the wipe would be vacuous) and gone after.
  *
  * LLM note: test ids are EW-n. `EW-1` covers a carrier the carve strategy
  * claims; `EW-2` covers a carrier it does not claim, since an unclaimed
  * artifact keeps its evidence under a per-strategy wipe and must not here.
  */
class EvidenceWipeAfterSelectionSuite extends GoatRodeoFunSuite {

  private def fixture(name: String): File =
    new File(s"test_data/carved-certs/$name")

  private def wrapper(name: String): ArtifactWrapper =
    FileWrapper(fixture(name), name, None)

  private def evidenceCount(w: ArtifactWrapper): Int =
    w.mimeTypeEvidence.values.map(_.length).sum

  // EW-1 — a claimed carrier's evidence is released after selection.
  test("EW-1 evidence is wiped after selection for a claimed carrier") {
    val w = wrapper("elf-rsa1024-cert")
    assert(
      w.mimeType.contains(CarvedCertAugmenter.CarvedMime) ||
        w.mimeTypeEvidence.contains(CarvedCertAugmenter.CarvedMime),
      "the carrier must be probed by the augmenter in the first place"
    )
    assert(evidenceCount(w) > 0, "evidence exists before selection")
    val claimed = ToProcess.strategiesForArtifacts(Vector(w), _ => (), false)
    assert(claimed.nonEmpty, "the artifact must be claimed by some strategy")
    assertEquals(
      w.mimeTypeEvidence,
      Map
        .empty[String, Vector[io.spicelabs.goatrodeo.util.AugmentationEvidence]]
    )
  }

  // EW-2 — an unclaimed carrier is wiped too: the wipe is about the selection
  // pass ending, not about which strategy won.
  test("EW-2 evidence is wiped after selection for an unclaimed carrier") {
    val w = wrapper("elf-no-certs")
    assert(evidenceCount(w) > 0, "evidence exists before selection")
    ToProcess.strategiesForArtifacts(Vector(w), _ => (), false)
    assertEquals(
      w.mimeTypeEvidence,
      Map
        .empty[String, Vector[io.spicelabs.goatrodeo.util.AugmentationEvidence]]
    )
  }
}
