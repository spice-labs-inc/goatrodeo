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

import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.File
import java.nio.file.Files
import scala.util.Try

/** Sidecar — the ground-truth JSON file paired with every fixture in
  * `test_data/certificates/`.
  *
  * Phase 0 of the Certificates strategy plan (see
  * `certificates-strategy/phase-0-corpus.md` and
  * `certificates-strategy/appendices.md` Appendix B) requires every fixture to
  * be paired with a `{artifact-filename}.expected.json` sidecar that declares
  * what the strategy must emit for that fixture.
  *
  * ## LLM-friendly summary
  *
  * A sidecar is a JSON object with a fixed schema. The schema defines:
  *   - `description`, `source`, `retrievedAt` — provenance
  *   - `itemCount` — exact number of Items the pipeline must emit for this
  *     fixture
  *   - `mimeTypes.mustContain` / `mustNotContain` — subset / absence
  *     assertions on the MIME types attached to the emitted Item
  *   - `purls.mustContain` / `mustNotContain` — subset / absence assertions on
  *     emitted pURLs (canonicalized strings)
  *   - `metadata.mustContain` — subset assertion on the Item's extra metadata
  *     (keys use `:` separators per project-wide convention — see the parent
  *     plan's "Hard rules" and `MKC.adHoc`'s `prefix:key` output)
  *   - `metadata.mustContainRanges` — integer-range assertions for metadata
  *     values whose exact content is too noisy to pin (e.g., entry counts in
  *     large keystores)
  *   - `forbiddenMetadataKeys` / `forbiddenMetadataPatterns` — the private-key
  *     leak guards from Appendix C
  *
  * All `must*` assertions are subset checks; `forbidden*` assertions are
  * absence checks; `itemCount` is an exact match.
  */
final case class CertificatesSidecar(
    description: String,
    source: String,
    retrievedAt: String,
    itemCount: Int,
    mimeTypes: MimeTypeAssertions,
    purls: PurlAssertions,
    metadata: MetadataAssertions,
    forbiddenMetadataKeys: List[String],
    forbiddenMetadataPatterns: List[String]
)

/** MIME-type subset/absence assertions. */
final case class MimeTypeAssertions(
    mustContain: List[String],
    mustNotContain: List[String]
)

/** pURL subset/absence assertions. Values are canonicalized pURL strings. */
final case class PurlAssertions(
    mustContain: List[String],
    mustNotContain: List[String]
)

/** Metadata subset and range assertions.
  *
  * `mustContain` values may be either a bare string (exact match against one
  * of the `StringOrPair` values at that key) or the literal token
  * `"<computed>"` which means "key must be present and non-empty, exact value
  * not yet locked in". Use `"<computed>"` as a bootstrap placeholder when
  * authoring sidecars; replace with the locked-in value once the fixture's
  * ground truth is computed via the `compute-expected.sh` tool.
  */
final case class MetadataAssertions(
    mustContain: Map[String, String],
    mustContainRanges: Map[String, NumericRange]
)

/** Inclusive integer range for `mustContainRanges`. Bounds are strings in the
  * JSON (e.g., `{"min": "0", "max": "10000"}`) so that arbitrary-precision
  * counts do not overflow. The harness parses them to `BigInt` at assertion
  * time.
  */
final case class NumericRange(min: String, max: String)

/** Parse sidecar JSON files on disk into `CertificatesSidecar` instances.
  *
  * ## Required fields
  *
  * The parser enforces that every sidecar declares:
  *   - `description` (String)
  *   - `source` (String)
  *   - `retrievedAt` (String, ISO-8601 date)
  *   - `itemCount` (Int)
  *   - `mimeTypes.mustContain` (array of String; may be empty but must exist)
  *   - `purls.mustContain` (array of String; may be empty but must exist)
  *   - `metadata.mustContain` (object; may be empty but must exist)
  *   - `forbiddenMetadataPatterns` (array of String; may be empty but must
  *     exist — this is the private-key leak guard and its absence is never
  *     benign)
  *
  * Optional fields default to empty collections: `mimeTypes.mustNotContain`,
  * `purls.mustNotContain`, `metadata.mustContainRanges`,
  * `forbiddenMetadataKeys`.
  *
  * A sidecar missing any required field produces a `SidecarParseError` with a
  * message identifying which field was missing. This surfaces authoring
  * mistakes at fixture commit time rather than as confusing downstream
  * assertion failures.
  */
