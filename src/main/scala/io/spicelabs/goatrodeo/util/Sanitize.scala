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

/** Escaping of untrusted strings before they are interpolated into log lines.
  *
  * WHY a shared object: the adaptive MIME pass introduced per-path escaping for
  * exactly this purpose, and the parallel file walk needs the same guarantee.
  * Two copies would drift; one definition pins the escape set and the length
  * cap in one place with tests.
  *
  * An untrusted corpus controls file names, so a raw path in a log line can
  * inject terminal escapes, fake log lines (via `\r` or `\n`), or Unicode line
  * separators that some sinks render as line breaks. Every character that can
  * do that is escaped as `\uXXXX`; everything else passes through unchanged.
  */
object Sanitize {

  /** The longest path that will ever be logged; longer paths are truncated with
    * a marker. A hostile corpus can contain multi-megabyte names, and a
    * megabyte log line is its own denial of service.
    */
  val MaxLoggedLength: Int = 2048

  /** Escape C0 (0x00–0x1F), C1 (0x7F–0x9F), and the Unicode line separators
    * U+2028/U+2029 as `\uXXXX`. All other characters pass through unchanged.
    */
  def path(raw: String): String = {
    val sb = new StringBuilder(raw.length)
    raw.foreach { ch =>
      val c = ch.toInt
      if (
        c < 0x20 || (c >= 0x7f && c <= 0x9f) ||
        c == 0x2028 || c == 0x2029
      ) {
        sb.append(f"\\u${c}%04x")
      } else {
        sb.append(ch)
      }
    }
    sb.toString
  }

  /** `path(raw)` plus a length cap: strings longer than `max` are truncated to
    * `max` characters and suffixed with an ellipsis marker.
    */
  def capped(raw: String, max: Int = MaxLoggedLength): String = {
    val escaped = path(raw)
    if (escaped.length <= max) escaped
    else escaped.substring(0, max) + "\u2026"
  }
}
