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

/** Extension-and-magic-byte short-circuit for MIME type detection.
  *
  * For well-known file types where the extension plus a few magic bytes
  * are sufficient to determine the MIME type, this detector bypasses the
  * (expensive) Tika content-based detection entirely. The rest of the
  * pipeline (augmenter chain, `notArchive` checks, etc.) runs unchanged.
  *
  * Currently handles:
  *   - `.class` files: verified by `CA FE BA BE` magic → `application/java-vm`
  *   - Java archives (`.jar`, `.war`, `.ear`, etc.): verified by `PK\x03\x04`
  *     magic → `application/java-archive`
  *
  * If the extension matches but the magic bytes do not, the detector returns
  * `None`, allowing Tika to perform full content-based detection as a
  * fallback.
  */
object ExtensionMimeDetector {

  /** Java class file magic: 0xCAFEBABE */
  private val ClassMagic = Array(0xCA.toByte, 0xFE.toByte, 0xBA.toByte, 0xBE.toByte)

  /** ZIP/JAR magic: PK\x03\x04 */
  private val ZipMagic = Array(0x50.toByte, 0x4B.toByte, 0x03.toByte, 0x04.toByte)

  /** Read the first 4 bytes of the stream and compare against `expected`.
    *
    * Returns `false` on any I/O error or if fewer than 4 bytes are
    * available.
    */
  private def checkMagic(input: InputStream, expected: Array[Byte]): Boolean = {
    input.mark(4)
    try {
      val buf = new Array[Byte](4)
      val read = input.read(buf)
      read == 4 && buf.sameElements(expected)
    } catch {
      case _: Exception => false
    } finally {
      input.reset()
    }
  }

  /** Attempt to determine the MIME type from extension + magic bytes.
    *
    * @param artifact
    *   the artifact wrapper to inspect
    * @return
    *   `Some(mimeType)` if the extension and magic bytes match a known
    *   type; `None` to fall through to Tika
    */
  def detect(artifact: ArtifactWrapper): Option[String] = {
    val lower = artifact.filenameWithNoPath.toLowerCase

    if (lower.endsWith(".class")) {
      val ok = artifact.withStream(s => checkMagic(s, ClassMagic))
      if (ok) Some("application/java-vm") else None
    } else if (JavaArchiveDetector.javaArchiveExtensions.exists(ext => lower.endsWith(ext))) {
      val ok = artifact.withStream(s => checkMagic(s, ZipMagic))
      if (ok) Some("application/java-archive") else None
    } else {
      None
    }
  }
}
