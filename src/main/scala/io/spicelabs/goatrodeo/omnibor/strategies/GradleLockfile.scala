/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import com.typesafe.scalalogging.Logger
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
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.PURLHelpers
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.compact
import org.json4s.native.JsonMethods.render

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** A dependency entry extracted from a Gradle lockfile.
  *
  * @param groupId
  *   Maven group ID
  * @param artifactId
  *   Maven artifact ID
  * @param version
  *   Resolved version string
  * @param configurations
  *   Gradle configuration names (e.g. compileClasspath, runtimeClasspath)
  */
case class GradleDependency(
    groupId: String,
    artifactId: String,
    version: String,
    configurations: Vector[String]
)

object GradleLockfile {

  private val logger = Logger(this.getClass())

  /** Parse a Gradle lockfile (modern or legacy format) into dependency records.
    *
    * Modern format (Gradle 7.0+): `group:artifact:version=config1,config2,...`
    *
    * Legacy format (Gradle 5.x-6.x): `group:artifact:version` (one file per
    * configuration)
    *
    * @param content
    *   raw lockfile text
    * @param configFromFilename
    *   for legacy format, the configuration name derived from the filename
    * @return
    *   vector of parsed dependencies
    */
  def parseLockfile(
      content: String,
      configFromFilename: Option[String]
  ): Vector[GradleDependency] = {
    val lines = content.linesIterator
      .map(_.trim)
      .filterNot(_.isEmpty)
      .toVector

    lines.flatMap { line =>
      if (line.startsWith("#")) {
        None
      } else if (line.startsWith("empty=")) {
        None
      } else {
        // Split at the first '=' to separate dependency coordinates from configs
        val eqIdx = line.indexOf('=')
        val (depPart, cfgPart) = if (eqIdx >= 0) {
          (line.substring(0, eqIdx), Some(line.substring(eqIdx + 1)))
        } else {
          (line, None)
        }

        val coords = depPart.split(':')
        if (coords.length != 3) {
          logger.debug(s"Skipping malformed Gradle lockfile line: $line")
          None
        } else {
          val group = coords(0)
          val artifact = coords(1)
          val version = coords(2)
          val configs = cfgPart match {
            case Some(cfg) => cfg.split(",").toVector.filter(_.nonEmpty)
            case None      => configFromFilename.toVector
          }
          Some(GradleDependency(group, artifact, version, configs))
        }
      }
    }
  }

  /** Determine the Gradle configuration name from a legacy lockfile filename.
    * e.g. `compileClasspath.lockfile` → `compileClasspath`
    */
  private def configFromFilename(name: String): Option[String] = {
    if (name.endsWith(".lockfile")) {
      Some(name.stripSuffix(".lockfile"))
    } else {
      None
    }
  }

  /** Compute files to process for Gradle lockfile strategy. Claims files named
    * `gradle.lockfile`, `buildscript-gradle.lockfile`, and legacy files in
    * `dependency-locks` ending with `.lockfile`.
    */
  def computeGradleLockfiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {

    val candidates = for {
      (_, wrapper) <- byUUID
      name = wrapper.filenameWithNoPath
      path = wrapper.path()
      if name == "gradle.lockfile" ||
        name == "buildscript-gradle.lockfile" ||
        (name.endsWith(".lockfile") && path.contains("dependency-locks"))
      content <- scala.util
        .Try(
          wrapper.withStream(Helpers.slurpInputToString(_))
        )
        .toOption
      cfg =
        if (name == "gradle.lockfile" || name == "buildscript-gradle.lockfile")
          None
        else configFromFilename(name)
      deps = parseLockfile(content, cfg)
    } yield (wrapper, deps)

    val uuids: Set[String] = candidates.map(_._1.uuid).toSet

    val revisedByUUID = byUUID.filter { case (name, _) =>
      !uuids.contains(name)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      candidates.map { case (wrapper, deps) =>
        GradleLockfile(wrapper, deps)
      }.toVector,
      revisedByUUID,
      revisedByName,
      "Gradle"
    )
  }
}

/** A Gradle lockfile to process.
  *
  * @param artifact
  *   the lockfile artifact wrapper
  * @param dependencies
  *   parsed dependencies from the lockfile
  */
class GradleLockfile(
    val artifact: ArtifactWrapper,
    val dependencies: Vector[GradleDependency]
) extends ToProcess {

  def markSuccessfulCompletion(): Unit = {
    artifact.finished()
  }

  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = GradleLockfileState

  def getElementsToProcess(): (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> GradleLockfileState(dependencies)
}

/** State maintained during Gradle lockfile processing.
  */
class GradleLockfileState(deps: Vector[GradleDependency])
    extends ProcessingState[SingleMarker, GradleLockfileState] {

  private def maybeStringOrPair(
      key: String,
      s: String | Option[String] | (String, String)
  ): Option[(String, TreeSet[StringOrPair])] = {
    s match {
      case value: String =>
        Some(key -> TreeSet(StringOrPair(value)))
      case value: Option[String] =>
        value match {
          case Some(str) => Some(key -> TreeSet(StringOrPair(str)))
          case None      => None
        }
      case value: (String, String) =>
        Some(key -> TreeSet(StringOrPair(value._1, value._2)))
    }
  }

  def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): GradleLockfileState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (Vector[String], GradleLockfileState) = {
    val purls = deps.map { d =>
      PURLHelpers
        .purl(
          `type` = "maven",
          name = d.artifactId,
          namespace = d.groupId,
          version = d.version
        )
        .toCanonical()
        .nn
    }
    purls.toVector -> this
  }

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], GradleLockfileState) = {
    val adHoc = MKC.adHoc("gradle")

    val depJson: Option[String] = if (deps.nonEmpty) {
      val json = deps.map { d =>
        ("group" -> d.groupId) ~
          ("artifact" -> d.artifactId) ~
          ("version" -> d.version) ~
          ("scope" -> d.configurations.mkString(",")) ~
          ("optional" -> false) ~
          ("classifier" -> Option.empty[String]) ~
          ("type" -> Option.empty[String])
      }
      Some(compact(render(json)))
    } else None

    val tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]()
        +? maybeStringOrPair(
          MKC.NAME,
          artifact.filenameWithNoPath
        )
        +? maybeStringOrPair(
          adHoc("DependencyCount"),
          deps.size.toString
        )
        +? maybeStringOrPair(MKC.DEPENDENCIES, depJson)

    tm -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, GradleLockfileState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): GradleLockfileState = this

  /** Gradle lockfiles do not represent a single package, so no per-package tag
    * is generated.
    */
  override def maybePackageTag(
      marker: SingleMarker
  ): Option[PackageTagInfo] = None
}
