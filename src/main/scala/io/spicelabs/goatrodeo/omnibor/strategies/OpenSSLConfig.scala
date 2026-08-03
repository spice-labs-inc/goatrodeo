/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.PackageTagInfo
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.PurlSet
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.OpenSSLConfigData
import io.spicelabs.goatrodeo.util.OpenSSLConfigDetector
import io.spicelabs.goatrodeo.util.OpenSSLConfigParser

import java.nio.file.Paths
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Success

/** A bundle of OpenSSL configuration files discovered at the same archive
  * layer.
  *
  * Files are ordered by intra-layer dependency (`.include` references) so that
  * referenced files are processed before the files that reference them.
  * Ordering and parsing happen during strategy processing, not during the
  * MIME-based selection phase.
  *
  * @param files
  *   config files claimed by MIME type (not yet ordered)
  */
class OpenSSLConfigToProcess(val files: Vector[ArtifactWrapper])
    extends ToProcess {

  private lazy val resolved: (Vector[ArtifactWrapper], OpenSSLConfigState) =
    OpenSSLConfigToProcess.resolveBundle(files)

  def markSuccessfulCompletion(): Unit = {
    files.foreach(_.finished())
  }

  override def itemCnt: Int = files.size
  override def main: String = files.headOption.map(_.path()).getOrElse("")
  def mimeType: Set[String] =
    files.headOption.map(_.mimeType).getOrElse(Set.empty)

  type MarkerType = SingleMarker
  type StateType = OpenSSLConfigState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) = {
    val (ordered, state) = resolved
    ordered.map(_ -> SingleMarker()) -> state
  }
}

/** State maintained while processing an `OpenSSLConfigToProcess` bundle.
  *
  * The state is immutable. Each `finalAugmentation` call records the GitOID of
  * the just-processed file and, for files that reference already-processed
  * files, emits `openssl.cnf:associated_files` metadata values that encode both
  * the container GitOID and the referenced-file GitOID.
  */
case class OpenSSLConfigState(
    configDataByPath: Map[String, OpenSSLConfigData] = Map.empty,
    referencesByPath: Map[String, Vector[String]] = Map.empty,
    gitoidByPath: Map[String, GitOID] = Map.empty
) extends ProcessingState[SingleMarker, OpenSSLConfigState] {

  private val logger = Logger(getClass())

  private val adHoc = MKC.adHoc("openssl.cnf")

  def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): OpenSSLConfigState = this

  def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, OpenSSLConfigState) = PurlSet.empty -> this

  def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], OpenSSLConfigState) = {
    val data = configDataByPath.getOrElse(artifact.path(), OpenSSLConfigData())
    val meta = buildMetadata(data)
    meta -> this
  }

  def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, OpenSSLConfigState) = {
    val path = artifact.path()
    val currentGitOID = item.identifier
    val updatedGitoids = gitoidByPath + (path -> currentGitOID)
    val updatedState = this.copy(gitoidByPath = updatedGitoids)

    val referencedPaths = referencesByPath.getOrElse(path, Vector.empty)
    if (referencedPaths.isEmpty) {
      item -> updatedState
    } else {
      val containerGitOID = item.connections
        .find(_._1 == EdgeType.containedBy)
        .map(_._2)
        .getOrElse("")

      val associated = referencedPaths.flatMap { refPath =>
        updatedGitoids.get(refPath) match {
          case Some(refGitOID) =>
            Some(StringOrPair(s"$containerGitOID:$refGitOID"))
          case None =>
            logger.debug(
              s"OpenSSL config reference not yet resolved: $path -> $refPath"
            )
            None
        }
      }

      if (associated.isEmpty) {
        item -> updatedState
      } else {
        val extra = TreeMap(
          adHoc("associated_files") -> TreeSet.from(associated)
        )
        item.enhanceWithMetadata(extra = extra) -> updatedState
      }
    }
  }

  def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): OpenSSLConfigState = this

  def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): OpenSSLConfigState = this

  override def maybePackageTag(marker: SingleMarker): Option[PackageTagInfo] =
    None

  private def buildMetadata(
      data: OpenSSLConfigData
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    var meta = TreeMap.empty[String, TreeSet[StringOrPair]]

    if (data.sections.nonEmpty) {
      meta = meta + (adHoc("sections") -> TreeSet.from(
        data.sections.map(StringOrPair(_))
      ))
    }

    data.cipherString.foreach { value =>
      meta = meta + (adHoc("cipher_string") -> TreeSet(StringOrPair(value)))
    }

    data.cipherSuites.foreach { value =>
      meta = meta + (adHoc("cipher_suites") -> TreeSet(StringOrPair(value)))
    }

    data.minProtocol.foreach { value =>
      meta = meta + (adHoc("min_protocol") -> TreeSet(StringOrPair(value)))
    }

    data.maxProtocol.foreach { value =>
      meta = meta + (adHoc("max_protocol") -> TreeSet(StringOrPair(value)))
    }

    if (data.options.nonEmpty) {
      meta = meta + (adHoc("options") -> TreeSet.from(
        data.options.map(StringOrPair(_))
      ))
    }

    meta
  }
}

