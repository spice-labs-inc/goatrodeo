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

import io.spicelabs.goatrodeo.testing.GoatRodeoFunSuite

import java.nio.charset.StandardCharsets

/** Tests for the JSON-like detection state machine and the augmenter built on
  * it.
  *
  * WHAT: `JsonLikeDetector` adds `application/json` when
  *
  *   - the artifact's name ends in `.json` (no read at all), or
  *   - the artifact's name says nothing, its MIME set is text-ish or
  *     `octet-stream`, and the first bytes are a valid **JSON prefix**: valid
  *     token stream up to the end of the probe, with at least one token opened.
  *
  * It also adds `text/x-lua` for a `.rockspec` name, which is the other type
  * Tika has no answer for.
  *
  * WHY: Tika has no JSON type. The name is the cheap and exact signal, but an
  * OCI config blob is named `blobs/sha256/<hex>` and is still JSON — that case
  * is precisely why the container strategies lost two of three images in the
  * `grinder_bt_pg` fixture. A name-only rule cannot see it, and a whole-file
  * parse cannot be afforded on the MIME pass, so the probe is bounded to one
  * block (4 KiB) and the decision is made from a prefix.
  *
  * THEORY: the machine is a byte-level DFA with a stack of open containers, not
  * a search for `{`. It tracks strings (including `\uXXXX` escapes), numbers
  * with their full grammar, the literals `true`/`false`/`null`, object
  * key/colon structure, array element structure, matching container types, and
  * trailing garbage. Acceptance at the block boundary:
  *
  *   - nothing that could start a value was seen (empty or all whitespace) ->
  *     reject, because leading whitespace is a valid *prefix* of JSON and would
  *     otherwise accept every text file;
  *   - a grammar violation before the boundary -> reject;
  *   - the input ran out inside a valid prefix -> accept (this is the OCI
  *     config case: 12 KB of JSON seen 4 KB at a time);
  *   - the outermost value closed and only whitespace follows -> accept;
  *   - the outermost value closed and anything else follows -> reject.
  *
  * A truncated prefix is accepted by design: the claim is "this begins as
  * JSON", which for a nameless text artifact is the only claim a bounded read
  * can support, and the alternative is losing every JSON document larger than
  * one block.
  *
  * LLM note: test ids are J-n. `J-1`..`J-9` pin the DFA directly;
  * `J-10`..`J-14` pin the augmenter end to end.
  */
class JsonLikeDetectorSuite extends GoatRodeoFunSuite {

  private def probe(s: String): Boolean =
    JsonLikeDetector.looksLikeJsonPrefix(
      s.getBytes(StandardCharsets.ISO_8859_1),
      s.length
    )

  // J-1 — a complete, valid document is JSON, and trailing whitespace is allowed.
  test("J-1 complete documents are accepted") {
    assert(probe("{\"a\":1}"))
    assert(probe("  {\"a\": 1, \"b\": [true, false, null]}  "))
    assert(probe("[1,2,3]"))
    assert(probe("[]"))
    assert(probe("{}"))
    assert(probe("true"))
    assert(probe("null"))
    assert(probe("-12.5e+3"))
    assert(probe("{\"a\":\"b\"}"))
    assert(probe("\"a string\""))
  }

  // J-2 — an empty or all-whitespace prefix is rejected. Leading whitespace is a
  // valid *prefix* of JSON, so without this rule every text file would be claimed.
  test("J-2 empty and whitespace-only prefixes are rejected") {
    assert(!probe(""))
    assert(!probe("   "))
    assert(!probe("\n\t\r\n"))
  }

  // J-3 — plain text that is not JSON is rejected.
  test("J-3 prose is rejected") {
    assert(!probe("hello world"))
    assert(!probe("openssl_conf = default_conf"))
    assert(!probe("key=value\nfoo=bar"))
    assert(!probe("<x>openssl_conf</x>"))
  }

  // J-4 — structural violations are rejected, not tolerated as a "prefix".
  test("J-4 structural violations are rejected") {
    assert(!probe("{\"a\":}"))
    assert(!probe("{a:1}"))
    assert(!probe("{\"a\" 1}"))
    assert(!probe("{\"a\":1,}"))
    assert(!probe("[1,]"))
    assert(!probe("{\"a\":1]"))
    assert(!probe("[1}"))
    assert(!probe("{,}"))
    assert(!probe("'a'"))
    assert(!probe("{}{}"))
    assert(!probe("{\"a\":1} trailing"))
  }

  // J-5 — numbers follow the JSON grammar, not a permissive digit scan.
  test("J-5 number grammar is enforced") {
    assert(probe("0"))
    assert(probe("123"))
    assert(probe("-0.5"))
    assert(probe("1e10"))
    assert(!probe("01"))
    assert(!probe("+1"))
    assert(!probe(".5"))
    // a truncated "1." is a valid prefix ("1.5" may follow past the block); a
    // number followed by a non-terminator is not
    assert(probe("1."))
    assert(!probe("1.x"))
  }

