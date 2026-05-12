package io.spicelabs.goatrodeo.omnibor.strategies

import com.github.packageurl.PackageURL
import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.Augmentation
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.PackageTagInfo
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingMarker
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.FileWalker
import io.spicelabs.goatrodeo.util.Gitoid
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.PURLHelpers
import io.spicelabs.goatrodeo.util.PURLHelpers.Ecosystems

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try
import scala.xml.NodeSeq
import scala.xml.XML

/** Markers for different Maven/JVM artifact types.
  *
  * Each marker identifies the type of artifact being processed within a Maven
  * package bundle (JAR + POM + sources + javadocs).
  */
enum MavenMarkers extends ProcessingMarker {

  /** A POM (Project Object Model) XML file. */
  case POM

  /** A JAR (Java Archive) file containing compiled classes. */
  case JAR

  /** A sources JAR containing Java source files. */
  case Sources

  /** A JavaDoc JAR containing API documentation. */
  case JavaDocs
}

/** State maintained during Maven artifact processing.
  *
  * Tracks POM file content and source file mappings to enable:
  *   - Package URL generation from POM metadata
  *   - Source-to-class file mapping for "built from" relationships
  *
  * @param pomFile
  *   the raw POM file content as a string
  * @param pomXml
  *   the parsed POM XML
  * @param sources
  *   map of source filenames to their Items
  * @param sourceGitoids
  *   map of source filenames to their GitOIDs
  */
