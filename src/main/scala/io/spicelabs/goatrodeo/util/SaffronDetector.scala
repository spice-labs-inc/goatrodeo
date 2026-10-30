package io.spicelabs.goatrodeo.util

import io.spicelabs.saffron.SaffronProbe

import java.util.Arrays
import java.util.Locale
import scala.jdk.OptionConverters.RichOptional
import scala.util.Try

/** Reports the binary formats Saffron can traverse into as MIME types, so that
  * `FileWalker` routes the artifact to Saffron's disk and container readers.
  *
  * Detection goes through [[SaffronProbe]], Saffron's stream-friendly probe:
  * the caller reads a bounded prefix from the front of the artifact (and, for
  * footer formats, a bounded suffix from the tail) off the artifact's own
  * stream and passes those bytes. Signature knowledge — byte offsets, footers,
  * superblocks, extension fallbacks — stays in Saffron; this object only maps
  * the kind Saffron reports to a MIME name.
  *
  * The probe is deliberately not path-based. `ArtifactWrapper.withFile` spills
  * an in-memory artifact to a temporary file, so probing through it writes the
  * whole artifact to disk to read a header. The stream probe costs one bounded
  * read and never touches the filesystem.
  */
object SaffronDetector {

  /** Bytes read from the tail of an artifact before probing: one block. The
    * suffix exists for the VHD (`conectix`) and DMG (`koly`) footers, which are
    * far smaller than a block, so a block is generous and keeps the read
    * symmetric with the head.
    */
  val probeSuffixBytes: Int = MimeProbe.MaxBytes

  /** Bytes read from the front of an artifact before probing: one block.
    *
    * Every signature this augmenter is responsible for decided lives in the
    * first 2 KiB -- the disk head magics, the container head magics, the
    * squashfs superblock, the partition-table probe at offset 512 and the
    * non-btrfs filesystem superblocks. The ELF container type is not decided
    * here at all any more: the carve augmenter claims it from the four magic
    * bytes it reads, which is why this prefix no longer has to reach the end of
    * a large binary.
    */
  val probePrefixBytes: Int = MimeProbe.MaxBytes

  /** The container MIME for an ELF binary. Named once: the carve augmenter
    * claims it from the ELF magic in the first block, and the Saffron kind map
    * uses the same value.
    */
  val ElfMime: String = "application/x-saffron-elf"

  /** MIME name for a raw disk or bare filesystem image. Saffron mounts both
    * directly, and `FileWalker` treats this name as a disk to offer to
    * `DiskReader`.
    */
  private val RawDiskMime = "application/x-saffron-raw-disk"