/** Factory and compute functions for the OpenSSL config strategy. */
object OpenSSLConfigToProcess {

  private val logger = Logger(getClass())

  /** MIME type that identifies OpenSSL configuration files. */
  val OpenSSLConfigMimeType: String =
    OpenSSLConfigDetector.OpenSSLConfigMimeType

  /** Compute OpenSSL config files to process at a layer.
    *
    * Selection is MIME-only and content-agnostic. The actual parsing,
    * `.include` resolution, and dependency ordering happen inside the strategy
    * during processing.
    */
  def computeOpenSSLConfigFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val claimed = byName.values.flatten
      .filter(_.mimeType.contains(OpenSSLConfigMimeType))
      .toVector

    if (claimed.isEmpty) {
      return (Vector.empty, byUUID, byName, "OpenSSLConfig")
    }

    val uuids = claimed.map(_.uuid).toSet

    val revisedByUUID = byUUID.filterNot { case (uuid, _) =>
      uuids.contains(uuid)
    }
    val revisedByName = byName.filterNot { case (_, artifacts) =>
      artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      Vector(new OpenSSLConfigToProcess(claimed)),
      revisedByUUID,
      revisedByName,
      "OpenSSLConfig"
    )
  }

  /** Resolve a bundle of claimed files into an ordered sequence and an initial
    * processing state.
    *
    * This reads file contents, parses configs, resolves `.include` references
    * within the claimed set, and topologically sorts the result. It is called
    * from `getElementsToProcess`, i.e., during strategy processing.
    */
  private def resolveBundle(
      files: Vector[ArtifactWrapper]
  ): (Vector[ArtifactWrapper], OpenSSLConfigState) = {
    // Parse every claimed file. Failures are tolerated: the file is still
    // claimed but its parsed data will be empty.
    val parsed: Vector[(ArtifactWrapper, OpenSSLConfigData)] = files.map {
      artifact =>
        val data = OpenSSLConfigParser.parse(artifact) match {
          case Success(d) => d
          case _ =>
            logger.debug(
              s"OpenSSL config parse failed for ${artifact.path()}; claiming without parsed data"
            )
            OpenSSLConfigData()
        }
        artifact -> data
    }

    val pathToArtifact = parsed.map(_._1.path()).zip(parsed.map(_._1)).toMap
    val knownPaths = pathToArtifact.keySet

    // Resolve .include references to paths within the claimed set.
    val referencesByPath: Map[String, Vector[String]] = parsed.map {
      case (artifact, data) =>
        val refs = data.includeReferences.flatMap { ref =>
          resolveReferencePath(ref, knownPaths)
        }.distinct
        artifact.path() -> refs
    }.toMap

    // Build dependency graph: if A references B, edge B -> A (B before A).
    val dependencies: Map[String, Set[String]] = referencesByPath.map {
      case (path, refs) =>
        path -> refs.filter(pathToArtifact.contains).toSet
    }

    val orderedFiles = topoSort(files, dependencies)
    val configDataByPath = parsed.map { case (a, d) => a.path() -> d }.toMap

    orderedFiles -> OpenSSLConfigState(configDataByPath, referencesByPath)
  }

  /** Resolve an include path to a path present in the claimed set.
    *
    * First tries an exact match, then a basename match. If multiple files share
    * the same basename, the alphabetically first path is chosen. References
    * that cannot be resolved are omitted.
    */
  private def resolveReferencePath(
      ref: String,
      knownPaths: Set[String]
  ): Option[String] = {
    val exact = knownPaths.find(_ == ref)
    exact.orElse {
      val refFileName = Option(Paths.get(ref).getFileName)
        .map(_.toString)
        .getOrElse(ref)
      val candidates = knownPaths
        .filter { path =>
          Option(Paths.get(path).getFileName)
            .map(_.toString)
            .contains(refFileName)
        }
        .toVector
        .sorted
      candidates.headOption
    }
  }

  /** Topologically sort files so dependencies are processed first.
    *
    * Cycles are broken by skipping the edge that would revisit a node already
    * on the DFS stack. The starting order is alphabetical to make the result
    * deterministic.
    */
  private def topoSort(
      files: Vector[ArtifactWrapper],
      dependencies: Map[String, Set[String]]
  ): Vector[ArtifactWrapper] = {
    val pathToArtifact = files.map(a => a.path() -> a).toMap
    val visited = scala.collection.mutable.Set[String]()
    val result = scala.collection.mutable.ListBuffer[String]()

    def visit(path: String, stack: Set[String]): Unit = {
      if (stack.contains(path)) {
        return
      }
      if (visited.contains(path)) {
        return
      }
      val deps =
        dependencies.getOrElse(path, Set.empty).filter(pathToArtifact.contains)
      deps.toSeq.sorted.foreach(visit(_, stack + path))
      visited += path
      result += path
    }

    files.map(_.path()).sorted.foreach(visit(_, Set.empty))
    result.toVector.map(pathToArtifact)
  }
}
