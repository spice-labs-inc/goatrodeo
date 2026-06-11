package io.spicelabs.goatrodeo.omnibor.strategies

import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.Augmentation
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
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
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.PURLHelpers
import io.spicelabs.goatrodeo.util.PURLHelpers.Ecosystems
import io.spicelabs.goatrodeo.util.PomParser
import org.apache.bcel.classfile.ClassParser
import org.apache.bcel.classfile.Module as BcelModule
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.compact
import org.json4s.native.JsonMethods.render

import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try
import scala.xml.NodeSeq

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

  /** A Maven repository metadata XML file. */
  case Metadata
}

/** Parsed contents of a `maven-metadata.xml` file.
  */
case class ParsedMavenMetadata(
    groupId: Option[String],
    artifactId: Option[String],
    latest: Option[String],
    release: Option[String],
    versions: Vector[String]
)

/** State maintained during Maven artifact processing.
  *
  * Tracks POM file content and source file mappings to enable:
  *   - Package URL generation from POM metadata
  *   - Source-to-class file mapping for "built from" relationships
  *
  * @param pomFile
  *   the raw POM file content as a string
  * @param pomXml
  *   the parsed POM XML (deprecated legacy field kept for backward compat)
  * @param parsedPom
  *   the parsed POM via PomParser (secure, interpolated)
  * @param sources
  *   map of source filenames to their Items
  * @param sourceGitoids
  *   map of source filenames to their GitOIDs
  * @param manifest
  *   TreeMap from MANIFEST.MF entries (lower-cased keys)
  * @param embeddedGavs
  *   all embedded GAV tuples found inside JARs (groupId, artifactId, version)
  * @param embeddedProps
  *   extracted pom.properties key-value pairs
  * @param embeddedPom
  *   parsed embedded pom.xml inside the JAR
  */
