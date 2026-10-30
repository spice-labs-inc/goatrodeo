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
import org.apache.tika.mime.MediaType

object DotnetDetector {
  lazy val DOTNET_MIME: MediaType = {
    MediaType.parse("application/x-msdownload; format=pe32-dotnet")
  }

  /** The dotnet MIME as a string; most call sites want the string, not the
    * MediaType wrapper, and converting each time is wasted work.
    */
  lazy val DOTNET_MIME_STRING: String = DOTNET_MIME.toString()

  /** Both sides come from [[MimeFamilies]], the same pair the consumer of this
    * augmenter's MIME type uses, so a change to what this augmenter can handle
    * changes its consumer at the same moment.
    */
  private[goatrodeo] def mimeRule(
      mimes: Set[String],
      name: String = ""
  ): Boolean =
    !MimeFamilies.contradicts(mimes, MimeFamilies.cannotBePeImage) &&
      MimeFamilies.couldBe(mimes, MimeFamilies.couldBePeImage)

  /** Read the PE header out of a bounded prefix.
    *
    * Everything needed is in the first kilobyte: the DOS stub's `e_lfanew`, the
    * COFF header, the optional-header magic, and the data directory. Following
    * the CLI header into the metadata is Cilantro's job, in the item phase —
    * doing it here cost up to 290 KB per artifact (measured).
    */
  /** The PE header facts the probe extracts. A plain value type: the augmenter
    * uses it to make its own decision, so it is not evidence.
    */
  private[goatrodeo] final case class PeImage(
      machine: Int,
      cliDirectoryPresent: Boolean,
      probedBytes: Int
  )

  private[goatrodeo] def peInfo(bytes: Array[Byte]): Option[PeImage] = {
    def u16(off: Int): Option[Int] =
      if (off + 2 <= bytes.length)
        Some((bytes(off) & 0xff) | ((bytes(off + 1) & 0xff) << 8))
      else None
    def u32(off: Int): Option[Long] =
      if (off + 4 <= bytes.length)
        Some(
          ((bytes(off) & 0xffL)) |
            ((bytes(off + 1) & 0xffL) << 8) |
            ((bytes(off + 2) & 0xffL) << 16) |
            ((bytes(off + 3) & 0xffL) << 24)
        )
      else None

    val isMz =
      bytes.length >= 2 && bytes(0) == 'M'.toByte && bytes(1) == 'Z'.toByte
    if (!isMz) None
    else
      u32(0x3c).flatMap { lfanew0 =>
        val lfanew = lfanew0.toInt
        val peOk =
          lfanew >= 0 && lfanew + 24 <= bytes.length &&
            bytes(lfanew) == 'P'.toByte && bytes(lfanew + 1) == 'E'.toByte &&
            bytes(lfanew + 2) == 0 && bytes(lfanew + 3) == 0
        if (!peOk) None
        else {
          val machine = u16(lfanew + 4).getOrElse(0)
          val optOff = lfanew + 24
          u16(optOff).flatMap { magic =>
            val dataDirOff = magic match {
              case 0x10b => Some(optOff + 96) // PE32
              case 0x20b => Some(optOff + 112) // PE32+
              case _     => None
            }
            dataDirOff.map { dir =>
              val cliOff = dir + 14 * 8
              val cliPresent = (u32(cliOff).getOrElse(0L) != 0L) &&
                (u32(cliOff + 4).getOrElse(0L) != 0L)
              PeImage(machine, cliPresent, bytes.length)
            }
          }
        }
      }
  }

  val augmenter: MimeAugmenter = MimeAugmenter(
    id = "DotnetDetector",
    rule = mimeRule(_, _),
    budget = ProbeBudget(),
    fn = (artifact, currentMimes, evidence, probe) => {
      peInfo(probe.bytes) match {
        case None       => (currentMimes, evidence)
        case Some(info) =>
          // A present CLI data directory is what a .NET assembly *is*, so the
          // MIME is added only when that is true — never because it might be.
          val mimes =
            if (
              info.cliDirectoryPresent && !currentMimes
                .contains(DOTNET_MIME_STRING)
            )
              currentMimes + DOTNET_MIME_STRING
            else currentMimes
          // The PE header settled it: the CLI data directory is either present,
          // in which case the MIME is asserted, or absent, in which case the
          // answer is a negative one. Neither is indeterminate.
          (mimes, evidence)
      }
    }
  )
}
