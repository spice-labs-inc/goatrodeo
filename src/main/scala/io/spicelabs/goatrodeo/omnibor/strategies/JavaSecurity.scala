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
import io.spicelabs.goatrodeo.util.JavaSecurityData
import io.spicelabs.goatrodeo.util.JavaSecurityDetector
import io.spicelabs.goatrodeo.util.JavaSecurityParser

import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** A bundle of Java security properties files discovered at the same archive
  * layer.
  *
  * Files are ordered by intra-layer dependency (`include` references) so that
  * referenced files are processed before the files that reference them. Parsing
  * and `include` resolution happen during strategy processing, not during the
  * MIME-based selection phase.
  *
  * @param files
  *   security files claimed by MIME type or path (not yet ordered)
  */
class JavaSecurityToProcess(val files: Vector[ArtifactWrapper])
    extends ToProcess {

  private lazy val resolved: (Vector[ArtifactWrapper], JavaSecurityState) =
    JavaSecurityToProcess.resolveBundle(files)

  def markSuccessfulCompletion(): Unit = {
    files.foreach(_.finished())
  }

  override def itemCnt: Int = files.size
  override def main: String = files.headOption.map(_.path()).getOrElse("")
  def mimeType: Set[String] =
    files.headOption.map(_.mimeType).getOrElse(Set.empty)

  type MarkerType = SingleMarker
  type StateType = JavaSecurityState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) = {
    val (ordered, state) = resolved
    ordered.map(_ -> SingleMarker()) -> state
  }
}

/** State maintained while processing a `JavaSecurityToProcess` bundle.
  *
  * The state is immutable. Each `finalAugmentation` call records the GitOID of
  * the just-processed file and, for files that reference already-processed
  * files, emits `java.security:associated_files` metadata values that encode
  * both the container GitOID and the referenced-file GitOID.
  */
case class JavaSecurityState(
    securityDataByPath: Map[String, JavaSecurityData] = Map.empty,
    referencesByPath: Map[String, Vector[String]] = Map.empty,
    gitoidByPath: Map[String, GitOID] = Map.empty
) extends ProcessingState[SingleMarker, JavaSecurityState] {

  private val logger = Logger(getClass())

  private val adHoc = MKC.adHoc("java.security")

  def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): JavaSecurityState = this

  def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, JavaSecurityState) = PurlSet.empty -> this

  def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], JavaSecurityState) = {
    val data = securityDataByPath.getOrElse(artifact.path(), JavaSecurityData())
    val meta = buildMetadata(data)
    meta -> this
  }

  def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, JavaSecurityState) = {
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
              s"Java security reference not yet resolved: $path -> $refPath"
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
  ): JavaSecurityState = this

  def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): JavaSecurityState = this

  override def maybePackageTag(marker: SingleMarker): Option[PackageTagInfo] =
    None

  private def buildMetadata(
      data: JavaSecurityData
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    var meta = TreeMap.empty[String, TreeSet[StringOrPair]]

    if (data.disabledAlgorithms.nonEmpty) {
      meta = meta + (adHoc("disabled_algorithms") -> TreeSet.from(
        data.disabledAlgorithms.map(StringOrPair(_))
      ))
    }

    if (data.certpathDisabledAlgorithms.nonEmpty) {
      meta = meta + (adHoc("certpath_disabled_algorithms") -> TreeSet.from(
        data.certpathDisabledAlgorithms.map(StringOrPair(_))
      ))
    }

    if (data.legacyAlgorithms.nonEmpty) {
      meta = meta + (adHoc("legacy_algorithms") -> TreeSet.from(
        data.legacyAlgorithms.map(StringOrPair(_))
      ))
    }

    if (data.namedGroups.nonEmpty) {
      meta = meta + (adHoc("named_groups") -> TreeSet.from(
        data.namedGroups.map(StringOrPair(_))
      ))
    }

    data.ephemeralDHKeySize.foreach { value =>
      meta =
        meta + (adHoc("ephemeral_dh_key_size") -> TreeSet(StringOrPair(value)))
    }

    meta
  }
}

/** Factory and compute functions for the Java security strategy. */
object JavaSecurityToProcess {

  private val logger = Logger(getClass())

  /** MIME type that identifies Java security properties files. */
  val JavaSecurityMimeType: String = JavaSecurityDetector.JavaSecurityMimeType

  /** Path fragments that identify a JDK/JRE security directory layout. */
  private val SecurityPathPatterns: Set[String] = Set(
    "/conf/security/java.security",
    "/lib/security/java.security",
    "/jre/lib/security/java.security"
  )

  /** Compute Java security files to process at a layer.
    *
    * Selection is content-agnostic. Files are claimed by MIME type or by being
    * named `java.security` inside a known security directory layout. Parsing,
    * `include` resolution, and dependency ordering happen inside the strategy.
    */
  def computeJavaSecurityFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mimeClaimed = byName.values.flatten
      .filter(_.mimeType.contains(JavaSecurityMimeType))
      .toVector

    val pathClaimed = byName.values.flatten.filter { artifact =>
      val path = artifact.path()
      path.endsWith("java.security") &&
      SecurityPathPatterns.exists(path.endsWith)
    }.toVector

    val claimed = (mimeClaimed ++ pathClaimed).distinctBy(_.uuid)

