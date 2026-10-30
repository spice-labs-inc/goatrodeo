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

package io.spicelabs.goatrodeo.util

import java.nio.charset.StandardCharsets

/** Claims `application/json` for JSON documents Tika cannot name, and
  * `text/x-lua` for LuaRocks rockspecs.
  *
  * Tika has no JSON type: it answers `text/plain` for a JSON file whose name
  * does not end in `.json`. That matters in practice — an OCI image config blob
  * is `blobs/sha256/<hex>`, is JSON, and is what the Docker strategy needs to
  * claim an image, so a name-only rule silently loses every image whose config
  * is not named `.json`.
  *
  * The decision is made on one block (4 KiB) of the artifact and never on the
  * whole file: the MIME pass is the expensive part of a run and must not read
  * more than a block. Because a document can be larger than the block, the
  * probe asks a bounded question -- "is what I can see a valid JSON prefix?" --
  * rather than "is this a complete JSON document?".
  *
  * Nothing is recorded as evidence: this is a local decision about a single
  * artifact's bytes, not metadata that instructs a later stage about what needs
  * more probing.
  */
object JsonLikeDetector {

  /** The type claimed for JSON documents. */
  val JsonMime: String = "application/json"

  /** The type claimed for LuaRocks rockspecs, which Tika calls `text/plain`. */
  val LuaMime: String = "text/x-lua"

  /** Both sides come from [[MimeFamilies]], the same pair the consumer of this
    * augmenter's MIME type uses, so a change to what this augmenter can handle
    * changes its consumer at the same moment.
    */
  private[goatrodeo] def mimeRule(
      mimes: Set[String],
      name: String = ""
  ): Boolean =
    !MimeFamilies.contradicts(mimes, MimeFamilies.cannotBeJsonLike) &&
      MimeFamilies.couldBe(mimes, MimeFamilies.couldBeJsonLike)

  private def isJsonName(name: String): Boolean =
    name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")

  private def isRockspecName(name: String): Boolean =
    name.toLowerCase(java.util.Locale.ROOT).endsWith(".rockspec")

  private def baseName(path: String): String = {
    val slash = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    if (slash >= 0) path.substring(slash + 1) else path
  }

  /** The augmenter: parameterised on a probe so a test can pin it directly. */
  val augmenter: MimeAugmenter = MimeAugmenter(
    id = "json-like",
    rule = mimeRule(_, _),
    budget = ProbeBudget(),
    fn = (artifact, currentMimes, evidence, probe) => {
      val name = baseName(artifact.path())

      val byName =
        (isJsonName(name) && !currentMimes.contains(JsonMime)) ||
          (isRockspecName(name) && !currentMimes.contains(LuaMime))

      val mimes =
        if (isJsonName(name)) {
          if (currentMimes.contains(JsonMime)) currentMimes
          else currentMimes + JsonMime
        } else if (isRockspecName(name)) {
          if (currentMimes.contains(LuaMime)) currentMimes
          else currentMimes + LuaMime
        } else if (
          // only a text claim is worth probing: an artifact Tika could not read
          // as text is not a JSON document, and probing it would spend a block
          // for nothing
          currentMimes.exists(_.startsWith("text/")) &&
          looksLikeJsonPrefix(probe.bytes, probe.length)
        ) {
          currentMimes + JsonMime
        } else currentMimes

      (mimes, evidence)
    }
  )

  // ---------------------------------------------------------------------------
  // The state machine
  // ---------------------------------------------------------------------------

  private final val StStart = 0
  private final val StFirstKey = 1
  private final val StKey = 2
  private final val StAfterKey = 3
  private final val StFirstValue = 4
  private final val StValue = 5
  private final val StAfterValue = 6
  private final val StString = 7
  private final val StEscape = 8
  private final val StHex = 9
  private final val StLiteral = 10
  private final val StNumber = 11
  private final val StDone = 12
  private final val StFail = -1

  private val TrueLit = "true".getBytes(StandardCharsets.US_ASCII)
  private val FalseLit = "false".getBytes(StandardCharsets.US_ASCII)
  private val NullLit = "null".getBytes(StandardCharsets.US_ASCII)

  /** Maximum container nesting tracked on the stack; beyond this the prefix is
    * declared not-JSON rather than risking an unbounded allocation.
    */
  private val MaxDepth = 256

  private inline def isWs(b: Int): Boolean =
    b == 0x20 || b == 0x09 || b == 0x0a || b == 0x0d

  private inline def isDigit(b: Int): Boolean = b >= 0x30 && b <= 0x39

  private inline def isHexDigit(b: Int): Boolean =
    isDigit(b) || (b >= 0x41 && b <= 0x46) || (b >= 0x61 && b <= 0x66)