case class MavenState(
    pomFile: String = "",
    pomXml: NodeSeq = NodeSeq.Empty,
    sources: Map[String, Item] = Map(),
    sourceGitoids: Map[String, Gitoid] = Map(),
    groupId: Option[String] = None,
    artifactId: Option[String] = None,
    version: Option[String] = None,
    buildDate: Option[java.util.Date] = None
) extends ProcessingState[MavenMarkers, MavenState] {
  private lazy val logger = Logger(getClass())

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
      marker: MavenMarkers
  ): MavenState = marker match {
    case MavenMarkers.POM =>
      val pomString = artifact.withStream(Helpers.slurpInputToString(_))
      val xml =
        Try { XML.loadString(pomString) }.toOption.getOrElse(NodeSeq.Empty)

      // Extract GAV coordinates
      val grp = findTag(xml, "groupId")
      val art = findTag(xml, "artifactId")
      val ver = tryToFixVersion(
        findTag(xml, "version"),
        artifact.filenameWithNoPath
      )

      // Extract build date from POM properties
      val buildDateFromPom = extractBuildDateFromPom(xml)

      this.copy(
        pomFile = pomString,
        pomXml = xml,
        groupId = grp,
        artifactId = art,
        version = ver,
        buildDate = buildDateFromPom
      )

    case _ => this
  }

  /** Extract build date from POM properties section. Looks for buildDate or
    * maven.build.timestamp properties.
    */
  private def extractBuildDateFromPom(xml: NodeSeq): Option[java.util.Date] = {
    val buildDateProp =
      (xml \ "properties" \ "buildDate").headOption.map(_.text)
    val timestampProp =
      (xml \ "properties" \ "maven.build.timestamp").headOption.map(_.text)

    // Try buildDate first, then maven.build.timestamp
    val dateStr = buildDateProp.orElse(timestampProp)

    dateStr.flatMap { str =>
      Try {
        // Try ISO 8601 format first
        val isoFormat =
          new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        isoFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
        isoFormat.parse(str)
      }.orElse(Try {
        // Try simple date format
        val simpleFormat = new java.text.SimpleDateFormat("yyyy-MM-dd")
        simpleFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
        simpleFormat.parse(str)
      }).toOption
    }
  }
  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: MavenMarkers
  ): (Vector[PackageURL], MavenState) = {
    val grp = findTag(pomXml, "groupId")
    val art = findTag(pomXml, "artifactId")
    val ver = tryToFixVersion(
      findTag(pomXml, "version"),
      artifact.filenameWithNoPath
    )

    (grp, art, ver) match {
      case (Some(g), Some(a), Some(v)) =>
        val ret = Vector(
          PURLHelpers.buildPackageURL(
            Ecosystems.Maven,
            Some(g),
            a,
            v,
            marker match {
              case MavenMarkers.JAR      => None
              case MavenMarkers.Sources  => Some("sources")
              case MavenMarkers.POM      => Some("pom")
              case MavenMarkers.JavaDocs => Some("javadoc")
            }
          )
        )
        ret -> this
      case _ => (Vector(), this)

    }
  }

  private def findTag(in: NodeSeq, name: String): Option[String] = {
    val topper = in \ name
    if (topper.length == 1) {
      topper.text match {
        case s if s.length() > 0 => Some(s)
        case _                   => None
      }

    } else {
      val t2 = in \ "parent" \ name
      if (t2.length == 1 && t2.text.length() > 0) { Some(t2.text) }
      else None
    }
  }

  private def tryToFixVersion(
      in: Option[String],
      fileName: String
  ): Option[String] = {
    in match {
      case Some(s) if s.startsWith("${") =>
        val fn2 =
          fileName.substring(0, fileName.length() - 4) // remove ".pom"
        val li = fn2.lastIndexOf("-")
        if (li >= 0) {
          Some(fn2.substring(li + 1))
        } else in
      case _ => in
    }
  }
  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: MavenMarkers
  ): (TreeMap[String, TreeSet[StringOrPair]], MavenState) = {

    val baseTree = if (pomFile.length() > 4) {
      TreeMap(
        "pom" -> TreeSet(StringOrPair("text/xml", pomFile))
      )
    } else TreeMap[String, TreeSet[StringOrPair]]()

    val (
      manifest: TreeMap[String, TreeSet[StringOrPair]],
      manifestBuildDate: Option[java.util.Date]
    ) = marker match {
      case MavenMarkers.POM => (TreeMap[String, TreeSet[StringOrPair]](), None)
      case _ =>
        FileWalker
          .withinArchiveStream(artifact) { files =>
            files
              .filter(_.path().toUpperCase() == "META-INF/MANIFEST.MF")
              .headOption match {
              case Some(manifestFile) =>
                val manifestStr = manifestFile.withStream(stream => {
                  Helpers.slurpInputToString(stream)
                })
                val manifestMap = Helpers.treeInfoFromManifest(manifestStr)
                val buildDate = extractBuildDateFromManifest(manifestMap)
                (manifestMap, buildDate)

              case None => (TreeMap[String, TreeSet[StringOrPair]](), None)
            }
          }
          .getOrElse((TreeMap[String, TreeSet[StringOrPair]](), None))
    }

    // MANIFEST build date takes precedence over POM build date
    val finalBuildDate = manifestBuildDate.orElse(this.buildDate)

    val updatedState = this.copy(buildDate = finalBuildDate)

    Helpers.mergeTreeMaps(baseTree, manifest) -> updatedState
  }

  /** Extract build date from MANIFEST.MF attributes. Looks for Buid-Date
    * (common Maven typo) or Build-Date.
    */
  private def extractBuildDateFromManifest(
      manifest: TreeMap[String, TreeSet[StringOrPair]]
  ): Option[java.util.Date] = {
    // Try common build date keys (case-insensitive, as manifest keys are lowercased)
    val buildDateKeys =
      Seq("buid-date", "build-date", "implementation-date", "built-date")

    val dateStr = buildDateKeys.view
      .flatMap(key => manifest.get(key).flatMap(_.headOption).map(_.value))
      .headOption

    dateStr.flatMap { str =>
      Try {
        // Try ISO 8601 format
        val isoFormat =
          new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        isoFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
        isoFormat.parse(str)
      }.orElse(Try {
        // Try simple date format
        val simpleFormat = new java.text.SimpleDateFormat("yyyy-MM-dd")
        simpleFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
        simpleFormat.parse(str)
      }).orElse(Try {
        // Try RFC 2822 format (common in manifests)
        val rfcFormat =
          new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z")
        rfcFormat.parse(str)
      }).toOption
    }
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: MavenMarkers,
      parentScope: ParentScope,
      store: Storage
  ): (Item, MavenState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[Gitoid]],
      store: Storage,
      marker: MavenMarkers
  ): MavenState = (marker, kids) match {
    case (MavenMarkers.Sources, Some(kids)) =>
      val items = for {
        gitoid <- kids
        item <- store.read(gitoid()).toVector
        metadata <- item.bodyAsItemMetaData.toVector
        filename <- metadata.fileNames.toVector
      } yield filename -> item
      val ret = this.copy(
        sources = Map(items*),
        sourceGitoids = Map(items.map { case (k, v) => k -> Gitoid(v.identifier) }*)
      )
      ret
    case _ => this
  }

  /** Determine if this Maven package should generate a per-package tag.
    *
    * Only the JAR marker produces a tag. Requires groupId, artifactId, and
    * version.
    */
  override def maybePackageTag(marker: MavenMarkers): Option[PackageTagInfo] =
    marker match {
      case MavenMarkers.JAR
          if groupId.isDefined && artifactId.isDefined && version.isDefined =>
        val fullName = s"${groupId.get}:${artifactId.get}"
        Some(
          PackageTagInfo(
            name = fullName,
            version = version,
            date = buildDate
          )
        )
      case _ => None
    }

  override def generateParentScope(
      artifact: ArtifactWrapper,
      item: Item,
      store: Storage,
      marker: MavenMarkers,
      parentScope: Option[ParentScope],
      augmentationByHash: Map[String, Vector[Augmentation]]
  ): ParentScope = marker match {
    case MavenMarkers.JAR =>
      // the code that associates source with class files
      new ParentScope(augmentationByHash) {

        def scopeFor(): String = item.identifier
        def parentOfParentScope(): Option[ParentScope] = parentScope

        def parentScopeInformation(): String =
          f"Maven/JAR Scope for ${item.identifier}${parentScope match {
              case None     => ""
              case Some(ps) => f" Parent: ${ps.parentScopeInformation()}"
            }}"

        override def finalAugmentation(
            store: Storage,
            artifact: ArtifactWrapper,
            item: Item
        ): Item = {
          val sources = Helpers.computeAssociatedSource(
            artifact,
            associatedFiles = sourceGitoids
          )
          sources.foldLeft(item) { case (item, source) =>
            item.withConnection(EdgeType.builtFrom, source())
          }
        }
      }
    case _ => ParentScope.forAndWith(item.identifier, parentScope, Map())
  }

}

