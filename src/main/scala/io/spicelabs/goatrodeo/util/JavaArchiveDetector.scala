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

  /** The plain zip claim, which `.ipa` and `.nupkg` carry (they are zips with a
    * foreign name, not JARs).
    */
  val ZipMime: String = "application/zip"

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

  /** ZIP local-file header, from an already-read prefix. */
  private def isZipBytes(bytes: Array[Byte]): Boolean =
    bytes.length >= 4 && (bytes(0) & 0xff) == 0x50 && (bytes(
      1
    ) & 0xff) == 0x4b &&
      (bytes(2) & 0xff) == 0x03 && (bytes(3) & 0xff) == 0x04

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

  /** Both sides come from [[MimeFamilies]], the same pair the consumer of this
    * augmenter's MIME type uses, so a change to what this augmenter can handle
    * changes its consumer at the same moment.
    */
  private[goatrodeo] def mimeRule(
      mimes: Set[String],
      name: String = ""
  ): Boolean =
    !MimeFamilies.contradicts(mimes, MimeFamilies.cannotBeZipArchive) &&
      MimeFamilies.couldBe(mimes, MimeFamilies.couldBeZipArchive)

  val augmenter: MimeAugmenter = MimeAugmenter(
    id = "JavaArchiveDetector",
    rule = mimeRule(_, _),
    budget = ProbeBudget(),
    fn = (artifact, currentMimes, evidence, probe) => {
      val filename = artifact.filenameWithNoPath.toLowerCase()
      // `.ipa` and `.nupkg` are zip archives whose names Tika resolves to
      // `application/x-itunes-ipa` and `application/x-tika-ooxml`. Both must carry
      // a zip claim so the FileWalker's archive branch expands them, which is how
      // an iOS `Payload/<App>.app` bundle and a package's `lib/` contents are
      // reached at all. They are name-gated, exactly as the JAR extensions are, so
      // plain ZIPs and Android packages are unaffected.
      val containerName: Option[String] =
        javaArchiveExtensions
          .find(e => filename.endsWith(e))
          .orElse(if (filename.endsWith(".ipa")) Some(".ipa") else None)
          .orElse(if (filename.endsWith(".nupkg")) Some(".nupkg") else None)
      containerName match {
        case None => (currentMimes, evidence)
        case Some(ext) =>
          val alreadyZip = (currentMimes & zipFamilyMimes).nonEmpty
          val zipMagic =
            alreadyZip || (probe.length >= 4 && isZipBytes(probe.bytes))
          // `.ipa` and `.nupkg` are zips with a foreign name, not JARs: they get
          // the plain zip claim, which is what the FileWalker archive branch and
          // the IPA/NuGet strategies key on. Everything else here is a JAR name.
          val claimed =
            if (ext == ".ipa" || ext == ".nupkg") ZipMime else JavaArchiveMime
          val mimes =
            if (zipMagic && !currentMimes.contains(claimed))
              currentMimes + claimed
            else currentMimes
          // The zip-magic check settled the question; nothing indeterminate
          // remains for a downstream evaluator to continue from.
          (mimes, evidence)
      }
    }
  )
}