case class MavenState(
    pomFile: String = "",
    pomXml: NodeSeq = NodeSeq.Empty,
    parsedPom: Option[PomParser.ParsedPom] = None,
    sources: Map[String, Item] = Map(),
    sourceGitoids: Map[String, GitOID] = Map(),
    groupId: Option[String] = None,
    artifactId: Option[String] = None,
    version: Option[String] = None,
    buildDate: Option[Date] = None,
    manifest: TreeMap[String, TreeSet[StringOrPair]] = TreeMap.empty,
    embeddedGavs: Vector[(String, String, String)] = Vector.empty,
    embeddedProps: Map[String, String] = Map.empty,
    embeddedPom: Option[PomParser.ParsedPom] = None,
    metadataXmlContent: Option[String] = None,
    parsedMetadata: Option[ParsedMavenMetadata] = None
) extends ProcessingState[MavenMarkers, MavenState] {
  private lazy val logger = Logger(getClass())

  /** Resolve GAV using the priority chain:
    *   1. embeddedProps (pom.properties) 2. externalPom (parsedPom) 3.
    *      embeddedPom (pom.xml inside JAR) 4. manifest OSGi / standard headers
    *      5. filename heuristics
    */
  def resolveGAV(
      artifact: ArtifactWrapper,
      externalPom: Option[PomParser.ParsedPom] = None,
      manifest: TreeMap[String, TreeSet[StringOrPair]] = TreeMap.empty,
      embeddedProps: Map[String, String] = Map.empty,
      embeddedPom: Option[PomParser.ParsedPom] = None
  ): (Option[String], Option[String], Option[String]) = {

    // ---- priority 1: embedded pom.properties ----
    val fromProps = for {
      g <- embeddedProps.get("groupId")
      a <- embeddedProps.get("artifactId")
      v <- embeddedProps.get("version")
    } yield (Some(g), Some(a), Some(v))

    // ---- priority 2: external POM direct GAV ----
    val fromExternal = externalPom.flatMap { p =>
      for {
        g <- p.groupId
        a <- p.artifactId
        v <- p.version
      } yield (Some(g), Some(a), Some(v))
    }

    // ---- priority 3: embedded pom.xml ----
    val fromEmbedded = embeddedPom.flatMap { p =>
      for {
        g <- p.groupId
        a <- p.artifactId
        v <- p.version
      } yield (Some(g), Some(a), Some(v))
    }

    // ---- priority 4: manifest ----
    val fromManifest = resolveGAVFromManifest(manifest)

    // ---- priority 5: filename ----
    val fromFilename = extractIdentityFromFilename(artifact.filenameWithNoPath)

    (fromProps orElse fromExternal orElse fromEmbedded orElse fromManifest orElse fromFilename)
      .getOrElse((None, None, None))
  }

  /** Build a fallback (groupId, artifactId, version) from MANIFEST headers. */
  private def resolveGAVFromManifest(
      manifest: TreeMap[String, TreeSet[StringOrPair]]
  ): Option[(Option[String], Option[String], Option[String])] = {
    val bundleSymOpt =
      manifest.get("bundle-symbolicname").flatMap(_.headOption).map(_.value)
    val bundleVerOpt =
      manifest.get("bundle-version").flatMap(_.headOption).map(_.value)
    val bundleNameOpt =
      manifest.get("bundle-name").flatMap(_.headOption).map(_.value)
    val implVendorOpt = manifest
      .get("implementation-vendor-id")
      .flatMap(_.headOption)
      .map(_.value)
    val implTitleOpt =
      manifest.get("implementation-title").flatMap(_.headOption).map(_.value)
    val implVerOpt =
      manifest.get("implementation-version").flatMap(_.headOption).map(_.value)
    val specVerOpt =
      manifest.get("specification-version").flatMap(_.headOption).map(_.value)
    val extNameOpt =
      manifest.get("extension-name").flatMap(_.headOption).map(_.value)
    val createdByOpt =
      manifest.get("created-by").flatMap(_.headOption).map(_.value)

    val artifactIdOpt = bundleSymOpt
      .map { raw =>
        val stripped = raw.split(";")(0).trim
        // Maven Bundle Plugin heuristic: last segment
        if (
          createdByOpt.exists(
            _.toLowerCase.contains("apache maven bundle plugin")
          )
        ) {
          val parts = stripped.split("\\.")
          if (parts.length > 1) parts.last else stripped
        } else stripped
      }
      .orElse(implTitleOpt)
      .orElse(bundleNameOpt)
      .orElse(extNameOpt)

    val groupIdOpt = implVendorOpt.orElse {
      bundleSymOpt
        .map(_.split(";")(0).trim.split("\\.").init.mkString("."))
        .filter(_.nonEmpty)
    }

    val versionOpt = bundleVerOpt.orElse(implVerOpt).orElse(specVerOpt)

    if (artifactIdOpt.isDefined) {
      Some((groupIdOpt, artifactIdOpt, versionOpt))
    } else None
  }

  /** Extract (groupId, artifactId, version) from a Maven-style filename.
    * Patterns: name-1.2.3.jar → artifactId=name, version=1.2.3
    * name_2.13-1.2.3.jar → artifactId=name_2.13, version=1.2.3
    * name-1.0-SNAPSHOT.jar → version=1.0-SNAPSHOT groupid.artifactid-1.2.3.jar
    * → groupId=groupid, artifactId=artifactid, version=1.2.3
    *
    * Algorithm: find the first '-' whose following character is a digit. If the
    * artifact part contains dots, the last dot separates groupId from
    * artifactId.
    */
  private[strategies] def extractIdentityFromFilename(
      filename: String
  ): Option[(Option[String], Option[String], Option[String])] = {
    val extIdx = filename.lastIndexOf('.')
    val name = if (extIdx > 0) filename.substring(0, extIdx) else filename
    val splitIdx = name.zipWithIndex
      .find { case (ch, i) =>
        ch == '-' && i + 1 < name.length && name.charAt(i + 1).isDigit
      }
      .map(_._2)
    splitIdx match {
      case Some(idx) if idx > 0 =>
        val artifactPart = name.substring(0, idx)
        val versionPart = name.substring(idx + 1)
        if (versionPart.matches(".*\\d.*")) {
          val lastDot = artifactPart.lastIndexOf('.')
          if (lastDot > 0) {
            val afterDot = artifactPart.substring(lastDot + 1)
            // Avoid splitting on dots that are part of a Scala binary suffix
            // (e.g. lib_2.13 → afterDot "13" is purely numeric).
            if (afterDot.matches("\\d+(\\.\\d+)?")) {
              Some((None, Some(artifactPart), Some(versionPart)))
            } else {
              val groupId = artifactPart.substring(0, lastDot)
              val artifactId = artifactPart.substring(lastDot + 1)
              Some((Some(groupId), Some(artifactId), Some(versionPart)))
            }
          } else {
            Some((None, Some(artifactPart), Some(versionPart)))
          }
        } else None
      case _ => None
    }
  }

  /** Public entry point used by tests: extract identity from filename string
    * only.
    */
  def resolveGAVFromFilename(
      filename: String
  ): (Option[String], Option[String], Option[String]) =
    extractIdentityFromFilename(filename).getOrElse((None, None, None))

  /** Extract build date from MANIFEST.MF attributes. Tries (in order):
    * Bnd-LastModified (epoch millis), Build-Date, maven.build.timestamp,
    * Implementation-Date, Built-Date, Created-By (if date-like).
    */
  def buildDateFromManifest(
      manifest: TreeMap[String, TreeSet[StringOrPair]]
  ): Option[Date] = {
    val keysInPriority = Seq(
      "bnd-lastmodified",
      "build-date",
      "maven.build.timestamp",
      "implementation-date",
      "built-date",
      "created-by"
    )

    keysInPriority.view
      .flatMap(key => manifest.get(key).flatMap(_.headOption).map(_.value))
      .flatMap(parseDateString)
      .headOption
  }

  private val dateFormats: Vector[SimpleDateFormat] = Vector(
    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'") {
      setTimeZone(TimeZone.getTimeZone("UTC"))
    },
    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"),
    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"),
    new SimpleDateFormat("yyyy-MM-dd"),
    new SimpleDateFormat("dd-MMM-yyyy"),
    new SimpleDateFormat("yyyyMMdd-HHmm"),
    new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z")
  )

  /** Parse a date string using the fallback format chain. */
  def parseDateString(str: String): Option[Date] = {
    // Bnd-LastModified is epoch millis
    val trimmed = str.trim
    if (trimmed.matches("\\d+")) {
      Try(new Date(trimmed.toLong)).toOption
    } else {
      dateFormats.view.flatMap { fmt =>
        Try(fmt.parse(trimmed)).toOption
      }.headOption
    }
  }

  /** Extract build date from POM properties using PomParser-resolved props.
    * Properties checked (in order): buildDate, maven.build.timestamp,
    * build.timestamp, timestamp. Interpolates property references (e.g.
    * ${maven.timestamp}) before parsing.
    */
  private def extractBuildDateFromPom(
      parsed: Option[PomParser.ParsedPom]
  ): Option[Date] = {
    val keys = Vector(
      "buildDate",
      "maven.build.timestamp",
      "build.timestamp",
      "timestamp",
      "maven.timestamp"
    )
    keys.view
      .flatMap(key =>
        parsed.flatMap { p =>
          p.properties
            .get(key)
            .flatMap(raw => PomParser.interpolate(raw, p.properties))
        }
      )
      .flatMap(parseDateString)
      .headOption
  }

  /** Open a JAR artifact and enumerate embedded pom.properties + pom.xml
    * entries. Returns all embedded GAVs, all parsed properties, and all parsed
    * embedded POMs.
    */
  private def extractAllEmbeddedGavs(
      artifact: ArtifactWrapper
  ): (
      Vector[(String, String, String)],
      Map[String, String],
      Vector[(String, PomParser.ParsedPom)]
  ) = {
    var gavs = Vector.empty[(String, String, String)]
    var props = Map.empty[String, String]
    var poms = Vector.empty[(String, PomParser.ParsedPom)]

    FileWalker.withinArchiveStream(artifact) { entries =>
      entries.foreach { entry =>
        val path = entry.path().toLowerCase
        if (path.startsWith("meta-inf/maven/") && !path.contains("..")) {
          if (path.endsWith("/pom.properties")) {
            val content = entry.withStream(Helpers.slurpInputToString(_))
            val parsed = parsePropertiesString(content)
            for {
              g <- parsed.get("groupid")
              a <- parsed.get("artifactid")
              v <- parsed.get("version")
            } {
              gavs = gavs :+ (g, a, v)
            }
            props = props ++ parsed
          } else if (path.endsWith("/pom.xml")) {
            val content = entry.withStream(Helpers.slurpInputToString(_))
            PomParser.parse(content).foreach { p =>
              poms = poms :+ (entry.path(), p)
            }
          }
        }
      }
    }

    (gavs, props, poms)
  }

  /** Parse a Java properties-style string (key=value lines). */
  private def parsePropertiesString(text: String): Map[String, String] = {
    text.linesIterator
      .map(_.trim)
      .filterNot(_.startsWith("#"))
      .filter(_.contains("="))
      .map { line =>
        val idx = line.indexOf('=')
        line.substring(0, idx).trim.toLowerCase -> line.substring(idx + 1).trim
      }
      .toMap
  }

  /** Select the primary embedded GAV that best matches the filename. Returns
    * None if no embedded GAV matches the filename, so that priority falls
    * through to external POM instead of picking a random dependency's embedded
    * metadata from a fat jar.
    */
  private def determinePrimaryGav(
      gavs: Vector[(String, String, String)],
      filenameArt: String
  ): Option[(String, String, String)] = {
    gavs.find { case (_, art, _) =>
      filenameArt.contains(art) || art.contains(filenameArt)
    }
  }

  // ------------------------------------------------------------------
  // beginProcessing
  // ------------------------------------------------------------------
  def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: MavenMarkers
  ): MavenState = marker match {
    case MavenMarkers.POM =>
      val pomString = artifact.withStream(Helpers.slurpInputToString(_))
      val parsedOpt = PomParser.parse(pomString)
      val xml = parsedOpt match {
        case Some(_) => scala.xml.NodeSeq.Empty // not used, but keep compat
        case None    => scala.xml.NodeSeq.Empty
      }

      val gav = parsedOpt match {
        case Some(p) =>
          (p.groupId, p.artifactId, p.version)
        case None => (None, None, None)
      }

      // Fallback to filename extraction for any missing GAV fields
      val fallbackGav =
        if (gav._1.isEmpty || gav._2.isEmpty || gav._3.isEmpty) {
          extractIdentityFromFilename(artifact.filenameWithNoPath)
            .getOrElse((None, None, None))
        } else (None, None, None)

      val finalG = gav._1.orElse(fallbackGav._1)
      val finalA = gav._2.orElse(fallbackGav._2)
      val finalV = gav._3.orElse(fallbackGav._3)

      val bDate = extractBuildDateFromPom(parsedOpt)

      this.copy(
        pomFile = pomString,
        pomXml = xml,
        parsedPom = parsedOpt,
        groupId = finalG,
        artifactId = finalA,
        version = finalV,
        buildDate = bDate
      )

    case MavenMarkers.JAR =>
      // Read manifest
      val (manifestMap, manifestBuildDate) = FileWalker
        .withinArchiveStream(artifact) { files =>
          files
            .filter(_.path().toUpperCase() == "META-INF/MANIFEST.MF")
            .headOption match {
            case Some(manifestFile) =>
              val manifestStr =
                manifestFile.withStream(Helpers.slurpInputToString(_))
              val map = Helpers.treeInfoFromManifest(manifestStr)
              (map, buildDateFromManifest(map))
            case None =>
              (TreeMap.empty[String, TreeSet[StringOrPair]], None)
          }
        }
        .getOrElse((TreeMap.empty[String, TreeSet[StringOrPair]], None))

      // Extract embedded pom.properties + pom.xml
      val (embedGavs, embedProps, embedPoms) = extractAllEmbeddedGavs(artifact)

      // Determine primary embedded GAV (matching filename)
      val filenameArt = artifact.filenameWithNoPath.takeWhile(_ != '.')
      val primaryOpt = determinePrimaryGav(embedGavs, filenameArt)

      // Determine primary embedded POM — match by artifactId or path
      val primaryPomOpt = embedPoms.collectFirst {
        case (_, p)
            if p.artifactId.exists(a =>
              filenameArt.contains(a) || a.contains(filenameArt)
            ) =>
          p
      }

      // Build effective embedded props map (prefer primary if ambiguous).
      // If no primary match, do NOT fall back to a random dependency's
      // properties from a fat jar; let the priority chain fall through
      // to external POM / manifest / filename.
      val effectiveProps = primaryOpt
        .map { case (g, a, v) =>
          Map("groupId" -> g, "artifactId" -> a, "version" -> v)
        }
        .getOrElse(Map.empty)

      // Resolve final GAV via priority chain
      val (g, a, v) = resolveGAV(
        artifact = artifact,
        externalPom = this.parsedPom,
        manifest = manifestMap,
        embeddedProps = effectiveProps,
        embeddedPom = primaryPomOpt
      )

      val finalBuildDate = manifestBuildDate.orElse(this.buildDate)

      this.copy(
        manifest = manifestMap,
        embeddedGavs = embedGavs,
        embeddedProps = embedProps,
        embeddedPom = primaryPomOpt,
        groupId = g,
        artifactId = a,
        version = v,
        buildDate = finalBuildDate
      )

    case MavenMarkers.Metadata =>
      val xmlContent = artifact.withStream(Helpers.slurpInputToString(_))
      val parsed = parseMavenMetadata(xmlContent)
      this.copy(
        metadataXmlContent = Some(xmlContent),
        parsedMetadata = parsed
      )

    case _ => this
  }

  // ------------------------------------------------------------------
  // getPurls
  // ------------------------------------------------------------------
  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: MavenMarkers
  ): (Vector[String], MavenState) = {
    (groupId, artifactId, version) match {
      case (Some(g), Some(a), Some(v)) =>
        val classifier = marker match {
          case MavenMarkers.JAR      => None
          case MavenMarkers.Sources  => Some("sources")
          case MavenMarkers.POM      => Some("pom")
          case MavenMarkers.JavaDocs => Some("javadoc")
          case MavenMarkers.Metadata => None
        }
        val purl = PURLHelpers
          .buildPackageURL(
            Ecosystems.Maven,
            Some(g),
            a,
            v,
            classifier
          )
          .toCanonical()
          .nn
        Vector(purl) -> this
      case _ => (Vector.empty, this)
    }
  }

  // ------------------------------------------------------------------
  // getMetadata
  // ------------------------------------------------------------------
  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: MavenMarkers
  ): (TreeMap[String, TreeSet[StringOrPair]], MavenState) = {

    val baseTree = if (pomFile.length() > 4) {
      TreeMap("pom" -> TreeSet(StringOrPair("text/xml", pomFile)))
    } else TreeMap.empty[String, TreeSet[StringOrPair]]

    val extendedPomMeta = parsedPom
      .map(buildExtendedPomMetadata)
      .getOrElse(TreeMap.empty[String, TreeSet[StringOrPair]])

    val depMeta = parsedPom
      .map(buildDependencyMetadata)
      .getOrElse(TreeMap.empty[String, TreeSet[StringOrPair]])

    val licenseMeta = buildLicenseMetadata(
      parsedPom.map(_.licenses).getOrElse(Vector.empty),
      manifest
    )

    val parentMeta = parsedPom
      .map(buildParentPomMetadata)
      .getOrElse(TreeMap.empty[String, TreeSet[StringOrPair]])
    val metaMeta = buildMavenMetadata()

    val jarStructureMeta = marker match {
      case MavenMarkers.JAR => buildJarStructureMetadata(artifact, manifest)
      case _                => TreeMap.empty[String, TreeSet[StringOrPair]]
    }

    val merged1 = Helpers.mergeTreeMaps(baseTree, extendedPomMeta)
    val merged2 = Helpers.mergeTreeMaps(merged1, depMeta)
    val merged3 = Helpers.mergeTreeMaps(merged2, licenseMeta)
    val merged4 = Helpers.mergeTreeMaps(merged3, manifest)
    val merged5 = Helpers.mergeTreeMaps(merged4, parentMeta)
    val merged6 = Helpers.mergeTreeMaps(merged5, metaMeta)
    val merged7 = Helpers.mergeTreeMaps(merged6, jarStructureMeta)

    merged7 -> this
  }

  /** Map extended ParsedPom fields to MetadataKeyConstants keys. */
  private def buildExtendedPomMetadata(
      parsed: PomParser.ParsedPom
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val b = Vector.newBuilder[(String, TreeSet[StringOrPair])]
    parsed.name.foreach(v =>
      b += MetadataKeyConstants.NAME -> TreeSet(StringOrPair(v))
    )
    parsed.description.foreach(v =>
      b += MetadataKeyConstants.DESCRIPTION -> TreeSet(StringOrPair(v))
    )
    parsed.url.foreach(v =>
      b += MetadataKeyConstants.URL -> TreeSet(StringOrPair(v))
    )
    parsed.organization.foreach(v =>
      b += MetadataKeyConstants.PUBLISHER -> TreeSet(StringOrPair(v))
    )
    parsed.scmUrl.foreach(v =>
      b += MetadataKeyConstants.adHoc("maven")("SCM_URL") -> TreeSet(
        StringOrPair(v)
      )
    )
    TreeMap(b.result()*)
  }

  /** Build dependency JSON and RuntimeDependencies JSON. */
  private def buildDependencyMetadata(
      parsed: PomParser.ParsedPom
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    if (parsed.dependencies.isEmpty) {
      TreeMap.empty[String, TreeSet[StringOrPair]]
    } else {
      // Merge managed versions into dependencies lacking versions
      val managed = parsed.dependencyManagement
        .map(d =>
          (d.groupId.getOrElse(""), d.artifactId.getOrElse("")) -> d.version
        )
        .toMap

      val enriched = parsed.dependencies.map { d =>
        val v = d.version.orElse {
          managed.getOrElse(
            (d.groupId.getOrElse(""), d.artifactId.getOrElse("")),
            None
          )
        }
        d.copy(version = v)
      }

      val allDepsJson = compact(render(enriched.map { d =>
        ("group" -> d.groupId) ~
          ("artifact" -> d.artifactId) ~
          ("version" -> d.version) ~
          ("scope" -> d.scope) ~
          ("optional" -> d.optional) ~
          ("classifier" -> d.classifier) ~
          ("type" -> d.`type`)
      }))

      val runtimeDeps = enriched.filter { d =>
        val sc = d.scope.getOrElse("compile").toLowerCase
        sc == "compile" || sc == "runtime"
      }

      val rtDepsJson = if (runtimeDeps.nonEmpty) {
        compact(render(runtimeDeps.map { d =>
          ("group" -> d.groupId) ~
            ("artifact" -> d.artifactId) ~
            ("version" -> d.version) ~
            ("scope" -> d.scope)
        }))
      } else "[]"

      TreeMap(
        MetadataKeyConstants.adHoc("maven")("DEPENDENCIES") -> TreeSet(
          StringOrPair(allDepsJson)
        ),
        MetadataKeyConstants.adHoc("maven")("RuntimeDependencies") -> TreeSet(
          StringOrPair(rtDepsJson)
        )
      )
    }
  }

  /** Build LICENSE metadata from POM licenses and manifest Bundle-License. */
  private def buildLicenseMetadata(
      pomLicenses: Vector[PomParser.ParsedLicense],
      manifest: TreeMap[String, TreeSet[StringOrPair]]
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val pomLicenseValues = pomLicenses.collect {
      case PomParser.ParsedLicense(Some(name), url) =>
        url match {
          case Some(u) => s"$name ($u)"
          case None    => name
        }
      case PomParser.ParsedLicense(None, Some(url)) => url
    }

    val manifestLicenses = manifest
      .get("bundle-license")
      .toVector
      .flatMap(_.toVector)
      .map(_.value)

    val pluginLicenseName = manifest
      .get("plugin-license-name")
      .toVector
      .flatMap(_.toVector)
      .map(_.value)

    val allLicenses = pomLicenseValues ++ manifestLicenses ++ pluginLicenseName
    if (allLicenses.isEmpty) {
      TreeMap.empty
    } else {
      TreeMap(
        MetadataKeyConstants.LICENSE -> TreeSet(
          allLicenses.map(StringOrPair.apply)*
        )
      )
    }
  }

  /** Build ParentPOM metadata from ParsedPom parent fields. */
  private def buildParentPomMetadata(
      parsed: PomParser.ParsedPom
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val hasParent =
      parsed.parentGroupId.isDefined || parsed.parentArtifactId.isDefined || parsed.parentVersion.isDefined
    if (!hasParent) TreeMap.empty
    else {
      val json = compact(
        render(
          ("groupId" -> parsed.parentGroupId) ~
            ("artifactId" -> parsed.parentArtifactId) ~
            ("version" -> parsed.parentVersion)
        )
      )
      TreeMap(
        MetadataKeyConstants.adHoc("maven")("ParentPOM") -> TreeSet(
          StringOrPair(json)
        )
      )
    }
  }

  /** Build maven-metadata.xml derived metadata. */
  private def buildMavenMetadata(): TreeMap[String, TreeSet[StringOrPair]] = {
    parsedMetadata match {
      case None => TreeMap.empty
      case Some(meta) =>
        val b = Vector.newBuilder[(String, TreeSet[StringOrPair])]
        meta.latest.foreach(v =>
          b += MetadataKeyConstants.adHoc("maven")("Latest") -> TreeSet(
            StringOrPair(v)
          )
        )
        meta.release.foreach(v =>
          b += MetadataKeyConstants.adHoc("maven")("Release") -> TreeSet(
            StringOrPair(v)
          )
        )
        if (meta.versions.nonEmpty) {
          val json = compact(render(meta.versions))
          b += MetadataKeyConstants.adHoc("maven")("Versions") -> TreeSet(
            StringOrPair(json)
          )
        }
        TreeMap.from(b.result())
    }
  }

  /** Parse a `maven-metadata.xml` string into structured data. */
  private def parseMavenMetadata(
      xmlString: String
  ): Option[ParsedMavenMetadata] = {
    Try {
      val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
      dbf.setNamespaceAware(false)
      dbf.setValidating(false)
      dbf.setFeature(
        "http://apache.org/xml/features/disallow-doctype-decl",
        true
      )
      dbf.setFeature(
        "http://xml.org/sax/features/external-general-entities",
        false
      )
      dbf.setFeature(
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        false
      )
      val db = dbf.newDocumentBuilder()
      db.setErrorHandler(new org.xml.sax.ErrorHandler {
        def warning(e: org.xml.sax.SAXParseException): Unit = ()
        def error(e: org.xml.sax.SAXParseException): Unit = ()
        def fatalError(e: org.xml.sax.SAXParseException): Unit = ()
      })
      val doc = db.parse(
        new java.io.ByteArrayInputStream(xmlString.getBytes("UTF-8"))
      )

      def tagText(tag: String): Option[String] = {
        val nl = doc.getElementsByTagName(tag)
        if (nl.getLength > 0)
          Some(nl.item(0).getTextContent.trim).filter(_.nonEmpty)
        else None
      }

      val versions = {
        val nl = doc.getElementsByTagName("version")
        (0 until nl.getLength)
          .map(i => nl.item(i).getTextContent.trim)
          .filter(_.nonEmpty)
          .toVector
      }

      ParsedMavenMetadata(
        groupId = tagText("groupId"),
        artifactId = tagText("artifactId"),
        latest = tagText("latest"),
        release = tagText("release"),
        versions = versions
      )
    }.toOption
  }

  /** Parse an OSGi package header (Export-Package / Import-Package) into a
    * JSON-friendly vector of maps. Each entry is of the form:
    * package[;directive=value]* Entries are comma-separated.
    */
  private def parseOsgiPackageHeader(
      header: String
  ): Vector[Map[String, String]] = {
    val entries = header.split(',').toVector.map(_.trim).filter(_.nonEmpty)
    entries.map { entry =>
      val parts = entry.split(';').toVector.map(_.trim)
      if (parts.isEmpty) Map.empty[String, String]
      else {
        val pkg = parts.head
        val directives = parts.tail.flatMap { part =>
          part.split('=').toVector.map(_.trim) match {
            case Vector(k, v) =>
              Some(k -> v.stripPrefix("\"").stripSuffix("\""))
            case _ => None
          }
        }.toMap
        directives + ("package" -> pkg)
      }
    }
  }

  /** Parsed JPMS module-info.class attributes extracted via BCEL.
    */
  private case class ParsedModuleInfo(
      name: String,
      version: String,
      requires: Vector[String],
      exports: Vector[String],
      opens: Vector[String],
      provides: Map[String, Vector[String]],
      uses: Vector[String]
  )

  /** Parse a module-info.class byte array into structured metadata using BCEL.
    * Returns None on any parsing failure so that malformed entries are silently
    * skipped rather than breaking the archive scan.
    */
  private def parseModuleInfoClass(
      bytes: Array[Byte]
  ): Option[ParsedModuleInfo] = {
    Try {
      val parser =
        new ClassParser(new ByteArrayInputStream(bytes), "module-info.class")
      val jc = parser.parse()
      val cp = jc.getConstantPool()
      val moduleAttr = jc.getAttributes
        .find(_.getName == "Module")
        .map(_.asInstanceOf[BcelModule])
      moduleAttr.flatMap { mod =>
        val nameOpt = Option(mod.getModuleName(cp)).filter(_.nonEmpty)
        val versionOpt = Option(mod.getVersion(cp)).filter(_.nonEmpty)
        nameOpt.map { name =>
          ParsedModuleInfo(
            name = name,
            version = versionOpt.getOrElse(""),
            requires = mod.getRequiresTable.toVector.flatMap(r =>
              Option(r.getModuleName(cp))
            ),
            exports = mod.getExportsTable.toVector.flatMap(e =>
              Option(e.getPackageName(cp))
            ),
            opens = mod.getOpensTable.toVector.flatMap(o =>
              Option(o.getPackageName(cp))
            ),
            provides = mod.getProvidesTable.toVector.flatMap { p =>
              val iface = Option(p.getInterfaceName(cp))
              val impls = p.getImplementationClassNames(cp, true).toVector
              iface.map(_ -> impls)
            }.toMap,
            uses = mod.getUsedClassNames(cp, true).toVector
          )
        }
      }
    }.toOption.flatten
  }

  /** Scan a JAR/WAR/EAR archive for structural metadata (Phase 5). */
  private def buildJarStructureMetadata(
      artifact: ArtifactWrapper,
      manifest: TreeMap[String, TreeSet[StringOrPair]]
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    var jarType: Option[String] = None
    var nestedJars = Vector.empty[String]
    var springBootMainClass: Option[String] = None
    var layersIdx = Vector.empty[String]
    var classpathIdx = Vector.empty[String]
    var warLibJars = Vector.empty[String]
    var earModules = Vector.empty[String]
    var multiReleaseVersions = Vector.empty[String]
    var signatureFiles = Vector.empty[String]
    var serviceProviders = Map.empty[String, Vector[String]]
    var automaticModuleName: Option[String] = None
    var moduleRequires = Vector.empty[String]
    var moduleExports = Vector.empty[String]
    var moduleOpens = Vector.empty[String]
    var moduleProvides = Map.empty[String, Vector[String]]
    var moduleUses = Vector.empty[String]
    var graalNativeImageProps = Map.empty[String, String]
    var jenkinsPlugin = false
    var osgiHeaders = Map.empty[String, String]

    // Manifest-based checks
    springBootMainClass =
      manifest.get("start-class").flatMap(_.headOption).map(_.value)
    automaticModuleName =
      manifest.get("automatic-module-name").flatMap(_.headOption).map(_.value)
    if (
      manifest
        .get("multi-release")
        .flatMap(_.headOption)
        .map(_.value)
        .exists(_.toLowerCase == "true")
    ) {
      jarType = Some("multi-release")
    }
    if (
      manifest
        .get("created-by")
        .flatMap(_.headOption)
        .map(_.value)
        .exists(_.toLowerCase.contains("shade plugin"))
    ) {
      jarType = Some("shaded-jar")
    }
    val isJenkinsByGroupId = manifest
      .get("group-id")
      .flatMap(_.headOption)
      .map(_.value)
      .exists(_.toLowerCase.contains("jenkins.plugins"))
    val isJenkinsByExt = {
      val lower = artifact.filenameWithNoPath.toLowerCase
      lower.endsWith(".jpi") || lower.endsWith(".hpi")
    }
    jenkinsPlugin = isJenkinsByGroupId || isJenkinsByExt

    // OSGi headers
    val osgiKeys = Vector(
      "bundle-name" -> "BundleName",
      "bundle-description" -> "BundleDescription",
      "bundle-vendor" -> "BundleVendor",
      "bundle-docurl" -> "BundleDocURL"
    )
    val osgiValues = osgiKeys.flatMap { case (mk, ok) =>
      manifest.get(mk).flatMap(_.headOption).map(_.value).map(ok -> _)
    }
    // Full OSGi headers (Phase 5.11 extended)
    val fullOsgiKeys = Vector(
      "export-package" -> "ExportPackage",
      "import-package" -> "ImportPackage",
      "require-capability" -> "RequireCapability",
      "provide-capability" -> "ProvideCapability",
      "fragment-host" -> "FragmentHost"
    )
    val fullOsgiValues = fullOsgiKeys.flatMap { case (mk, ok) =>
      manifest.get(mk).flatMap(_.headOption).map(_.value).map(ok -> _)
    }
    osgiHeaders = (osgiValues ++ fullOsgiValues).toMap

    // Walk archive entries
    FileWalker.withinArchiveStream(artifact) { entries =>
      entries.foreach { entry =>
        val path = entry.path()
        val lowerPath = path.toLowerCase

        // Spring Boot
        if (lowerPath.startsWith("boot-inf/classes/")) {
          jarType = jarType.orElse(Some("spring-boot-fat-jar"))
        }
        if (
          lowerPath.startsWith("boot-inf/lib/") && lowerPath.endsWith(".jar")
        ) {
          nestedJars = nestedJars :+ path
        }
        if (lowerPath == "boot-inf/layers.idx") {
          layersIdx = entry
            .withStream(Helpers.slurpInputToString(_))
            .linesIterator
            .filter(_.nonEmpty)
            .toVector
        }
        if (lowerPath == "boot-inf/classpath.idx") {
          classpathIdx = entry
            .withStream(Helpers.slurpInputToString(_))
            .linesIterator
            .filter(_.nonEmpty)
            .toVector
        }

        // Shade Plugin marker
        if (lowerPath.contains("maven-shade-plugin")) {
          jarType = jarType.orElse(Some("shaded-jar"))
        }

        // WAR
        if (
          lowerPath.startsWith("web-inf/lib/") && lowerPath.endsWith(".jar")
        ) {
          warLibJars = warLibJars :+ path
        }
        if (lowerPath == "web-inf/web.xml") {
          jarType = jarType.orElse(Some("war"))
        }

        // EAR
        if (lowerPath == "meta-inf/application.xml") {
          jarType = jarType.orElse(Some("ear"))
          val appXmlStr = entry.withStream(Helpers.slurpInputToString(_))
          // Extract module names: <ejb>name</ejb>, <web-uri>name</web-uri>, <alt-dd>name</alt-dd>, <connector>name</connector>
          val modulePattern =
            "<(ejb|web-uri|alt-dd|connector|java|web)>([^<]+)</(ejb|web-uri|alt-dd|connector|java|web)>".r
          val extracted = modulePattern
            .findAllMatchIn(appXmlStr)
            .map(m => Option(m.group(2)).map(_.trim).getOrElse(""))
            .filter(_.nonEmpty)
            .toVector
            .distinct
          earModules = earModules ++ extracted
        }

        // Multi-Release versions
        if (lowerPath.startsWith("meta-inf/versions/")) {
          val parts = lowerPath.split('/')
          if (parts.length >= 3) {
            val ver = parts(2)
            if (!multiReleaseVersions.contains(ver)) {
              multiReleaseVersions = multiReleaseVersions :+ ver
            }
          }
        }

        // Signatures
        if (
          lowerPath.startsWith("meta-inf/") && (lowerPath.endsWith(
            ".sf"
          ) || lowerPath.endsWith(".rsa") || lowerPath.endsWith(".dsa"))
        ) {
          signatureFiles = signatureFiles :+ path
        }

        // ServiceLoader
        if (lowerPath.startsWith("meta-inf/services/")) {
          val serviceName = path.substring("meta-inf/services/".length)
          val impls = entry
            .withStream(Helpers.slurpInputToString(_))
            .linesIterator
            .filter(_.nonEmpty)
            .toVector
          serviceProviders = serviceProviders + (serviceName -> impls)
        }

        // JPMS module-info.class
        if (lowerPath == "module-info.class") {
          val bytes = entry.withStream(Helpers.slurpInputNoClose)
          parseModuleInfoClass(bytes).foreach { info =>
            automaticModuleName = Some(info.name)
            moduleRequires = info.requires
            moduleExports = info.exports
            moduleOpens = info.opens
            moduleProvides = info.provides
            moduleUses = info.uses
          }
        }

        // GraalVM native-image.properties
        if (
          lowerPath.endsWith("native-image.properties") && lowerPath.startsWith(
            "meta-inf/native-image/"
          )
        ) {
          val props = entry
            .withStream(Helpers.slurpInputToString(_))
            .linesIterator
            .filterNot(_.trim.startsWith("#"))
            .filter(_.contains("="))
            .map { line =>
              val idx = line.indexOf('=')
              line.substring(0, idx).trim -> line.substring(idx + 1).trim
            }
            .toMap
          graalNativeImageProps = graalNativeImageProps ++ props
        }
      }
    }

    // Build metadata
    val b = scala.collection.mutable.ArrayBuffer
      .empty[(String, TreeSet[StringOrPair])]

    jarType.foreach(t =>
      b += MetadataKeyConstants.adHoc("maven")("JarType") -> TreeSet(
        StringOrPair(t)
      )
    )
    if (nestedJars.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")("NestedJars") -> TreeSet(
        StringOrPair(compact(render(nestedJars)))
      )
    springBootMainClass.foreach(c =>
      b += MetadataKeyConstants.adHoc("maven")(
        "SpringBootMainClass"
      ) -> TreeSet(StringOrPair(c))
    )
    if (layersIdx.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")("LayersIdx") -> TreeSet(
        StringOrPair(compact(render(layersIdx)))
      )
    if (classpathIdx.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")("ClasspathIdx") -> TreeSet(
        StringOrPair(compact(render(classpathIdx)))
      )
    if (warLibJars.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")("WarLibJars") -> TreeSet(
        StringOrPair(compact(render(warLibJars)))
      )
    if (earModules.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")("EarModules") -> TreeSet(
        StringOrPair(compact(render(earModules)))
      )
    if (multiReleaseVersions.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")(
        "MultiReleaseVersions"
      ) -> TreeSet(StringOrPair(compact(render(multiReleaseVersions))))
    if (signatureFiles.nonEmpty) {
      b += MetadataKeyConstants.adHoc("maven")("JarSigned") -> TreeSet(
        StringOrPair("true")
      )
      b += MetadataKeyConstants.adHoc("maven")("SignatureFiles") -> TreeSet(
        StringOrPair(compact(render(signatureFiles)))
      )
    }
    if (serviceProviders.nonEmpty) {
      val json = compact(render(serviceProviders.map { case (k, v) =>
        k -> v
      }.toMap))
      b += MetadataKeyConstants.adHoc("maven")("ServiceProviders") -> TreeSet(
        StringOrPair(json)
      )
    }
    automaticModuleName.foreach(n =>
      b += MetadataKeyConstants.adHoc("maven")(
        "AutomaticModuleName"
      ) -> TreeSet(StringOrPair(n))
    )
    if (moduleRequires.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")("ModuleRequires") -> TreeSet(
        StringOrPair(compact(render(moduleRequires)))
      )
    if (moduleExports.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")("ModuleExports") -> TreeSet(
        StringOrPair(compact(render(moduleExports)))
      )
    if (moduleOpens.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")("ModuleOpens") -> TreeSet(
        StringOrPair(compact(render(moduleOpens)))
      )
    if (moduleProvides.nonEmpty) {
      val json = compact(render(moduleProvides))
      b += MetadataKeyConstants.adHoc("maven")("ModuleProvides") -> TreeSet(
        StringOrPair(json)
      )
    }
    if (moduleUses.nonEmpty)
      b += MetadataKeyConstants.adHoc("maven")("ModuleUses") -> TreeSet(
        StringOrPair(compact(render(moduleUses)))
      )
    if (graalNativeImageProps.nonEmpty) {
      val json = compact(render(graalNativeImageProps.toMap))
      b += MetadataKeyConstants.adHoc("maven")("GraalNativeImage") -> TreeSet(
        StringOrPair(json)
      )
    }
    if (jenkinsPlugin)
      b += MetadataKeyConstants.adHoc("maven")("JenkinsPlugin") -> TreeSet(
        StringOrPair("true")
      )
    if (osgiHeaders.nonEmpty) {
      osgiHeaders.foreach { case (k, v) =>
        val value = k match {
          case "ExportPackage" =>
            val parsed = parseOsgiPackageHeader(v)
            if (parsed.nonEmpty) compact(render(parsed)) else v
          case "ImportPackage" =>
            val parsed = parseOsgiPackageHeader(v)
            if (parsed.nonEmpty) compact(render(parsed)) else v
          case _ => v
        }
        b += MetadataKeyConstants.adHoc("osgi")(k) -> TreeSet(
          StringOrPair(value)
        )
      }
    }

    TreeMap.from(b.toSeq)
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: MavenMarkers,
      parentScope: ParentScope,
      store: Storage
  ): (Item, MavenState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: MavenMarkers
  ): MavenState = (marker, kids) match {
    case (MavenMarkers.Sources, Some(kids)) =>
      val items = for {
        gitoid <- kids
        item <- store.read(gitoid).toVector
        metadata <- item.bodyAsItemMetaData.toVector
        filename <- metadata.fileNames.toVector
      } yield filename -> item
      this.copy(
        sources = Map(items*),
        sourceGitoids = Map(items.map { case (k, v) => k -> v.identifier }*)
      )
    case _ => this
  }

  override def maybePackageTag(marker: MavenMarkers): Option[PackageTagInfo] =
    marker match {
      case MavenMarkers.JAR
          if groupId.isDefined && artifactId.isDefined && version.isDefined =>
        Some(
          PackageTagInfo(
            name = s"${groupId.get}:${artifactId.get}",
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
            item.withConnection(EdgeType.builtFrom, source)
          }
        }
      }
    case _ => ParentScope.forAndWith(item.identifier, parentScope, Map())
  }
}