    if (claimed.isEmpty) {
      return (Vector.empty, byUUID, byName, "JavaSecurity")
    }

    val uuids = claimed.map(_.uuid).toSet

    val revisedByUUID = byUUID.filterNot { case (uuid, _) =>
      uuids.contains(uuid)
    }
    val revisedByName = byName.filterNot { case (_, artifacts) =>
      artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      Vector(new JavaSecurityToProcess(claimed)),
      revisedByUUID,
      revisedByName,
      "JavaSecurity"
    )
  }

  /** Resolve a bundle of claimed files into an ordered sequence and an initial
    * processing state.
    *
    * This reads file contents, parses properties, resolves `include` references
    * within the claimed set, merges effective security data across the
    * dependency graph, and topologically sorts the result. It is called from
    * `getElementsToProcess`, i.e., during strategy processing.
    */
  private def resolveBundle(
      files: Vector[ArtifactWrapper]
  ): (Vector[ArtifactWrapper], JavaSecurityState) = {
    val rawContent: Map[String, String] = files.flatMap { artifact =>
      readBoundedText(artifact) match {
        case Success(content) => Some(artifact.path() -> content)
        case Failure(_) =>
          logger.debug(s"Java security read failed for ${artifact.path()}")
          None
      }
    }.toMap

    val selectedPaths = rawContent.keySet

    // Parse each claimed file individually. Failures are tolerated: the file is
    // still claimed but its parsed data will be empty.
    val parsedByPath: Map[String, JavaSecurityData] = rawContent.flatMap {
      case (path, content) =>
        JavaSecurityParser.parseString(content) match {
          case Success(data) => Some(path -> data)
          case Failure(_) =>
            logger.debug(s"Java security parse failed for $path")
            None
        }
    }

    // Build reference graph from raw include directives.
    val referencesByPath: Map[String, Vector[String]] = rawContent.map {
      case (path, content) =>
        val refs = extractIncludePaths(content).flatMap { refPath =>
          resolveReferencePath(refPath, selectedPaths)
        }.distinct
        path -> refs
    }

    // Build dependency graph: if A references B, edge B -> A (B before A).
    val dependencies: Map[String, Set[String]] = referencesByPath.map {
      case (path, refs) =>
        path -> refs.filter(selectedPaths.contains).toSet
    }

    // Compute effective data for each file by merging its own parsed data with
    // the data of its dependencies (dependencies first, then the file itself).
    // List-valued properties are unioned; scalar values are overridden by the
    // dependent file.
    val orderedFiles = topoSort(files, dependencies)
    val effectiveData: Map[String, JavaSecurityData] = selectedPaths.map {
      path =>
        val deps = dependencies.getOrElse(path, Set.empty)
        val orderedSources =
          (deps.toSeq.sorted :+ path).flatMap(parsedByPath.get)
        val merged = orderedSources.foldLeft(JavaSecurityData())(mergeData)
        path -> merged
    }.toMap

    orderedFiles -> JavaSecurityState(effectiveData, referencesByPath)
  }

  /** Merge two `JavaSecurityData` values, treating the second as an overlay.
    *
    * List-valued properties are unioned; scalar values are taken from the
    * overlay if present.
    */
  private def mergeData(
      base: JavaSecurityData,
      overlay: JavaSecurityData
  ): JavaSecurityData = {
    JavaSecurityData(
      disabledAlgorithms =
        base.disabledAlgorithms ++ overlay.disabledAlgorithms,
      certpathDisabledAlgorithms =
        base.certpathDisabledAlgorithms ++ overlay.certpathDisabledAlgorithms,
      legacyAlgorithms = base.legacyAlgorithms ++ overlay.legacyAlgorithms,
      namedGroups = base.namedGroups ++ overlay.namedGroups,
      ephemeralDHKeySize =
        overlay.ephemeralDHKeySize.orElse(base.ephemeralDHKeySize)
    )
  }

  /** Extract include target paths from raw content.
    *
    * Java `include` directives have the form `include <url>` or `include url`.
    * The path is stripped of surrounding quotes, angle brackets, and the
    * `file:` prefix.
    */
  private def extractIncludePaths(content: String): Vector[String] = {
    content.linesIterator.toVector.flatMap { line =>
      val trimmed = line.trim
      if (trimmed.startsWith("include")) {
        val rest = trimmed.substring("include".length).trim
        val stripped = rest
          .stripPrefix("<")
          .stripSuffix(">")
          .stripPrefix("\"")
          .stripSuffix("\"")
          .stripPrefix("'")
          .stripSuffix("'")
          .trim
        if (stripped.nonEmpty) {
          val withoutFilePrefix = if (stripped.startsWith("file:")) {
            stripped.substring("file:".length)
          } else {
            stripped
          }
          Some(withoutFilePrefix)
        } else {
          None
        }
      } else {
        None
      }
    }
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

  /** Read up to the Java security parse budget from an artifact as ISO-8859-1.
    */
  private def readBoundedText(artifact: ArtifactWrapper): Try[String] = Try {
    artifact.withStream { stream =>
      val bytes = stream.readNBytes(JavaSecurityParser.MaxReadBytes)
      new String(bytes, StandardCharsets.ISO_8859_1)
    }
  }

  private val MaxIncludeDepth: Int = 8
}
