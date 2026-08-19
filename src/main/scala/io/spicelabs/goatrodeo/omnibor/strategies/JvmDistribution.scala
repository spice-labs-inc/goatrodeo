/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors. Apache 2.0. */

package io.spicelabs.goatrodeo.omnibor.strategies

import com.typesafe.scalalogging.Logger
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
import io.spicelabs.goatrodeo.util.CryptoContentDetector
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.PURLComponentSanitizer
import io.spicelabs.goatrodeo.util.PURLHelpers
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Parsed contents of a JDK/JRE `release` file.
  */
case class JvmReleaseData(
    javaRuntimeVersion: Option[String],
    javaVersion: Option[String],
    implementor: Option[String],
    imageType: Option[String],
    osArch: Option[String],
    osName: Option[String],
    libc: Option[String],
    semanticVersion: Option[String],
    fullVersion: Option[String],
    jvmVariant: Option[String],
    sourceRepo: Option[String],
    buildSourceRepo: Option[String],
    javaVersionDate: Option[String]
)

object JvmDistribution {

  private val logger = Logger(this.getClass())

  /** Parse a `release` file KEY="VALUE" format into structured data. Values are
    * unquoted. Unknown keys are ignored silently.
    */
  def parseReleaseFile(content: String): JvmReleaseData = {
    val lines = content.linesIterator
      .map(_.trim)
      .filterNot(_.isEmpty)
      .filterNot(_.startsWith("#"))
      .toVector

    val pairs: Map[String, String] = lines.flatMap { line =>
      val eqIdx = line.indexOf('=')
      if (eqIdx > 0) {
        val key = line.substring(0, eqIdx).trim
        var value = line.substring(eqIdx + 1).trim
        // Strip surrounding quotes if present
        if (value.length >= 2 && value.head == '"' && value.last == '"') {
          value = value.substring(1, value.length - 1)
        }
        Some(key -> value)
      } else None
    }.toMap

    def opt(key: String): Option[String] = pairs.get(key).filter(_.nonEmpty)

    JvmReleaseData(
      javaRuntimeVersion = opt("JAVA_RUNTIME_VERSION"),
      javaVersion = opt("JAVA_VERSION"),
      implementor = opt("IMPLEMENTOR"),
      imageType = opt("IMAGE_TYPE"),
      osArch = opt("OS_ARCH"),
      osName = opt("OS_NAME"),
      libc = opt("LIBC"),
      semanticVersion = opt("SEMANTIC_VERSION"),
      fullVersion = opt("FULL_VERSION"),
      jvmVariant = opt("JVM_VARIANT"),
      sourceRepo = opt("SOURCE_REPO"),
      buildSourceRepo = opt("BUILD_SOURCE_REPO"),
      javaVersionDate = opt("JAVA_VERSION_DATE")
    )
  }

  /** Map IMPLEMENTOR and path to a known vendor name. Falls back to "OpenJDK"
    * for unknown vendors.
    */
  def detectVendor(
      implementor: Option[String],
      path: String
  ): (String, String) = {
    val implLower = implementor.getOrElse("").toLowerCase
    val pathLower = path.toLowerCase

    if (implLower.contains("azul") || pathLower.contains("zulu")) {
      ("azul", "zulu")
    } else if (
      implLower
        .contains("eclipse") || implLower.contains("adoptium") || implLower
        .contains("temurin")
    ) {
      ("eclipse", "temurin")
    } else if (implLower.contains("amazon") || implLower.contains("corretto")) {
      ("amazon", "corretto")
    } else if (implLower.contains("oracle")) {
      ("oracle", "jdk")
    } else if (implLower.contains("ibm")) {
      ("ibm", "jdk")
    } else if (implLower.contains("microsoft")) {
      ("microsoft", "jdk")
    } else {
      ("openjdk", "jdk")
    }
  }

  /** Determine the effective version string. Prefers JAVA_RUNTIME_VERSION,
    * falls back to JAVA_VERSION, then semanticVersion, then fullVersion.
    */
  def effectiveVersion(data: JvmReleaseData): Option[String] = {
    data.javaRuntimeVersion
      .orElse(data.javaVersion)
      .orElse(data.semanticVersion)
      .orElse(data.fullVersion)
  }

  /** Determine whether this is a JDK (true) or JRE (false). Uses IMAGE_TYPE if
    * present; otherwise checks for `bin/javac` sibling.
    */
  def isJDK(
      data: JvmReleaseData,
      artifact: ArtifactWrapper
  ): Boolean = {
    data.imageType match {
      case Some(it) => it.equalsIgnoreCase("JDK")
      case None     => hasSiblingJavac(artifact)
    }
  }

  /** True if the artifact's parent directory contains `bin/javac`. */
  private def hasSiblingJavac(artifact: ArtifactWrapper): Boolean = {
    val file = artifact match {
      case fw: FileWrapper => fw.wrappedFile
      case _               => new java.io.File(artifact.path())
    }
    val parent = file.getParentFile
    if (parent == null) false
    else new java.io.File(parent, "bin/javac").exists()
  }