// ------------------------------------------------------------------
// MavenToProcess
// ------------------------------------------------------------------
final case class MavenToProcess(
    jar: ArtifactWrapper,
    pom: Option[ArtifactWrapper],
    source: Option[ArtifactWrapper],
    javaDoc: Option[ArtifactWrapper],
    metadataXml: Option[ArtifactWrapper] = None
) extends ToProcess {

  def markSuccessfulCompletion(): Unit = {
    jar.finished()
    pom.foreach(_.finished())
    source.foreach(_.finished())
    javaDoc.foreach(_.finished())
    metadataXml.foreach(_.finished())
  }
  type MarkerType = MavenMarkers
  type StateType = MavenState

  override def itemCnt: Int = {
    def bToI(b: Boolean): Int = if (b) 1 else 0
    1 + bToI(pom.isDefined) + bToI(source.isDefined) + bToI(
      javaDoc.isDefined
    ) + bToI(metadataXml.isDefined)
  }
  override def main: String = jar.path()
  def mimeType: Set[String] = jar.mimeType

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) = Vector(
    pom.toVector.map(pom => pom -> MavenMarkers.POM),
    source.toVector.map(src => src -> MavenMarkers.Sources),
    javaDoc.toVector.map(jd => jd -> MavenMarkers.JavaDocs),
    metadataXml.toVector.map(m => m -> MavenMarkers.Metadata),
    Vector(jar -> MavenMarkers.JAR)
  ).flatten -> MavenState()
}