  /** MIME name for every kind the probe can report. A kind absent from this map
    * adds no MIME (the artifact keeps the MIME set it already had).
    */
  private val KindMimes: Map[SaffronProbe.Kind, String] = Map(
    SaffronProbe.Kind.DISK_QCOW2 -> "application/x-qcow2",
    SaffronProbe.Kind.DISK_VMDK -> "application/x-vmdk",
    SaffronProbe.Kind.DISK_VHD -> "application/x-vhd",
    SaffronProbe.Kind.DISK_VHDX -> "application/x-vhdx",
    SaffronProbe.Kind.DISK_VDI -> "application/x-vdi",
    SaffronProbe.Kind.DISK_RAW -> RawDiskMime,
    SaffronProbe.Kind.DISK_AMI -> "application/x-ami",
    // Saffron's kind taxonomy has no GCP entry, so a `.tar.gz`/`.tgz` Google
    // Compute image and a `.img.gz`/`.raw.gz` gzip-wrapped raw disk report the
    // same kind. `mimeFor` splits them by name.
    SaffronProbe.Kind.DISK_GZIP_WRAPPED_RAW -> "application/gzip",
    SaffronProbe.Kind.CONTAINER_LINUX_KERNEL -> "application/x-saffron-linux-kernel",
    SaffronProbe.Kind.CONTAINER_FIT_IMAGE -> "application/x-saffron-fit-image",
    SaffronProbe.Kind.CONTAINER_DTB -> "application/x-saffron-dtb",
    SaffronProbe.Kind.CONTAINER_ELF -> ElfMime,
    SaffronProbe.Kind.CONTAINER_RPI_FIRMWARE -> "application/x-saffron-rpi-firmware",
    SaffronProbe.Kind.CONTAINER_ANDROID_BOOT -> "application/x-saffron-android-boot",
    SaffronProbe.Kind.CONTAINER_COMPRESSED_SINGLE -> "application/x-saffron-compressed-single",
    SaffronProbe.Kind.CONTAINER_WIM -> "application/x-saffron-wim",
    SaffronProbe.Kind.CONTAINER_DMG -> "application/x-saffron-dmg",
    // A bare filesystem image is a raw disk whose single filesystem Saffron
    // can mount directly, so it carries the raw-disk MIME.
    SaffronProbe.Kind.FILESYSTEM_EXT -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_FAT -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_EXFAT -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_NTFS -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_XFS -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_BTRFS -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_SQUASHFS -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_HFSPLUS -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_APFS -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_SWAP -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_JFFS2 -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_CRAMFS -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_YAFFS2 -> RawDiskMime,
    SaffronProbe.Kind.FILESYSTEM_UBIFS -> RawDiskMime,
    SaffronProbe.Kind.CONTAINER_UBI -> RawDiskMime
  )

  /** The kinds that name a Saffron *container* rather than a disk or a bare
    * filesystem. `FileWalker` mounts these through `BinaryContainerMount` and
    * everything else through `DiskReader`, so this set — not every Saffron kind
    * — defines [[containerMimeTypes]].
    */
  private val containerKinds: Set[SaffronProbe.Kind] = Set(
    SaffronProbe.Kind.CONTAINER_LINUX_KERNEL,
    SaffronProbe.Kind.CONTAINER_FIT_IMAGE,
    SaffronProbe.Kind.CONTAINER_DTB,
    SaffronProbe.Kind.CONTAINER_ELF,
    SaffronProbe.Kind.CONTAINER_RPI_FIRMWARE,
    SaffronProbe.Kind.CONTAINER_ANDROID_BOOT,
    SaffronProbe.Kind.CONTAINER_COMPRESSED_SINGLE,
    SaffronProbe.Kind.CONTAINER_WIM,
    SaffronProbe.Kind.CONTAINER_DMG
  )

  /** Every container MIME this detector can emit. */
  val containerMimeTypes: Set[String] = containerKinds.flatMap(KindMimes.get)

  /** The container MIMEs the file walker will mount.
    *
    * ELF is deliberately absent. An ELF is a container in Saffron's taxonomy,
    * but mounting one is expensive and, in a container image, it happens
    * thousands of times: every `.so` and every binary in every layer. The
    * certificate material inside an ELF is reached by reading its section and
    * symbol tables, not by mounting it, so mounting is left out of the default
    * path.
    */
  val mountableContainerMimeTypes: Set[String] =
    containerMimeTypes - ElfMime

  /** Read up to `n` bytes from the front of the artifact through a fresh
    * stream. Never throws; the result may be shorter than `n`.
    */
  private def readPrefix(artifact: ArtifactWrapper, n: Int): Array[Byte] = {
    val want = math.min(n.toLong, artifact.size()).toInt
    artifact.withStream(stream => readUpTo(stream, want))
  }

  /** Read up to `n` bytes from the end of the artifact through a fresh stream.
    * Never throws; the result may be shorter than `n` (or empty).
    */
  private def readSuffix(artifact: ArtifactWrapper, n: Int): Array[Byte] = {
    val size = artifact.size()
    val want = math.max(0L, math.min(n.toLong, size)).toInt
    if (want == 0) Array.emptyByteArray
    else
      artifact.withStream { stream =>
        var remaining = math.max(0L, size - want)
        var stop = false
        while (remaining > 0 && !stop) {
          val skipped = stream.skip(remaining)
          if (skipped <= 0) stop = true else remaining -= skipped
        }
        readUpTo(stream, want)
      }
  }

