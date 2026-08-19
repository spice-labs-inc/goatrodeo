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

/** MIME-type augmenter that recognizes Java security properties files.
  *
  * Java `java.security` files and their included siblings are plain Java
  * properties files. Tika usually classifies them as `text/plain`. This
  * augmenter probes a small prefix and adds
  * `application/x-java-security-properties` when the content looks like a Java
  * security policy file.
  *
  * The detection is conservative: it requires either a Java security-specific
  * property key or a filename suffix that suggests a security properties file.
  */
object JavaSecurityDetector {

  /** MIME type added when a Java security properties file is detected. */
  val JavaSecurityMimeType: String = "application/x-java-security-properties"

  /** Maximum bytes read during detection. */
  val MaxReadBytes: Int = 4096

  /** Java security-specific property keys and directives. A match on any of
    * these anywhere in the prefix is enough to classify the file.
    */
  private val SecurityKeys: Set[String] = Set(
    "jdk.tls.disabledalgorithms",
    "jdk.certpath.disabledalgorithms",
    "jdk.tls.legacyalgorithms",
    "jdk.tls.namedgroups",
    "jdk.tls.ephemeraldhkeysize",
    "security.provider",
    "keystore.type",
    "policy.url",
    "login.config.url",
    "include"
  )

  /** Detect Java security properties markers in the given byte prefix.
    *
    * Detection is content-based only. The primary `java.security` file is
    * claimed by path inside the strategy; this detector is for
    * included/security sibling files that may live at unusual paths.
    *
    * @param artifact
    *   the artifact to probe
    * @return
    *   `Set(application/x-java-security-properties)` if detected, otherwise
    *   `Set.empty`
    */
  def detect(artifact: ArtifactWrapper): Set[String] = {
    if (looksBinary(artifact)) {
      Set.empty
    } else {
      readPrefix(artifact) match {
        case None => Set.empty
        case Some(text) =>
          val lower = text.toLowerCase
          if (hasSecurityKey(lower)) {
            Set(JavaSecurityMimeType)
          } else {
            Set.empty
          }
      }
    }
  }

  /** Augmenter entry point used by `ArtifactWrapper`. Purely additive. */
  /** Applicability rule: `java.security` properties are text-ish files; media,
    * class files, archives, and XML can never be one. Unknown mimes stay
    * probed.
    */
  private[goatrodeo] def mimeRule(mimes: Set[String]): Boolean =
    ArtifactWrapper.noneOf(
      "image/",
      "audio/",
      "video/",
      "application/java-vm",
      "application/java-archive",
      "application/vnd.android.package-archive",
      "application/xml"
    )(mimes)

  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    currentMimes ++ detect(artifact)
  }

  /** Read up to `MaxReadBytes` from the artifact as an ISO-8859-1 string.
    * Malformed bytes are replaced with the replacement character rather than
    * throwing.
    */
  private def readPrefix(artifact: ArtifactWrapper): Option[String] = {
    Try {
      artifact.withStream { stream =>
        val bytes = stream.readNBytes(MaxReadBytes)
        new String(bytes, StandardCharsets.ISO_8859_1)
      }
    }.toOption
  }

  /** Check whether the prefix looks like binary data. Binary prefixes are not
    * scanned for security markers to avoid wasted work and false positives.
    */
  private def looksBinary(artifact: ArtifactWrapper): Boolean = {
    if (artifact.size() == 0) {
      false
    } else {
      readPrefix(artifact) match {
        case None => true
        case Some(text) =>
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

  private def hasSecurityKey(text: String): Boolean = {
    SecurityKeys.exists(text.contains)
  }
}