/** Factory methods for creating Maven processing strategies. */
object MavenToProcess {
  val logger: Logger = Logger(getClass())

  /** Extensions recognized as Java archives by Maven strategy. Includes
    * standard and industry-standard additional types.
    */
  private val archiveExtensions: Set[String] = Set(
    ".jar",
    ".war",
    ".ear",
    ".par",
    ".sar",
    ".nar",
    ".jpi",
    ".hpi",
    ".kar",
    ".far",
    ".lpkg",
    ".rar",
    ".zap"
  )

  /** True if the filename ends with any recognized archive extension and is not
    * a sources/javadoc classifier.
    */
  private def isMavenArchive(name: String): Boolean = {
    val lower = name.toLowerCase
    archiveExtensions.exists(ext => lower.endsWith(ext)) &&
    !lower.endsWith("-sources.jar") &&
    !lower.endsWith("-javadoc.jar")
  }

  /** Detect JARs with NewRelic Weave-Classes MANIFEST header. These should be
    * excluded from Maven strategy (fall through to Generic).
    */
  private def hasWeaveClasses(artifact: ArtifactWrapper): Boolean = {
    FileWalker
      .withinArchiveStream(artifact) { files =>
        files.find(_.path().equalsIgnoreCase("META-INF/MANIFEST.MF")) match {
          case Some(entry) =>
            val text = entry.withStream(Helpers.slurpInputToString(_))
            text.linesIterator.exists(
              _.trim.toLowerCase.startsWith("weave-classes")
            )
          case None => false
        }
      }
      .getOrElse(false)
  }

