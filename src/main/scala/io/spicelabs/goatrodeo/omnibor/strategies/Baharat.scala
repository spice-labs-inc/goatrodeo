package io.spicelabs.goatrodeo.omnibor.strategies

import com.github.packageurl.PackageURL
import com.typesafe.scalalogging.Logger
import io.spicelabs.baharat.Package
import io.spicelabs.baharat.PackageReader
import io.spicelabs.goatrodeo.omnibor.Item
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
import org.json4s.*
import org.json4s.native.JsonMethods.*

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.jdk.OptionConverters.RichOptional
import scala.util.Try

object BaharatStrategy {
  val logger = Logger(this.getClass())

  /** MIME types for all supported package formats */
  val supportedMimeTypes: Set[String] = Set(
    "application/x-rpm",
    "application/x-debian-package",
    "application/x-xz", // Pacman .pkg.tar.xz
    "application/zstd", // Pacman .pkg.tar.zst
    "application/gzip", // APK, OpenBSD .tgz
    "application/x-tar" // FreeBSD .pkg (tar+zstd)
  )

  /** Compute files to process using Baharat. This can replace the existing
    * Debian strategy and adds support for RPM, Pacman, APK, FreeBSD, and
    * OpenBSD packages.
    */
  def computeBaharatFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {

    val mine = for {
      // for every type that has a mime type that is supported
      (_, wrapper) <- byUUID
      if wrapper.mimeType.intersect(supportedMimeTypes).nonEmpty
      // if we can create a pkg, then it's a thing we will handle
      pkg <- Try(wrapper.withFile(f => PackageReader.read(f.toPath()))).toOption
    } yield (wrapper, pkg)

    val uuids: Set[String] = mine.map(_._1.uuid).toSet

    val revisedByUUID = byUUID.filter { case (name, _) =>
      !uuids.contains(name)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map { case (artifact, pkg) => Baharat(artifact, pkg) }.toVector,
      revisedByUUID,
      revisedByName,
      "Baharat"
    )

  }

}

class Baharat(artifact: ArtifactWrapper, pkg: Package) extends ToProcess {
  def markSuccessfulCompletion(): Unit = {
    artifact.finished()
  }
  override def itemCnt: Int = 1
  override def main: String = artifact.path()

  /** The mime type of the main artifact
    */
  def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = BaharatState
  def getElementsToProcess(): (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> BaharatState(artifact, pkg)
}

/** State maintained during Baharat package processing.
  */
class BaharatState(artifact: ArtifactWrapper, pkg: Package)
    extends ProcessingState[SingleMarker, BaharatState] {

  /** Call the state object at the beginning of processing an ArtfactWrapper
    * into an Item. This is done just after the generation of the gitoids.
    *
    * This allows state to capture, for example, the contents of a pom file
    *
    * @param artifact
    *   the artifact
    * @param item
    *   the currently build item
    * @param marker
    *   the marker
    * @return
    *   the updated state
    */
  def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): BaharatState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (Vector[PackageURL], BaharatState) = {
    Vector(pkg.packageUrl()) -> this
  }

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], BaharatState) = {
    val metadata = pkg.metadata()
    // format the metadata
    val lst: Vector[(String, TreeSet[StringOrPair])] = Vector(
      "arch" -> Some(metadata.arch()),
      "buildtime" -> metadata.buildTime().map(_.toString()).toScala,
      "description" -> metadata.description().toScala,
      "epoch" -> metadata.epoch().map(_.toString()).toScala,
      "group" -> metadata.group().toScala,
      "installed_size" -> Some(metadata.installedSize().toString()),
      "license" -> metadata.license().toScala,
      "maintainer" -> metadata.maintainer().toScala,
      "name" -> Some(metadata.name()),
      "release" -> metadata.release().toScala,
      "summary" -> metadata.summary().toScala,
      "url" -> metadata.url().toScala,
      "vendor" -> metadata.vendor().toScala,
      "version" -> Some(metadata.version())
    ).flatMap {
      case (k, Some(v)) => Some(k -> TreeSet(StringOrPair(v)))
      case _            => None
    }

    // compute dependencies
    val dependencies: JArray = JArray(
      metadata
        .dependencies()
        .asScala
        .toList
        .map(d => JString(d.toVersionedString()))
    )

    // and provides
    val provides: JArray = JArray(
      metadata
        .provides()
        .asScala
        .toList
        .map(p => JString(p.toVersionedString()))
    )

    TreeMap(
      lst :+ ("dependencies" -> TreeSet(
        StringOrPair("application/json", compact(render(dependencies)))
      )) :+ ("provides" -> TreeSet(
        StringOrPair("application/json", compact(render(provides)))
      ))*
    ) -> this

  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, BaharatState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): BaharatState = this

}
