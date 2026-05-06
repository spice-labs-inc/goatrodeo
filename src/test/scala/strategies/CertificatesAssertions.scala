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

import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.PairOf
import io.spicelabs.goatrodeo.omnibor.StringOf

import scala.collection.immutable.TreeSet

/** Assertion helpers shared by all Certificates-strategy test suites.
  *
  * ## LLM-friendly summary
  *
  * These helpers translate a sidecar's declared expectations into `assert`
  * calls against an emitted [[Item]]. They are intentionally small and
  * self-contained — the harness orchestrates, these helpers adjudicate.
  *
  * Every helper that fails throws [[AssertionError]] with a message naming:
  *   - the fixture (via the caller's context — callers are expected to prepend
  *     or include fixture identification in the failure message they propagate)
  *   - what was expected
  *   - what was actually observed
  *
  * ## Data-model notes
  *
  *   - MIME types for the emitted Item live in `item.bodyAsItemMetaData.map
  *     (_.mimeType)` (a `TreeSet[String]`).
  *   - pURLs are attached as `EdgeType.aliasFrom` connections on the item (the
  *     canonical string form of each pURL appears there) and also as entries in
  *     `ItemMetaData.fileNames`; we read them off the connection set which is
  *     the authoritative location.
  *   - Ad-hoc metadata lives in `ItemMetaData.extra` — keyed by strings like
  *     `Certificates:SubjectDN` (colon-separator convention; see parent plan's
  *     Hard rule #6).
  */
object CertificatesAssertions {

  /** Extract all pURL strings attached to the Item via `alias:from` edges.
    * These are the canonicalized pURL strings produced by
    * `PackageURL.canonicalize()`.
    */
  def purlsOf(item: Item): Set[String] = {
    item.connections
      .collect {
        case (edgeType, target) if edgeType == EdgeType.aliasFrom =>
          target
      }
      .toSet
      .filter(_.startsWith("pkg:"))
  }

  /** Extract the MIME type set attached to the Item. Returns empty set if the
    * Item has no metadata body.
    */
  def mimeTypesOf(item: Item): TreeSet[String] =
    item.bodyAsItemMetaData.map(_.mimeType).getOrElse(TreeSet.empty)

  /** Flatten the `extra` metadata to a list of `(key, value)` pairs where
    * `value` is the plain string form of each [[StringOrPair]]. For `PairOf`,
    * only the value (not the MIME-type half) is returned — the leak sweep and
    * most assertions care about the value itself.
    */
  def metadataEntries(item: Item): Vector[(String, String)] = {
    val extra = item.bodyAsItemMetaData.map(_.extra).getOrElse(Map.empty)
    extra.toVector.flatMap { case (k, vs) =>
      vs.toVector.map {
        case StringOf(s)  => k -> s
        case PairOf(_, s) => k -> s
      }
    }
  }

  /** All metadata values (any key) — used by leak sweeps. */
  def allMetadataValues(item: Item): Vector[String] =
    metadataEntries(item).map(_._2)

  /** Assert that every MIME type in `required` is present on `item`. */
  def assertMimeTypesContain(
      item: Item,
      required: List[String],
      label: String
  ): Unit = {
    val actual = mimeTypesOf(item)
    val missing = required.filterNot(actual.contains)
    if (missing.nonEmpty) {
      throw new AssertionError(
        s"$label: MIME types missing ${missing.mkString("[", ", ", "]")}; " +
          s"actual=${actual.mkString("[", ", ", "]")}"
      )
    }
  }

  /** Assert that no MIME type in `forbidden` appears on `item`. */
  def assertMimeTypesAbsent(
      item: Item,
      forbidden: List[String],
      label: String
  ): Unit = {
    val actual = mimeTypesOf(item)
    val present = forbidden.filter(actual.contains)
    if (present.nonEmpty) {
      throw new AssertionError(
        s"$label: forbidden MIME types present " +
          s"${present.mkString("[", ", ", "]")}"
      )
    }
  }

  /** Assert that every pURL string in `required` is emitted. `required` strings
    * may contain `<computed>` tokens — those skip the exact check for that pURL
    * and only verify that *some* pURL matches the prefix before the first
    * `<computed>` and the suffix after it.
    */
  def assertPurlsContain(
      item: Item,
      required: List[String],
      label: String
  ): Unit = {
    val actual = purlsOf(item)
    val missing = required.filterNot { req =>
      if (req.contains("<computed>")) {
        val parts = req.split("<computed>", -1).toVector
        actual.exists { a =>
          parts
            .foldLeft(Option(0)) {
              case (None, _) => None
              case (Some(idx), seg) =>
                val nextIdx = a.indexOf(seg, idx)
                if (nextIdx < 0) None else Some(nextIdx + seg.length)
            }
            .isDefined
        }
      } else actual.contains(req)
    }
    if (missing.nonEmpty) {
      throw new AssertionError(
        s"$label: pURLs missing ${missing.mkString("[", ", ", "]")}; " +
          s"actual=${actual.toVector.sorted.mkString("[", ", ", "]")}"
      )
    }
  }