  // J-6 — strings, escapes and the non-ASCII byte range.
  test("J-6 strings and escapes are enforced") {
    assert(probe("\"a\\\"b\""))
    assert(probe("\"\\u00e9\""))
    assert(probe("\"tab\\there\""))
    assert(!probe("\"\\q\""))
    assert(!probe("\"\\u12\""))
    // a string cut off by the block boundary is a valid prefix, not an error
    assert(probe("\"unterminated"))
  }

  // J-7 — the truncation rule, which is the reason this probe exists: a document
  // larger than the block is accepted when what was read is a valid prefix.
  test("J-7 a truncated valid prefix is accepted") {
    assert(probe("{\"a\":1,\"b\":[1,2"))
    assert(probe("{\"architecture\":\"amd64\",\"os\":\"linux\",\"history\":[{"))
    assert(probe("{\"a\": "))
    // ... but a truncation that is already a grammar violation is not
    assert(!probe("{\"a\":}"))
  }

  // J-8 — binary prefixes are rejected at the first byte, which is why a disk
  // image can never be claimed as JSON by this machine.
  test("J-8 binary prefixes are rejected") {
    assert(!probe("\u0089PNG\r\n\u001a\n"))
    assert(!probe("\u007fELF\u0002\u0001\u0001"))
    assert(!probe("\u0000\u0000\u0000\u0000conectix"))
    assert(!probe("\u0068squashfs"))
  }

  // J-9 — the decision is made on the whole prefix, and a valid prefix that is
  // exactly the block length behaves the same as a shorter one.
  test("J-9 the whole probe is traversed") {
    val deep = new StringBuilder
    deep.append("""{"k":""")
    var i = 0
    while (i < 200) { deep.append(s"[$i,\"pad\","); i += 1 }
    val s = deep.toString
    assert(s.length > 1000, "the fixture must be long enough to matter")
    assert(probe(s))
    // a violation far from the start is still caught
    val broken = s + "]}"
    assert(!probe(broken + "@"))
  }

  // J-10 — a `.json` name is claimed with no probe at all.
  test("J-10 a json name is claimed without probing") {
    val w = ByteWrapper(
      "{\"a\":1}".getBytes(StandardCharsets.ISO_8859_1),
      "config.json",
      None
    )
    assert(w.mimeType.contains("application/json"))
  }

  // J-11 — a nameless text artifact whose prefix is a valid JSON prefix is
  // claimed. This is the OCI config blob case from the docker corpus: larger than
  // one block, no name signal, and previously lost, which cost the grinder and
  // postgres images their purls and their tags.
  test("J-11 a nameless OCI config blob is claimed") {
    val f = new java.io.File(
      "test_data/download/docker_tests/grinder_bt_pg_docker.tar"
    )
    assert(f.exists(), s"corpus fixture missing: ${f.getAbsolutePath}")
    val tar =
      new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
        new java.io.BufferedInputStream(new java.io.FileInputStream(f))
      )
    var checked = 0
    try {
      var entry = tar.getNextEntry
      while (entry != null) {
        val name = entry.getName
        if (name.startsWith("blobs/sha256/")) {
          val bytes = tar.readAllBytes()
          if (bytes.length > 4096 && bytes(0) == '{'.toByte) {
            val w = ByteWrapper(bytes, name, None)
            assert(
              w.mimeType.contains("application/json"),
              s"a ${bytes.length}-byte nameless JSON blob must be claimed: $name"
            )
            checked += 1
          }
        }
        entry = tar.getNextEntry
      }
    } finally tar.close()
    assert(
      checked >= 4,
      s"expected at least four nameless JSON config blobs in the corpus, saw $checked"
    )
  }

  // J-12 — prose in the same position earns nothing.
  test("J-12 a nameless prose fragment is not claimed") {
    val w = ByteWrapper(
      "this is a readme, not json\n".getBytes(StandardCharsets.ISO_8859_1),
      "blobs/sha256/abcdef",
      None
    )
    assert(!w.mimeType.contains("application/json"))
  }

  // J-13 — a `.rockspec` name claims `text/x-lua`, which Tika has no answer for.
  test("J-13 a rockspec name claims lua") {
    val w = ByteWrapper(
      "package = \"foo\"\nversion = \"1.0-1\"\n".getBytes(
        StandardCharsets.ISO_8859_1
      ),
      "foo-1.0-1.rockspec",
      None
    )
    assert(w.mimeType.contains("text/x-lua"))
  }

  // J-14 — binary artifacts are not probed for JSON at all: the rule refuses
  // them, so a PNG or an ELF never pays the parse.
  test("J-14 binary artifacts are refused by the rule") {
    assert(!JsonLikeDetector.mimeRule(Set("image/png")))
    assert(!JsonLikeDetector.mimeRule(Set("application/x-sharedlib")))
    assert(!JsonLikeDetector.mimeRule(Set("application/java-archive")))
    assert(JsonLikeDetector.mimeRule(Set("text/plain")))
    assert(JsonLikeDetector.mimeRule(Set("application/octet-stream")))
  }
}
