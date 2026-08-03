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
import scala.util.Try

/** MIME-type augmenter that recognizes OpenSSL configuration files.
  *
  * OpenSSL configs are INI-style files that Tika classifies as `text/plain`.
  * This augmenter probes a small prefix and adds `application/x-openssl-config`
  * when the content looks like an OpenSSL config.
  *
  * The detection is intentionally conservative: it requires both INI-style
  * section headers and at least one OpenSSL-specific keyword. Files that are
  * ambiguous fall through to their existing MIME type.
  */
object OpenSSLConfigDetector {

  /** MIME type added when an OpenSSL config is detected. */
  val OpenSSLConfigMimeType: String = "application/x-openssl-config"

  /** Maximum bytes read during detection. */
  val MaxReadBytes: Int = 4096

  /** Strong OpenSSL-specific keywords and directives. A match on any of these
    * anywhere in the prefix is enough to classify the file as an OpenSSL
    * config.
    */
  private val StrongSignals: Set[String] = Set(
    "openssl_conf",
    "ssl_conf",
    ".include",
    "config_diagnostics",
    "cipherstring",
    "ciphersuites",
    "minprotocol",
    "maxprotocol",
    "oid_section",
    "default_ca",
    "distinguished_name",
    "req_extensions",
    "x509_extensions",
    "basicconstraints",
    "keyusage",
    "subjectkeyidentifier",
    "authoritykeyidentifier",
    "subjectaltname",
    "issueraltname",
    "issuersigntool",
    "sbgp-autonomoussysnum",
    "sbgp-ipaddrblock",
    "issuingdistributionpoint",
    "ssleay::"
  )

  /** Medium-signal OpenSSL keywords. These are only considered when the file
    * also has INI-style section headers, to avoid false positives on generic
    * INI/TOML files.
    */
  private val MediumSignals: Set[String] = Set(
    "options",
    "curves",
    "signaturealgorithms",
    "default_bits",
    "default_keyfile",
    "default_md",
    "encrypt_key",
    "prompt",
    "randfile",
    "oid_file",
    "new_oids"
  )

  /** Section header pattern, e.g. `[ req ]`.
    *
    * Package-private so `OpenSSLConfigParser` can reuse the same regex.
    */
  private[util] val SectionHeaderPattern = "^\\s*\\[.+\\]\\s*$".r

  /** Detect OpenSSL config markers in the given byte prefix.
    *
    * Detection is based on OpenSSL-specific keywords and directives. If the
    * file has at least one of these markers, it is classified as an OpenSSL
    * config. The probe is conservative enough to avoid generic INI files.
    *
    * @param artifact
    *   the artifact to probe
    * @return
    *   `Set(application/x-openssl-config)` if detected, otherwise `Set.empty`
    */
  def detect(artifact: ArtifactWrapper): Set[String] = {
    if (looksBinary(artifact)) {
      Set.empty
    } else {
      readPrefix(artifact) match {
        case None => Set.empty
        case Some(text) =>
          val lower = text.toLowerCase
          if (
            hasStrongSignal(lower) || (hasSectionHeader(
              lower
            ) && hasMediumSignal(lower))
          ) {
            Set(OpenSSLConfigMimeType)
          } else {
            Set.empty
          }
      }
    }
  }

  /** Augmenter entry point used by `ArtifactWrapper`. Purely additive. */
  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    currentMimes ++ detect(artifact)
  }

  /** Read up to `MaxReadBytes` from the artifact as a UTF-8 string. Malformed
    * UTF-8 is replaced with the replacement character rather than throwing.
    */
  private def readPrefix(artifact: ArtifactWrapper): Option[String] = {
    Try {
      artifact.withStream { stream =>
        val bytes = stream.readNBytes(MaxReadBytes)
        new String(bytes, StandardCharsets.UTF_8)
      }
    }.toOption
  }

  /** Check whether the prefix looks like binary data. Binary prefixes are not
    * scanned for config markers to avoid wasted work and false positives.
    */
  private def looksBinary(artifact: ArtifactWrapper): Boolean = {
    if (artifact.size() == 0) {
      false
    } else {
      readPrefix(artifact) match {
        case None       => true
        case Some(text) =>
          // If more than 10% of characters are control characters (excluding
          // whitespace), treat as binary.
          if (text.isEmpty) {
            false
          } else {
            val controlCount = text.count { c =>
              c < 0x20 && !c.isWhitespace
            }
            controlCount.toDouble / text.length > 0.1
          }
      }
    }
  }

  private def hasSectionHeader(text: String): Boolean = {
    text.linesIterator.exists(SectionHeaderPattern.matches)
  }

  private def hasStrongSignal(text: String): Boolean = {
    StrongSignals.exists(text.contains)
  }

  private def hasMediumSignal(text: String): Boolean = {
    MediumSignals.exists(text.contains)
  }
}
