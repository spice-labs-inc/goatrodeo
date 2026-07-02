/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

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

import io.spicelabs.coordinates.Purl
import scala.util.Try

/** The result of pURL discovery for a single artifact.
  *
  * A `PurlSet` bundles two pieces of information:
  *
  *  1. '''canonical''' — the primary identity pURL for the artifact (if one
  *     can be determined). For a shaded JAR, this is the JAR's own
  *     groupId/artifactId/version (from
  *     `determinePrimaryGroupIdArtifactIdVersion`). For a simple JAR with one
  *     package, this is that package's pURL. For lockfiles or other
  *     non-package artifacts, this may be `None`.
  *
  *  2. '''purls''' — ALL pURLs for this artifact, including the canonical
  *     one. For a shaded JAR with 300 embedded packages, this vector
  *     contains 300 `Purl` objects.
  *
  * == Design rationale ==
  *
  * This class is a plain data holder — no `require`, no validation logic,
  * no exceptions. The invariant (canonical is a member of purls) is ensured
  * by the factory methods (`single`, `build`) in the companion object. A
  * test ([[PurlSetSuite]]) verifies the factory methods produce consistent
  * data.
  *
  * The project policy is that code must not throw exceptions for expected,
  * recoverable conditions. A `PurlSet` constructed with a canonical pURL not
  * in the purls vector is a data inconsistency, not an unrecoverable system
  * failure. `canonicalStrings` defensively includes the canonical pURL
  * regardless of whether it is in the `purls` vector.
  *
  * `Purl` is a Java `final class` (from `io.spicelabs.coordinates`) that does
  * NOT override `equals` or `hashCode`. This means `Vector[Purl].contains`
  * and `.distinct` use reference identity. The factory methods always
  * include the same `Purl` object reference in both `canonical` and `purls`,
  * so reference equality is sufficient for factory-produced instances.
  * `canonicalStrings` converts to `String` before calling `.distinct`, so
  * string-level deduplication works correctly regardless.
  *
  * @param canonical
  *   The primary identity pURL, or `None` if no canonical identity can be
  *   determined.
  * @param purls
  *   ALL pURLs for this artifact. When constructed via the factory methods,
  *   the canonical pURL (if present) is a member of this vector. Direct
  *   construction does not enforce this — see `canonicalStrings` which
  *   includes the canonical pURL regardless.
  */
case class PurlSet(
    canonical: Option[Purl],
    purls: Vector[Purl]
) {

  /** The canonical pURL as a canonical string, or `None` if canonical is
    * absent or canonicalization fails.
    *
    * `toCanonical()` can throw `Purl.PurlException` for malformed pURLs
    * (e.g., a Maven pURL with a null namespace). The call is wrapped in
    * `Try` so this method never throws. This matches the defensive pattern
    * already applied to all `toCanonical()` call sites in the strategies.
    *
    * @return
    *   `Some(canonicalString)` if the canonical pURL exists and
    *   canonicalizes successfully; `None` otherwise.
    */
  def canonicalString: Option[String] =
    canonical.flatMap(c => Try(c.toCanonical()).toOption)

  /** All pURLs as canonical strings, including the canonical pURL.
    *
    * Each `toCanonical()` call is wrapped in `Try` so one malformed pURL
    * does not prevent the others from being emitted. The canonical pURL is
    * included even if it was not a member of `purls` (which can happen with
    * direct construction). Duplicates are removed at the string level
    * (`String` has proper `equals`/`hashCode`).
    *
    * This is the storage boundary — call this only when writing to the
    * store or comparing with string-based expectations.
    *
    * @return
    *   A vector of canonical pURL strings, with malformed pURLs dropped and
    *   duplicates removed. May be shorter than `purls.size` if some pURLs
    *   fail canonicalization.
    */
  def canonicalStrings: Vector[String] = {
    (canonical.toVector ++ purls)
      .flatMap(p => Try(p.toCanonical()).toOption)
      .distinct
  }
}

object PurlSet {

  /** The empty `PurlSet` — no canonical, no purls. */
  val empty: PurlSet = PurlSet(None, Vector.empty)

  /** Build a `PurlSet` with a single pURL that is also the canonical one.
    *
    * @param p
    *   the pURL to use as both canonical and the sole entry in `purls`.
    */
  def single(p: Purl): PurlSet = PurlSet(Some(p), Vector(p))

  /** Build a `PurlSet` with a canonical pURL and additional secondary pURLs.
    *
    * The canonical pURL is always included in the resulting `purls` vector.
    * Duplicates are removed by reference identity (`Purl` does not override
    * `equals`, so `distinct` uses reference equality).
    *
    * @param canonical
    *   the primary identity pURL, or `None` if no canonical identity can be
    *   determined.
    * @param secondary
    *   additional pURLs (e.g., embedded packages in a shaded JAR).
    */
  def build(
      canonical: Option[Purl],
      secondary: Vector[Purl]
  ): PurlSet = {
    val all = canonical.toVector ++ secondary
    new PurlSet(canonical, all.distinct)
  }
}