/** A Maven package bundle to process.
  *
  * Represents a complete Maven package including the main JAR and optional POM,
  * sources, and JavaDoc artifacts.
  *
  * @param jar
  *   the main JAR artifact (required)
  * @param pom
  *   the optional POM file
  * @param source
  *   the optional sources JAR
  * @param javaDoc
  *   the optional JavaDoc JAR
  */
final case class MavenToProcess(
    jar: ArtifactWrapper,
    pom: Option[ArtifactWrapper],
    source: Option[ArtifactWrapper],
    javaDoc: Option[ArtifactWrapper]
) extends ToProcess {

  /** Call at the end of successful completing the operation
    */
  def markSuccessfulCompletion(): Unit = {
    jar.finished()
    pom.foreach(_.finished())
    source.foreach(_.finished())
    javaDoc.foreach(_.finished())
  }
  type MarkerType = MavenMarkers
  type StateType = MavenState

  override def itemCnt: Int = {
    def bToI(b: Boolean): Int = if (b) 1 else 0
    1 + bToI(pom.isDefined) + bToI(source.isDefined) + bToI(javaDoc.isDefined)
  }
  override def main: String = jar.path()

  /** The mime type of the main artifact
    */
  def mimeType: Set[String] = jar.mimeType

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) = Vector(
    pom.toVector.map(pom => pom -> MavenMarkers.POM),
    source.toVector.map(src => src -> MavenMarkers.Sources),
    javaDoc.toVector.map(jd => jd -> MavenMarkers.JavaDocs),
    Vector(jar -> MavenMarkers.JAR)
  ).flatten -> MavenState()

}

/** Factory methods for creating Maven processing strategies. */
object MavenToProcess {
  val logger: Logger = Logger(getClass())

  /** Identify and group Maven artifacts from a collection of files.
    *
    * Finds JARs and matches them with their associated POM, sources, and
    * JavaDoc artifacts based on naming conventions.
    *
    * @param byUUID
    *   artifacts indexed by UUID
    * @param byName
    *   artifacts indexed by filename
    * @return
    *   tuple of (ToProcess items, remaining UUID map, remaining name map,
    *   strategy name)
    */
  def computeMavenFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val jars = byName.toVector.filter { case (name, artifacts) =>
      (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(
        ".war"
      )) && !(name.endsWith("-sources.jar") || name.endsWith(
        "-javadoc.jar"
      )) &&
      !artifacts.filter(_.mimeType.contains("application/java-archive")).isEmpty
    }

    val (toProcess, revisedByUUID, revisedByName) =
      jars.foldLeft((Vector[ToProcess](), byUUID, byName)) {
        case ((toProcess, byId, byName), (name, artifacts)) =>
          val noJarName = name.substring(0, name.length() - 4)
          val pomName = noJarName + ".pom"
          val javaDocName = noJarName + "-javadoc.jar"
          val sourcesName = noJarName + "-sources.jar"

          val poms = byName.get(pomName).toVector.flatten
          val javaDocs = byName.get(javaDocName).toVector.flatten
          val sources = byName.get(sourcesName).toVector.flatten
          val revisedById =
            Vector(artifacts, poms, sources, javaDocs).flatten.foldLeft(byId) {
              case (byId, artifact) => byId - artifact.uuid
            }
          val revisedToProcess = toProcess :+ MavenToProcess(
            artifacts.head,
            poms.headOption,
            sources.headOption,
            javaDocs.headOption
          )
          (
            revisedToProcess,
            revisedById,
            byName - name - pomName - javaDocName - sourcesName
          )
      }

    (toProcess, revisedByUUID, revisedByName, "Maven")
  }
}
