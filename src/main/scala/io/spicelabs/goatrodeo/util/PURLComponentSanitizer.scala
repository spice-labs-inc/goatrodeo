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

package io.spicelabs.goatrodeo.util

import scala.util.matching.Regex

/** Sanitizes pURL components at the point of extraction.
  *
  * The rules are applied close to the source (POM, manifest, filename, etc.)
  * rather than inside [[PURLHelpers]]. Each method returns `None` when the
  * input cannot be sanitized into a legal non-empty value. No exceptions are
  * thrown.
  */
object PURLComponentSanitizer {

  /** Characters legal in a Maven groupId or artifactId. */
  private val MavenIdLegalChars: Regex = raw"[A-Za-z0-9_\-.]".r

  /** Characters legal in a Maven version. */
  private val MavenVersionLegalChars: Regex = raw"[A-Za-z0-9_\-.+]".r

  /** Characters legal in a generic pURL name/namespace.
    *
    * Keeps letters, digits, underscore, hyphen, and dot. Spaces and other
    * characters are removed; callers that need to preserve whitespace should
    * pre-process the value or use a more permissive rule.
    */
  private val GenericIdentifierLegalChars: Regex = raw"[A-Za-z0-9_\-.]".r

  /** Characters legal in a generic pURL version. */
  private val GenericVersionLegalChars: Regex = raw"[A-Za-z0-9_\-.+]".r

  /** Sanitize a Maven groupId. */
  def sanitizeMavenGroupId(s: String): Option[String] =
    sanitizeMavenIdentifier(s)

  /** Sanitize a Maven artifactId. */
  def sanitizeMavenArtifactId(s: String): Option[String] =
    sanitizeMavenIdentifier(s)

  /** Sanitize a Maven version. */
  def sanitizeMavenVersion(s: String): Option[String] =
    sanitize(s, MavenVersionLegalChars)

  /** Sanitize a generic pURL identifier (name or namespace). */
  def sanitizeGenericIdentifier(s: String): Option[String] =
    sanitize(s, GenericIdentifierLegalChars)

  /** Sanitize a generic pURL version. */
  def sanitizeGenericVersion(s: String): Option[String] =
    sanitize(s, GenericVersionLegalChars)

  /** Sanitize a Docker image namespace (registry/user).
    *
    * Does not allow `/`.
    */
  def sanitizeDockerNamespace(s: String): Option[String] =
    sanitize(s, GenericIdentifierLegalChars)

  /** Sanitize a Docker image name/path.
    *
    * Allows `/` because Docker image paths can be nested (e.g.
    * `namespace/name/subname`). The pURL builder will percent-encode the slash
    * if necessary.
    */
  def sanitizeDockerName(s: String): Option[String] =
    sanitize(s, raw"[A-Za-z0-9_\-. /]".r)

  /** Sanitize a Docker image tag. */
  def sanitizeDockerTag(s: String): Option[String] =
    sanitize(s, GenericVersionLegalChars)

  /** Sanitize a Maven groupId or artifactId.
    *
    * Path-style separators (`/`) are normalized to `.` because Maven groupIds
    * are conventionally dot-separated (e.g. `org/apache/httpcomponents` is the
    * path form of `org.apache.httpcomponents`).
    */
  private def sanitizeMavenIdentifier(s: String): Option[String] = {
    val normalized = s.replace('/', '.')
    sanitize(normalized, MavenIdLegalChars)
  }

  /** Shared sanitization pipeline:
    *
    *   1. trim whitespace 2. remove characters outside the legal set 3.
    *      collapse sequences of two or more dots to a single dot 4. strip
    *      leading and trailing dots 5. return `None` if the result is empty
    */
  private def sanitize(s: String, legalChars: Regex): Option[String] = {
    val trimmed = s.trim
    if (trimmed.isEmpty) return None
    val legalOnly = legalChars.findAllIn(trimmed).mkString
    val collapsed = legalOnly.replaceAll("\\.{2,}", ".")
    val stripped = collapsed.replaceAll("^\\.+|\\.+$", "")
    if (stripped.isEmpty) None else Some(stripped)
  }
}