  /** Does the first `length` bytes of `bytes` form a valid **JSON prefix**?
    *
    * A valid prefix means: every byte tested belongs to a token stream that is
    * grammatically correct JSON so far, and at least one value was opened. A
    * prefix that ends mid-token (mid-string, mid-number, inside a container) is
    * accepted, because the document may continue past the block; a prefix that
    * violates the grammar, or that is empty or all whitespace, is rejected.
    *
    * This is a byte-level DFA with a stack of open containers. It is not a
    * search for `{`: it tracks string escapes, the full number grammar,
    * literals, object key/colon structure, array element structure, matching
    * container types and trailing garbage.
    *
    * @param bytes
    *   the probe window
    * @param length
    *   how many of those bytes were actually read
    * @return
    *   true when the window is a valid JSON prefix
    */
  private[goatrodeo] def looksLikeJsonPrefix(
      bytes: Array[Byte],
      length: Int
  ): Boolean = {
    val end = math.min(length, bytes.length)
    val stack = new Array[Byte](MaxDepth)
    var depth = 0
    var state = StStart
    var sawValue = false
    var stringIsKey = false
    var lit: Array[Byte] = TrueLit
    var litPos = 0
    var hexLeft = 0

    // number sub-states: 0 start, 1 after '-', 2 zero, 3 int digits, 4 after '.',
    // 5 frac digits, 6 after e/E, 7 after exp sign, 8 exp digits
    var num = 0

    var i = 0
    while (i < end && state != StFail) {
      val b = bytes(i) & 0xff
      var advance = true

      state match {
        case StStart =>
          if (isWs(b)) ()
          else if (b == '{') {
            sawValue = true
            if (depth >= MaxDepth) state = StFail
            else { stack(depth) = '{'; depth += 1; state = StFirstKey }
          } else if (b == '[') {
            sawValue = true
            if (depth >= MaxDepth) state = StFail
            else { stack(depth) = '['; depth += 1; state = StFirstValue }
          } else if (b == '"') {
            sawValue = true; stringIsKey = false; state = StString
          } else if (b == 't') {
            sawValue = true; lit = TrueLit; litPos = 1; state = StLiteral
          } else if (b == 'f') {
            sawValue = true; lit = FalseLit; litPos = 1; state = StLiteral
          } else if (b == 'n') {
            sawValue = true; lit = NullLit; litPos = 1; state = StLiteral
          } else if (b == '-') { sawValue = true; num = 1; state = StNumber }
          else if (isDigit(b)) {
            sawValue = true
            num = if (b == '0') 2 else 3
            state = StNumber
          } else state = StFail

        case StFirstKey =>
          if (isWs(b)) ()
          else if (b == '"') { stringIsKey = true; state = StString }
          else if (b == '}') {
            depth -= 1
            state = if (depth == 0) StDone else StAfterValue
          } else state = StFail

        case StKey =>
          if (isWs(b)) ()
          else if (b == '"') { stringIsKey = true; state = StString }
          else state = StFail

        case StAfterKey =>
          if (isWs(b)) ()
          else if (b == ':') state = StStart
          else state = StFail

        case StFirstValue =>
          if (isWs(b)) ()
          else if (b == ']') {
            depth -= 1
            state = if (depth == 0) StDone else StAfterValue
          } else { state = StStart; advance = false }

        case StValue =>
          if (isWs(b)) () else { state = StStart; advance = false }

        case StAfterValue =>
          if (isWs(b)) ()
          else if (b == ',') {
            if (depth == 0) state = StFail
            else state = if (stack(depth - 1) == '{') StKey else StValue
          } else if (b == '}') {
            if (depth == 0 || stack(depth - 1) != '{') state = StFail
            else {
              depth -= 1
              state = if (depth == 0) StDone else StAfterValue
            }
          } else if (b == ']') {
            if (depth == 0 || stack(depth - 1) != '[') state = StFail
            else {
              depth -= 1
              state = if (depth == 0) StDone else StAfterValue
            }
          } else state = StFail

        case StDone =>
          if (!isWs(b)) state = StFail

        case StString =>
          if (b == '\\') state = StEscape
          else if (b == '"')
            state = if (stringIsKey) StAfterKey else StAfterValue
          else if (b < 0x20) state = StFail
          else ()

        case StEscape =>
          if (b == 'u') { hexLeft = 4; state = StHex }
          else if (
            b == '"' || b == '\\' || b == '/' || b == 'b' || b == 'f' ||
            b == 'n' || b == 'r' || b == 't'
          ) state = StString
          else state = StFail

        case StHex =>
          if (isHexDigit(b)) {
            hexLeft -= 1
            if (hexLeft == 0) state = StString
          } else state = StFail

        case StLiteral =>
          if (b == (lit(litPos) & 0xff)) {
            litPos += 1
            if (litPos == lit.length) state = StAfterValue
          } else state = StFail

        case StNumber =>
          num match {
            case 1 =>
              if (isDigit(b)) num = if (b == '0') 2 else 3 else state = StFail
            case 2 =>
              if (b == '.') num = 4
              else if (b == 'e' || b == 'E') num = 6
              else { state = StAfterValue; advance = false }
            case 3 =>
              if (isDigit(b)) ()
              else if (b == '.') num = 4
              else if (b == 'e' || b == 'E') num = 6
              else { state = StAfterValue; advance = false }
            case 4 =>
              if (isDigit(b)) num = 5 else state = StFail
            case 5 =>
              if (isDigit(b)) ()
              else if (b == 'e' || b == 'E') num = 6
              else { state = StAfterValue; advance = false }
            case 6 =>
              if (b == '+' || b == '-') num = 7
              else if (isDigit(b)) num = 8
              else state = StFail
            case 7 =>
              if (isDigit(b)) num = 8 else state = StFail
            case _ =>
              if (!isDigit(b)) { state = StAfterValue; advance = false }
          }

        case _ => state = StFail
      }

      if (advance) i += 1
    }

    state != StFail && sawValue
  }
}
