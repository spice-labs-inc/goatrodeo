package io.spicelabs.goatrodeo.testsupport

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

/** Builds in-memory ZIP archives shaped like an iOS `.ipa` for tests.
  *
  * An `.ipa` is a ZIP with a `Payload/<App>.app/` bundle inside; the builder
  * produces exactly that shape from caller-supplied member names and bytes, so
  * hostile/edge contents are easy to construct without committing binary blobs.
  */
object IpaZipBuilder {

  /** Create a ZIP archive with the given entries.
    *
    * @param entries
    *   (path, bytes) pairs; directory entries are auto-created as needed by the
    *   caller (pass explicit empty entries for them if relevant)
    * @return
    *   the ZIP bytes
    */
  def zip(entries: (String, Array[Byte])*): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    try {
      entries.foreach { case (name, data) =>
        zos.putNextEntry(new ZipEntry(name))
        zos.write(data)
        zos.closeEntry()
      }
    } finally zos.close()
    bos.toByteArray
  }

  /** Build a minimal but structurally real `.ipa`: `Payload/Min.app/` with an
    * `Info.plist` and an `embedded.mobileprovision`.
    *
    * @param infoPlistBytes
    *   the Info.plist bytes (binary or XML)
    * @param mobileProvisionBytes
    *   the embedded.mobileprovision bytes
    * @return
    *   the ZIP bytes
    */
  def minimalIpa(
      infoPlistBytes: Array[Byte],
      mobileProvisionBytes: Array[Byte]
  ): Array[Byte] =
    zip(
      "Payload/" -> Array.emptyByteArray,
      "Payload/Min.app/" -> Array.emptyByteArray,
      "Payload/Min.app/Info.plist" -> infoPlistBytes,
      "Payload/Min.app/embedded.mobileprovision" -> mobileProvisionBytes
    )
}
