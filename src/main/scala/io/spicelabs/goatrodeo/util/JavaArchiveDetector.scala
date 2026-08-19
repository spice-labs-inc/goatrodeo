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

import java.io.InputStream

/** Augments Tika mime type detection for Java archive formats that Tika may
  * classify as generic `application/zip` rather than
  * `application/java-archive`.
  *
  * Covers: `.ear`, `.par`, `.sar`, `.nar`, `.jpi`, `.hpi`, `.kar`, `.far`,
  * `.lpkg`, `.rar` (Java rar, not WinRAR), `.zap`.
  *
  * These are all ZIP-format files that the JVM/Java ecosystem treats as
  * `application/java-archive`, but Tika may not have specific mappings for the
  * less common extensions.
  *
  * Detection logic:
  *   - File extension matches a known Java archive extension AND
  *   - File has ZIP magic bytes (PK\x03\x04) OR current mimes already contain a
  *     ZIP-family type
  *   - THEN add `application/java-archive` to the mime set
  */
object JavaArchiveDetector {

  private val JavaArchiveMime: String = "application/java-archive"

  val javaArchiveExtensions = Set(
    ".ear",
    ".par",
    ".sar",
    ".nar",
    ".jpi",
    ".hpi",
    ".kar",
    ".far",
    ".lpkg",
    ".rar",
    ".zap",
    ".war",
    ".jar",
    ".a",
    ".nar",
    ".zar"
  )

  private def isZip(input: InputStream): Boolean = {
    input.mark(4)
    try {
      val b0 = input.read()
      val b1 = input.read()
      val b2 = input.read()
      val b3 = input.read()
      b0 == 0x50 && b1 == 0x4b && b2 == 0x03 && b3 == 0x04
    } catch {
      case _: Exception => false
    } finally {
      input.reset()
    }
  }

  private val zipFamilyMimes: Set[String] = Set(
    "application/zip",
    "application/java-archive",
    "application/x-zip-compressed"
  )

  /** Applicability rule: a JAR is a zip; text/XML/class files can never be
    * one. Binaries with unknown mimes stay probed (the zip check is cheap).
    */
  private[goatrodeo] def mimeRule(mimes: Set[String]): Boolean =
    ArtifactWrapper.noneOf(
      "text/",
      "application/xml",
      "application/java-vm"
    )(mimes)

  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    val filename = artifact.filenameWithNoPath.toLowerCase()
    val hasJavaExt = javaArchiveExtensions.exists(ext => filename.endsWith(ext))
    if (!hasJavaExt) currentMimes
    else {
      val alreadyZip = (currentMimes & zipFamilyMimes).nonEmpty
      if (alreadyZip) currentMimes + JavaArchiveMime
      else {
        val looksLikeZip = artifact.withStream(s => isZip(s))
        if (looksLikeZip) currentMimes + JavaArchiveMime
        else currentMimes
      }
    }
  }
}