object CertificatesSidecar {

  final case class SidecarParseError(file: File, message: String)
      extends RuntimeException(s"${file.getPath}: $message")

  /** Parse a sidecar file. Throws [[SidecarParseError]] on any required-field
    * violation or malformed JSON.
    */
  def parse(file: File): CertificatesSidecar = {
    val bytes = Files.readAllBytes(file.toPath)
    val text = new String(bytes, "UTF-8")
    val json =
      Try(parseOpt(text)).toOption.flatten.getOrElse(
        throw SidecarParseError(file, "not valid JSON")
      )

    def req[T](path: String)(extract: JValue => Option[T]): T = {
      val v = pick(json, path)
      extract(v).getOrElse(
        throw SidecarParseError(
          file,
          s"required field '$path' missing or wrong type"
        )
      )
    }

    def opt[T](path: String)(extract: JValue => Option[T]): Option[T] = {
      val v = pick(json, path)
      if (v == JNothing || v == JNull) None else extract(v)
    }

    CertificatesSidecar(
      description = req("description")(asString),
      source = req("source")(asString),
      retrievedAt = req("retrievedAt")(asString),
      itemCount = req("itemCount")(asInt),
      mimeTypes = MimeTypeAssertions(
        mustContain = req("mimeTypes.mustContain")(asStringList),
        mustNotContain =
          opt("mimeTypes.mustNotContain")(asStringList).getOrElse(Nil)
      ),
      purls = PurlAssertions(
        mustContain = req("purls.mustContain")(asStringList),
        mustNotContain =
          opt("purls.mustNotContain")(asStringList).getOrElse(Nil)
      ),
      metadata = MetadataAssertions(
        mustContain = req("metadata.mustContain")(asStringMap),
        mustContainRanges =
          opt("metadata.mustContainRanges")(asRangeMap).getOrElse(Map.empty)
      ),
      forbiddenMetadataKeys =
        opt("forbiddenMetadataKeys")(asStringList).getOrElse(Nil),
      forbiddenMetadataPatterns =
        req("forbiddenMetadataPatterns")(asStringList)
    )
  }

  private def pick(json: JValue, path: String): JValue = {
    path.split('.').foldLeft(json) { (cur, seg) => cur \ seg }
  }

  private def asString(v: JValue): Option[String] = v match {
    case JString(s) => Some(s)
    case _          => None
  }

  private def asInt(v: JValue): Option[Int] = v match {
    case JInt(n)    => Try(n.toInt).toOption
    case JLong(n)   => Try(n.toInt).toOption
    case JDouble(n) => Try(n.toInt).toOption
    case _          => None
  }

  private def asStringList(v: JValue): Option[List[String]] = v match {
    case JArray(items) =>
      val strings = items.collect { case JString(s) => s }
      if (strings.length == items.length) Some(strings) else None
    case _ => None
  }

  private def asStringMap(v: JValue): Option[Map[String, String]] = v match {
    case JObject(fields) =>
      val entries = fields.collect { case (k, JString(s)) => k -> s }
      if (entries.length == fields.length) Some(entries.toMap) else None
    case _ => None
  }

  private def asRangeMap(v: JValue): Option[Map[String, NumericRange]] =
    v match {
      case JObject(fields) =>
        val entries = fields.flatMap {
          case (k, JObject(inner)) =>
            val m = inner.toMap
            for {
              minV <- m.get("min").flatMap(asString)
              maxV <- m.get("max").flatMap(asString)
            } yield k -> NumericRange(minV, maxV)
          case _ => None
        }
        if (entries.length == fields.length) Some(entries.toMap) else None
      case _ => None
    }
}
