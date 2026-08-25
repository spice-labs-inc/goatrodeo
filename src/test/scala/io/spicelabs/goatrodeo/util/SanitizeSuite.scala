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

import munit.FunSuite

/** Tests for [[Sanitize]] — the shared escaping of untrusted strings before
  * they are interpolated into log lines.
  *
  * WHAT: every character that a hostile corpus can smuggle into a log line (C0
  * controls, C1 controls, Unicode line separators) is escaped as `\uXXXX`;
  * everything else passes through; over-long strings are capped.
  *
  * WHY: file names come from untrusted corpora, and a raw path in a log line
  * can inject terminal escapes, fake log lines, or render as line breaks. The
  * escape set is pinned here so the MIME pass and the file walk cannot drift
  * apart.
  *
  * LLM note: S-x = test id.
  */
class SanitizeSuite extends FunSuite {

  // S-1 — the full escape set: C0, C1, and U+2028/U+2029 are escaped; no raw
  // control character survives.
  test("S-1 control characters and line separators are escaped") {
    val hostile =
      "a\u0000b\u0007c\u001bd\u001fe\u007ff\u009fg\u2028h\u2029i\tj\nk\r"
    val escaped = Sanitize.path(hostile)
    assert(!escaped.contains("\n"), s"raw LF survives: $escaped")
    assert(!escaped.contains("\r"), s"raw CR survives: $escaped")
    assert(!escaped.contains("\t"), s"raw tab survives: $escaped")
    assert(!escaped.contains("\u001b"), s"raw ESC survives: $escaped")
    assert(!escaped.contains("\u007f"), s"raw DEL survives: $escaped")
    assert(!escaped.contains("\u009f"), s"raw C1 survives: $escaped")
    assert(!escaped.contains("\u2028"), s"raw LS survives: $escaped")
    assert(!escaped.contains("\u2029"), s"raw PS survives: $escaped")
    assert(!escaped.contains("\u0000"), s"raw NUL survives: $escaped")
    assert(escaped.contains("\\u0000"), s"expected \\u0000: $escaped")
    assert(escaped.contains("\\u2028"), s"expected \\u2028: $escaped")
  }

  // S-2 — harmless characters pass through unchanged, and the escape
  // sequences themselves do not contain the raw character.
  test("S-2 benign strings pass through unchanged") {
    val benign = "/usr/lib/JetBrains/ jdk8u_jdk-FileTreeWalker.java"
    assertEquals(Sanitize.path(benign), benign)
  }

  // S-3 — the length cap: over-long paths are truncated with a marker, so a
  // megabyte filename cannot produce a megabyte log line.
  test("S-3 over-long strings are capped") {
    val long = "x" * (Sanitize.MaxLoggedLength + 1000)
    val capped = Sanitize.capped(long)
    assert(capped.length <= Sanitize.MaxLoggedLength + 1)
    assert(capped.endsWith("\u2026"), "cap must carry the ellipsis marker")
    val short = "ok"
    assertEquals(Sanitize.capped(short), "ok")
  }
}
