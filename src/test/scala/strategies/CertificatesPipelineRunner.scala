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
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.util.FileWrapper

import java.io.File

/** Runs the full Goat Rodeo pipeline against a single on-disk artifact and
  * returns the resulting Items.
  *
  * ## LLM-friendly summary
  *
  * Takes a `File` on disk, wraps it as a `FileWrapper`, runs it through
  * `ToProcess.buildGraphFromArtifactWrapper` (the same entry point used by
  * `ToProcessTestSuite`), and returns every Item in the resulting in-memory
  * store.
  *
  * Per `certificates-strategy/appendices.md` Appendix B, the Certificates
  * harness asserts against a single Item (`items.head`) when the sidecar's
  * `itemCount == 1`. This runner returns the full vector so cases with
  * multi-Item fixtures (if any ever emerge — the plan says no, since
  * keystores produce one Item) can still be reasoned about.
  *
  * Note: this method is test-only infrastructure. It is not optimized — it
  * spins up a fresh in-memory `MemStorage` per call. Phase 0 prefers clarity
  * over throughput; Phase 3+ fixture runs amount to ~200 invocations of this
  * runner, which is acceptable.
  */
object CertificatesPipelineRunner {

  /** Run the pipeline on `file` and return the **strategy-produced** Items
    * in the resulting store.
    *
    * The pipeline also writes "alias-stub" Items — placeholder Items that
    * exist solely so each alternate-hash form (e.g., `gitoid:blob:sha1`
    * for the same bytes that live under `gitoid:blob:sha256`) can appear
    * as a lookup key. Those stubs have `body = None` and do not represent
    * artifacts a strategy claims. The plan's `itemCount` field counts
    * only the primary Items (those with an `ItemMetaData` body), which is
    * what this method returns.
    *
    * Ordering is stable (sorted by identifier) so tests that depend on
    * `items.head` get a deterministic Item when multiple Items are
    * produced.
    */
  def runGoatRodeoOnSingleFile(file: File): Vector[Item] = {
    val artifact = FileWrapper(file, file.getName.nn, None)
    val store =
      ToProcess.buildGraphFromArtifactWrapper(artifact, args = Config())
    val allItems = store
      .keys()
      .toVector
      .sorted
      .flatMap(key => store.read(key).toVector)
    filterPrimaryItems(allItems)
  }

  /** Return only "primary" Items — those produced by a strategy as first-
    * class artifact representations. Excludes alias-stub Items (which
    * have `body = None` and exist solely as alternate-hash lookup
    * entries) and tag Items (which carry `ItemTagData`, not
    * `ItemMetaData`).
    *
    * Exposed as its own method so it can be unit-tested with synthetic
    * Items without spinning up the full pipeline.
    */
  def filterPrimaryItems(items: Vector[Item]): Vector[Item] =
    items.filter(_.body.exists(_.isInstanceOf[ItemMetaData]))
}
