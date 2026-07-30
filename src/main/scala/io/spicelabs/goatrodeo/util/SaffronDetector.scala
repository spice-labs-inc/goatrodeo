package io.spicelabs.goatrodeo.util

import io.spicelabs.saffron.DiskFormat

import scala.jdk.OptionConverters.RichOptional
import scala.util.Try

object SaffronDetector {

  /** VM disk images are large files, and probing one requires a path. For an
    * in-memory artifact, `withFile` spills the entire artifact to a temp file
    * just to read a header — wasteful when run on every artifact (e.g. each
    * class file extracted from a JAR). Below this size an in-memory artifact is
    * never a disk image, so we skip the probe (and the spill). Real files on
    * disk are cheap to probe and are always checked.
    */
  val minInMemoryProbeSize: Long = 1L * 1024 * 1024

  // detect if an artifact wrapper is a mime type known to saffron
  private def readFormat(artifact: ArtifactWrapper): Set[String] = {
    artifact.withFile(file => {
      Try(DiskFormat.detect(file.toPath())).toOption
        .flatMap(_.toScala)
        .map(d =>
          // RAW disk images report the generic octet-stream MIME type. Promote
          // it to a Saffron-specific MIME type so FileWalker does not reject
          // the file as "definitely not an archive".
          if (d == DiskFormat.RAW) Set("application/x-saffron-raw-disk")
          else Set(d.mimeType())
        )
        .getOrElse(Set.empty[String])
    })
  }
  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    // Avoid spilling small in-memory artifacts to a temp file just to rule out
    // a disk image they cannot be.
    if (!artifact.isRealFile() && artifact.size() < minInMemoryProbeSize)
      return currentMimes

    val myMimes = readFormat(artifact)
    // why the conditional?
    // an empty set indicates that this augmentinator should absolutely do nothing to the
    // current mimes, otherwise we remove any that start with "text/"
    // This is still fairly heavy-handed, but if it turns out to do too much, the filter could
    // be narrowed to one that specifically filters out "text/x-vhdl" when myMimes contains
    // application/vhd". This happens in ONE specific case when tika misidentifies a .vhd file as
    // "text/x-vhdl"
    if myMimes.isEmpty then currentMimes
    else currentMimes.filterNot(_.startsWith("text/")) ++ myMimes
  }
}