  /** Read up to `n` bytes from the stream. Returns what it got when the stream
    * ends early, and never loops forever on a stream that returns nothing.
    */
  private def readUpTo(
      stream: java.io.InputStream,
      n: Int
  ): Array[Byte] = {
    val buffer = new Array[Byte](n)
    var total = 0
    var done = false
    while (total < n && !done) {
      val read = stream.read(buffer, total, n - total)
      if (read <= 0) done = true else total += read
    }
    if (total == n) buffer else Arrays.copyOf(buffer, total)
  }

  /** Map a probed kind to the MIME name to add.
    *
    * `DISK_GZIP_WRAPPED_RAW` is the one kind the probe cannot split on its own.
    * Saffron's kind taxonomy has no GCP entry, so `.tar.gz`/`.tgz` (a Google
    * Compute image) and `.img.gz`/`.raw.gz` (a gzip-wrapped raw disk) report
    * the same kind; the probe already reached it by name, so the split here is
    * by name too.
    */
  private def mimeFor(kind: SaffronProbe.Kind, name: String): Option[String] = {
    if (kind == SaffronProbe.Kind.DISK_GZIP_WRAPPED_RAW) {
      val lower = name.toLowerCase(Locale.ROOT)
      Some(
        if (lower.endsWith(".img.gz") || lower.endsWith(".raw.gz")) RawDiskMime
        else "application/gzip"
      )
    } else KindMimes.get(kind)
  }

  /** Ask Saffron whether it can traverse this artifact, from the retained
    * prefix plus a bounded tail read (the VHD and DMG footers live at the end).
    *
    * The head comes from the probe; the tail is a separate 512-byte read so the
    * total inspected stays one block.
    */
  private def readFormat(
      artifact: ArtifactWrapper,
      probe: AugmentProbe
  ): Option[(String, SaffronProbe.Kind)] = {
    val head =
      if (probe.length <= probePrefixBytes) probe.bytes
      else java.util.Arrays.copyOf(probe.bytes, probePrefixBytes)
    val suffix =
      if (artifact.size() > probePrefixBytes.toLong)
        readSuffix(artifact, probeSuffixBytes)
      else Array.emptyByteArray
    Try(
      SaffronProbe
        .detect(head, suffix, artifact.path())
        .toScala
        .flatMap(result =>
          mimeFor(result.kind(), artifact.filenameWithNoPath)
            .map(_ -> result.kind())
        )
    ).toOption.flatten
  }

  /** Both sides come from [[MimeFamilies]], shared with the file walker so that
    * a change to what counts as a container changes the augmenter and the
    * walker at the same moment. A text artifact is refused as prose unless
    * Tika's extension misreading says otherwise (`text/x-vhdl`,
    * `image/x-raw-*`), which is the only reason text was ever probed here.
    */
  /** Both sides: refuse a contradiction, refuse when nothing could be ours. */
  private[goatrodeo] def mimeRule(
      mimes: Set[String],
      name: String = ""
  ): Boolean =
    !MimeFamilies.contradictsDiskOrGuest(mimes, name) &&
      MimeFamilies.couldBeDiskOrGuest(mimes, name)

  val augmenter: MimeAugmenter = MimeAugmenter(
    id = "SaffronDetector",
    rule = mimeRule(_, _),
    budget = ProbeBudget(),
    fn = (artifact, currentMimes, evidence, probe) =>
      readFormat(artifact, probe) match {
        case None            => (currentMimes, evidence)
        case Some((mime, _)) =>
          // The format probe identified the container, so the MIME is asserted
          // and nothing is left indeterminate; no evidence is recorded.
          (currentMimes + mime, evidence)
      }
  )
}