  def computeMavenFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val jars = byName.toVector.filter { case (name, artifacts) =>
      isMavenArchive(name) &&
      artifacts.exists(_.mimeType.contains("application/java-archive")) &&
      !hasWeaveClasses(artifacts.head)
    }

    // Build directory-to-metadata mapping for maven-metadata.xml files
    val metadataXmlFiles = byName.getOrElse("maven-metadata.xml", Vector.empty)
    val metadataXmlByDir = metadataXmlFiles.groupBy { a =>
      Option(new java.io.File(a.path()).getParent()).getOrElse("")
    }

    val (toProcess, revisedByUUID, revisedByName, consumedMetaPaths) =
      jars.foldLeft((Vector[ToProcess](), byUUID, byName, Set.empty[String])) {
        case ((toProcess, byId, byName, consumed), (name, artifacts)) =>
          val extLen = name.lastIndexOf('.') match {
            case -1 => 0
            case i  => name.length - i
          }
          val noExtName = name.substring(0, name.length() - extLen)
          val pomName = noExtName + ".pom"
          val javaDocName = noExtName + "-javadoc.jar"
          val sourcesName = noExtName + "-sources.jar"

          val poms = byName.get(pomName).toVector.flatten
          val javaDocs = byName.get(javaDocName).toVector.flatten
          val sources = byName.get(sourcesName).toVector.flatten
          val revisedById =
            Vector(artifacts, poms, sources, javaDocs).flatten.foldLeft(byId) {
              case (byId, artifact) => byId - artifact.uuid
            }

          // Try to match a maven-metadata.xml in the same directory
          val jarDir = Option(
            new java.io.File(artifacts.head.path()).getParent()
          ).getOrElse("")
          val metaXml = metadataXmlByDir.get(jarDir).flatMap(_.headOption)

          val revisedToProcess = toProcess :+ MavenToProcess(
            artifacts.head,
            poms.headOption,
            sources.headOption,
            javaDocs.headOption,
            metaXml
          )
          val newConsumed = metaXml.map(_.path()).toSet ++ consumed
          (
            revisedToProcess,
            revisedById,
            byName - name - pomName - javaDocName - sourcesName,
            newConsumed
          )
      }

    // Remove consumed maven-metadata.xml entries from revisedByName
    val finalRevisedByName = if (consumedMetaPaths.nonEmpty) {
      val allMeta = revisedByName.getOrElse("maven-metadata.xml", Vector.empty)
      val remaining =
        allMeta.filterNot(a => consumedMetaPaths.contains(a.path()))
      if (remaining.isEmpty) revisedByName - "maven-metadata.xml"
      else revisedByName.updated("maven-metadata.xml", remaining)
    } else revisedByName

    (toProcess, revisedByUUID, finalRevisedByName, "Maven")
  }
}