  /** Compute files to process for JVM distributions. Claims files named
    * `release` selected by MIME (detected during the MIME augmentation pass);
    * the release file is parsed lazily during processing, not here.
    */
  def computeJvmFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {

    val candidates = byUUID.values.filter { wrapper =>
      wrapper.filenameWithNoPath == "release" &&
      wrapper.mimeType.contains(CryptoContentDetector.JvmReleaseMime)
    }.toVector

    val uuids: Set[String] = candidates.map(_.uuid).toSet

    val revisedByUUID = byUUID.filter { case (name, _) =>
      !uuids.contains(name)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      candidates.map(wrapper => new JvmDistribution(wrapper)).toVector,
      revisedByUUID,
      revisedByName,
      "JvmDistribution"
    )
  }
}

class JvmDistribution(val artifact: ArtifactWrapper) extends ToProcess {

  private lazy val releaseData: JvmReleaseData = {
    Try(artifact.withStream(Helpers.slurpInputToString(_))).toOption
      .map(JvmDistribution.parseReleaseFile)
      .getOrElse(JvmDistribution.parseReleaseFile(""))
  }

  def markSuccessfulCompletion(): Unit = {
    artifact.finished()
  }

  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = JvmState

  def getElementsToProcess(): (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> JvmState(artifact, releaseData)
}

/** State maintained during JVM distribution processing. */
class JvmState(artifact: ArtifactWrapper, releaseData: JvmReleaseData)
    extends ProcessingState[SingleMarker, JvmState] {

  private lazy val (vendorNamespace, productName) =
    JvmDistribution.detectVendor(
      releaseData.implementor,
      ""
    ) // path not needed for vendor detection in state

  private lazy val versionOpt = JvmDistribution.effectiveVersion(releaseData)

  private lazy val isJDK_? = JvmDistribution.isJDK(releaseData, artifact)

  def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): JvmState = this

  def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): JvmState = this

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

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, JvmState) = {
    // Return the Purl object directly (not a string). PurlSet.canonicalStrings
    // will handle toCanonical() at the storage boundary, wrapped in Try.
    val purlOpt = for {
      ver <- versionOpt
      cleanVendor <-
        PURLComponentSanitizer.sanitizeGenericIdentifier(vendorNamespace)
      cleanProduct <-
        PURLComponentSanitizer.sanitizeGenericIdentifier(productName)
      cleanVersion <- PURLComponentSanitizer.sanitizeGenericVersion(ver)
    } yield {
      val qualifier = releaseData.sourceRepo.toSeq.map("repository_url" -> _)
      scala.util.Try {
        PURLHelpers
          .purl(
            `type` = "generic",
            name = cleanProduct,
            namespace = Some(cleanVendor),
            version = Some(cleanVersion),
            qualifiers = qualifier
          )
      }.toOption
    }
    purlOpt.flatten
      .map(p => PurlSet.single(p))
      .getOrElse(PurlSet.empty) -> this
  }

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], JvmState) = {
    val adHoc = MKC.adHoc("jvm")

    val tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]()
        +? maybeStringOrPair(MKC.NAME, productName)
        +? maybeStringOrPair(MKC.VERSION, versionOpt)
        +? maybeStringOrPair(MKC.PUBLISHER, releaseData.implementor)
        +? maybeStringOrPair(adHoc("Vendor"), vendorNamespace)
        +? maybeStringOrPair(adHoc("JavaVersion"), releaseData.javaVersion)
        +? maybeStringOrPair(
          adHoc("JavaRuntimeVersion"),
          releaseData.javaRuntimeVersion
        )
        +? maybeStringOrPair(adHoc("ImageType"), releaseData.imageType)
        +? maybeStringOrPair(adHoc("OsArch"), releaseData.osArch)
        +? maybeStringOrPair(adHoc("OsName"), releaseData.osName)
        +? maybeStringOrPair(adHoc("Libc"), releaseData.libc)
        +? maybeStringOrPair(
          adHoc("JvmVariant"),
          releaseData.jvmVariant
        )
        +? maybeStringOrPair(
          adHoc("SemanticVersion"),
          releaseData.semanticVersion
        )
        +? maybeStringOrPair(
          adHoc("FullVersion"),
          releaseData.fullVersion
        )
        +? maybeStringOrPair(
          adHoc("SourceRepo"),
          releaseData.sourceRepo
        )
        +? maybeStringOrPair(
          adHoc("BuildSourceRepo"),
          releaseData.buildSourceRepo
        )
        +? maybeStringOrPair(
          adHoc("JavaVersionDate"),
          releaseData.javaVersionDate
        )
        +? maybeStringOrPair(
          adHoc("IsJDK"),
          if (isJDK_?) "true" else "false"
        )

    tm -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, JvmState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): JvmState = this

  /** Generate per-package tag info for JVM distributions. */
  override def maybePackageTag(
      marker: SingleMarker
  ): Option[PackageTagInfo] = {
    val date: Option[java.util.Date] =
      releaseData.javaVersionDate.flatMap(JavaDateParser.parse)

    Some(
      PackageTagInfo(
        name = s"$vendorNamespace/$productName",
        version = versionOpt,
        date = date
      )
    )
  }
}

/** Lightweight ISO-8601-ish date parser for JAVA_VERSION_DATE strings. Returns
  * None on unparseable input.
  */
private object JavaDateParser {
  def parse(str: String): Option[java.util.Date] = {
    val formats = Seq(
      "yyyy-MM-dd",
      "yyyy/MM/dd",
      "dd-MM-yyyy",
      "dd/MM/yyyy"
    )
    formats.view.flatMap { fmt =>
      scala.util.Try {
        new java.text.SimpleDateFormat(fmt).parse(str)
      }.toOption
    }.headOption
  }
}