  /** Assert that no pURL string in `forbidden` is emitted. */
  def assertPurlsAbsent(
      item: Item,
      forbidden: List[String],
      label: String
  ): Unit = {
    val actual = purlsOf(item)
    val present = forbidden.filter(actual.contains)
    if (present.nonEmpty) {
      throw new AssertionError(
        s"$label: forbidden pURLs present ${present.mkString("[", ", ", "]")}"
      )
    }
  }

  /** Assert that every `key -> expected` pair in `required` is present in the
    * Item's extra metadata. If `expected == "<computed>"`, only key presence
    * (and non-empty value) is checked.
    */
  def assertMetadataContains(
      item: Item,
      required: Map[String, String],
      label: String
  ): Unit = {
    val entries = metadataEntries(item)
    val byKey = entries.groupMap(_._1)(_._2)
    val failures = required.toVector.flatMap { case (k, expected) =>
      byKey.get(k) match {
        case None => Some(s"key '$k' missing")
        case Some(values) =>
          if (expected == "<computed>") {
            if (values.exists(_.nonEmpty)) None
            else Some(s"key '$k' present but empty (sidecar said <computed>)")
          } else if (values.contains(expected)) None
          else
            Some(
              s"key '$k' expected '$expected', got ${values.mkString("[", ", ", "]")}"
            )
      }
    }
    if (failures.nonEmpty) {
      throw new AssertionError(s"$label: ${failures.mkString("; ")}")
    }
  }

  /** Assert integer-range assertions. Value strings are parsed to `BigInt`.
    */
  def assertMetadataRanges(
      item: Item,
      ranges: Map[String, NumericRange],
      label: String
  ): Unit = {
    val entries = metadataEntries(item)
    val byKey = entries.groupMap(_._1)(_._2)
    val failures = ranges.toVector.flatMap { case (k, range) =>
      byKey.get(k) match {
        case None => Some(s"range key '$k' missing")
        case Some(values) =>
          val parsed = values.flatMap(v => scala.util.Try(BigInt(v)).toOption)
          val min = scala.util.Try(BigInt(range.min)).toOption
          val max = scala.util.Try(BigInt(range.max)).toOption
          (min, max) match {
            case (Some(mn), Some(mx)) =>
              if (parsed.exists(n => n >= mn && n <= mx)) None
              else
                Some(
                  s"range key '$k' no value in [$mn, $mx]; got " +
                    values.mkString("[", ", ", "]")
                )
            case _ =>
              Some(s"range key '$k' has unparseable bounds ${range}")
          }
      }
    }
    if (failures.nonEmpty) {
      throw new AssertionError(s"$label: ${failures.mkString("; ")}")
    }
  }

  /** Assert that none of the listed metadata keys are present on the Item. */
  def assertMetadataKeysAbsent(
      item: Item,
      forbidden: List[String],
      label: String
  ): Unit = {
    val present = item.bodyAsItemMetaData
      .map(_.extra.keySet)
      .getOrElse(Set.empty)
    val violations = forbidden.filter(present.contains)
    if (violations.nonEmpty) {
      throw new AssertionError(
        s"$label: forbidden metadata keys present " +
          s"${violations.mkString("[", ", ", "]")}"
      )
    }
  }

  /** Assert that no metadata value on the Item matches any of the supplied
    * forbidden regex patterns. This is the private-key leak guard (see Appendix
    * C in `certificates-strategy/appendices.md`).
    */
  def assertNoForbiddenPatterns(
      item: Item,
      patterns: List[String],
      label: String
  ): Unit = {
    val compiled = patterns.map(p => p -> java.util.regex.Pattern.compile(p))
    val violations = for {
      (key, value) <- metadataEntries(item)
      (raw, pat) <- compiled
      if pat.matcher(value).find()
    } yield s"key '$key' value matched forbidden pattern /$raw/"
    if (violations.nonEmpty) {
      throw new AssertionError(s"$label: ${violations.mkString("; ")}")
    }
  }
}
