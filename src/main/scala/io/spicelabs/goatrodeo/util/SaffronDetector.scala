package io.spicelabs.goatrodeo.util

import io.spicelabs.saffron.DiskFormat
import scala.util.Try
import scala.jdk.OptionConverters.RichOptional

object SaffronDetector {
  // detect if an artifact wrapper is a mime type known to saffron
  private def readFormat(artifact: ArtifactWrapper): Set[String] = {
    artifact.withFile(file => {
      Try(DiskFormat.detect(file.toPath()))
        .toOption
        .flatMap(_.toScala)
        .map(d => Set(d.mimeType()))
        .getOrElse(Set.empty[String])
    })
  }
  def mimeTypeAugmenter(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    val myMimes = readFormat(artifact)
    // why the conditional?
    // an empty set indicates that this augmentinator should absolutely do nothing to the
    // current mimes, otherwise we remove any that start with "text/"
    // This is still fairly heavy-handed, but if it turns out to do too much, the filter could
    // be narrowed to one that specifically filters out "text/x-vhdl" when myMimes contains
    // application/vhd". This happens in ONE specific case when tika misidentifies a .vhd file as
    // "text/x-vhdl"
    if myMimes.isEmpty then currentMimes else currentMimes.filterNot(_.startsWith("text/")) ++ myMimes
  }
}
