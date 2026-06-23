package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.annatto.Ecosystem
import io.spicelabs.annatto.EcosystemRouter
import io.spicelabs.annatto.LanguagePackage
import io.spicelabs.annatto.LanguagePackageReader
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.PackageTagInfo
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional
import scala.util.Try

object AnnattoStrategy {
  private val supportedMimeTypes =
    EcosystemRouter.supportedMimeTypes().asScala.toSet

  def computeAnnattoFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = for {
      // for every type that has a mime type that is supported
      (_, wrapper) <- byUUID
      if wrapper.mimeType.intersect(supportedMimeTypes).nonEmpty
      pkg <- Try(
        wrapper.withFile(f => LanguagePackageReader.read(f.toPath()))
      ).toOption
    } yield (wrapper, pkg)

    val uuids: Set[String] = mine.map(_._1.uuid).toSet
    val revisedByUUID = byUUID.filterNot { case (name, _) =>
      uuids.contains(name)
    }

    val revisedByName = byName.filterNot { case (_, artifacts) =>
      artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map { case (artifact, pkg) => Annatto(artifact, pkg) }.toVector,
      revisedByUUID,
      revisedByName,
      "Annatto"
    )
  }
}

class Annatto(artifact: ArtifactWrapper, pkg: LanguagePackage)
    extends ToProcess {
  def markSuccessfulCompletion(): Unit = {
    artifact.finished()
  }
  override def itemCnt: Int = 1
  override def main: String = artifact.path()

  /** The mime type of the main artifact
    */
  def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = AnnattoState
  def getElementsToProcess(): (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> AnnattoState(artifact, pkg)

}

class AnnattoState(artifact: ArtifactWrapper, pkg: LanguagePackage)
    extends ProcessingState[SingleMarker, AnnattoState] {
  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): AnnattoState = this

  def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): AnnattoState = this
  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (Vector[String], AnnattoState) = {
    // annatto returns a com.github.packageurl.PackageURL; emit its own canonical
    // form. We keep the reader's canonicalization rather than re-validating
    // through coordinates, which is stricter than what some readers produce.
    pkg.toPurl().toScala.toVector.map(_.canonicalize().nn) -> this
  }

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], AnnattoState) = {
    // Build metadata tree using standard keys (MKC) and ad-hoc prefix
    val adHoc = MKC.adHoc("Annatto")
    val metadata = pkg.metadata()

    val tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]()
        +? maybeStringOrPair(MKC.NAME, metadata.name())
        +? maybeStringOrPair(MKC.VERSION, metadata.version())
        +? maybeStringOrPair(MKC.DESCRIPTION, metadata.description().toScala)
        +? maybeStringOrPair(MKC.LICENSE, metadata.license().toScala)
        +? maybeStringOrPair(MKC.PUBLISHER, metadata.publisher().toScala)
        +? maybeStringOrPair(
          MKC.PUBLICATION_DATE,
          metadata.publishedAt().map(_.toString).toScala
        )
        +? maybeStringOrPair(adHoc("Ecosystem"), ecoString(pkg.ecosystem()))
    tm -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, AnnattoState) =
    item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): AnnattoState = this

  /** Generate per-package tag info for Annatto packages.
    */
  override def maybePackageTag(marker: SingleMarker): Option[PackageTagInfo] = {
    val metadata = pkg.metadata()
    val publishedAt = metadata.publishedAt()

    val date: Option[java.util.Date] = publishedAt.toScala.map { instant =>
      new java.util.Date(instant.toEpochMilli())
    }

    Some(
      PackageTagInfo(
        name = metadata.name(),
        version = Some(metadata.version()),
        date = date
      )
    )
  }

  // Converts any of String, Option[String], (String, String) to Option[(String, TreeSet[StringOrPair])].
  // These three cases are the most common output from metadata and the output of thisfunction
  // will feed the +? operator directly
  private def maybeStringOrPair(
      key: String,
      s: String | Option[String] | (String, String)
  ): Option[(String, TreeSet[StringOrPair])] = {
    s match {
      case value: String => Some(key -> TreeSet(StringOrPair(value)))
      case value: Option[String] => {
        value match {
          case Some(str) => Some(key -> TreeSet(StringOrPair(str)))
          case None      => None
        }
      }
      case value: (String, String) =>
        Some(key -> TreeSet(StringOrPair(value._1, value._2)))
    }
  }

  private def ecoString(eco: Ecosystem): String = {
    eco match {
      case Ecosystem.COCOAPODS => "CocoaPods"
      case Ecosystem.CONDA     => "Conda"
      case Ecosystem.CPAN      => "Cpan"
      case Ecosystem.CRATES    => "Crates"
      case Ecosystem.GO        => "Go"
      case Ecosystem.HEX       => "Hex"
      case Ecosystem.LUAROCKS  => "LuaRocks"
      case Ecosystem.NPM       => "Npm"
      case Ecosystem.PACKAGIST => "Packagist"
      case Ecosystem.PYPI      => "PyPi"
      case Ecosystem.RUBYGEMS  => "RubyGems"
    }
  }
}
