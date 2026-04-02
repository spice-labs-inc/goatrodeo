package io.spicelabs.goatrodeo.util

import io.spicelabs.saffron.DiskFormat
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import scala.jdk.OptionConverters.RichOptional

object SaffronDetector {
  // detect if an artifact wrapper is a mime type known to saffron
  private def readFormat(artifact: ArtifactWrapper): Option[String] = {
    artifact.withFile(file => {
      Try {
        DiskFormat.detect(file.toPath())
      } match {
        case Failure(exception) => None
        case Success(value) => value.toScala.map(format => format.mimeType())
      }
    })
  }
  def mimeTypeAugmentor(
      artifact: ArtifactWrapper,
      currentMimes: Set[String]
  ): Set[String] = {
    readFormat(artifact) match {
      // Tika misidentifies vhd files as text/vhdl
      // if there are future problems with this filter being too loose, it can be tightened to
      // .filter(!_.startsWith("text/" && value == "application/vhd"))
      case Some(value) => currentMimes.filterNot(_.startsWith("text/")) + value
      case None        => currentMimes
    }
  }
}
