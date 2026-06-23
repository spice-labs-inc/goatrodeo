package io.spicelabs.goatrodeo.omnibor.strategies

import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.Augmentation
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ItemMetaData
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

/** Mutable accumulator for JAR-structure metadata collected during child
  * processing via `accumulateInfo`. This replaces the 3 separate archive walks
  * (manifest reading, extractAllEmbeddedGavs, buildJarStructureMetadata) that
  * previously occurred in `beginProcessing(JAR)`.
  *
  * Why a separate case class instead of vars on MavenState?
  *   - Separation of concerns: these fields are only relevant for the JAR
  *     marker. POM/Metadata/Sources markers never touch them.
  *   - MavenState stays focused on POM, metadata, and source concerns. Adding
  *     20+ jar-specific vars would bloat it.
  *   - `Option[JarAccumulatedState]` = None for non-JAR markers makes the
  *     lifecycle explicit — you know accumulation hasn't started vs. has
  *     started but found nothing.
  *   - Testability: can unit-test the accumulation logic independently.
  *   - Clear reset: `applyAccumulatedAugmentation` sets the field back to
  *     `None` rather than resetting 20+ individual vars.
  *
  * Thread safety: this class is NOT thread-safe. It relies on the single-
  * threaded processing pipeline in `ToProcess.process`. If the pipeline becomes
  * multi-threaded in the future, this will need synchronization.
  *
  * @param manifest
  *   TreeMap from MANIFEST.MF entries (lower-cased keys), populated when the
  *   `META-INF/MANIFEST.MF` child entry is encountered
  * @param buildDate
  *   build date extracted from manifest headers, if present
  * @param embeddedGavs
  *   all embedded GAV tuples found in pom.properties files inside the JAR; each
  *   tuple is (groupId, artifactId, version)
  * @param embeddedProps
  *   extracted pom.properties key-value pairs (lower-cased keys)
  * @param embeddedPoms
  *   all embedded pom.xml files found inside the JAR, stored with their archive
  *   path so we can later select the "primary" one matching the JAR filename.
  *   Replaces the single `embeddedPom` field that only stored one POM.
  * @param jarType
  *   detected JAR type (e.g., "spring-boot-fat-jar", "shaded-jar", "war",
  *   "ear", "multi-release")
  * @param nestedJars
  *   paths to nested JARs found in BOOT-INF/lib/
  * @param springBootMainClass
  *   the Start-Class from Spring Boot manifest header
  * @param layersIdx
  *   lines from BOOT-INF/layers.idx (Spring Boot layered format)
  * @param classpathIdx
  *   lines from BOOT-INF/classpath.idx (Spring Boot classpath ordering)
  * @param warLibJars
  *   paths to JARs found in WEB-INF/lib/ (WAR structure)
  * @param earModules
  *   module names extracted from META-INF/application.xml (EAR structure)
  * @param multiReleaseVersions
  *   Java version numbers found under META-INF/versions/ (multi-release JAR)
  * @param signatureFiles
  *   paths to signature files (.sf, .rsa, .dsa) under META-INF/
  * @param serviceProviders
  *   map of service interface name to implementation class names, from
  *   META-INF/services/ entries (ServiceLoader metadata)
  * @param automaticModuleName
  *   Automatic-Module-Name from manifest or module-info.class
  * @param moduleRequires
  *   / moduleExports / moduleOpens / moduleProvides / moduleUses JPMS module
  *   descriptor data extracted from module-info.class via BCEL
  * @param graalNativeImageProps
  *   key-value pairs from
  *   META-INF/native-image/[subdir]/native-image.properties
  * @param jenkinsPlugin
  *   true if this JAR is a Jenkins plugin (detected by Group-Id manifest header
  *   or .jpi/.hpi file extension)
  * @param osgiHeaders
  *   OSGi bundle headers extracted from manifest (Bundle-Name, Export-Package,
  *   Import-Package, etc.)
  */
case class JarAccumulatedState(
    var manifest: TreeMap[String, TreeSet[StringOrPair]] = TreeMap.empty,
    var buildDate: Option[Date] = None,
    var embeddedGavs: Vector[(String, String, String)] = Vector.empty,
    var embeddedProps: Map[String, String] = Map.empty,
    var embeddedPoms: Vector[(String, PomParser.ParsedPom)] = Vector.empty,
    var jarType: Option[String] = None,
    var nestedJars: Vector[String] = Vector.empty,
    var springBootMainClass: Option[String] = None,
    var layersIdx: Vector[String] = Vector.empty,
    var classpathIdx: Vector[String] = Vector.empty,
    var warLibJars: Vector[String] = Vector.empty,
    var earModules: Vector[String] = Vector.empty,
    var multiReleaseVersions: Vector[String] = Vector.empty,
    var signatureFiles: Vector[String] = Vector.empty,
    var serviceProviders: Map[String, Vector[String]] = Map.empty,
    var automaticModuleName: Option[String] = None,
    var moduleRequires: Vector[String] = Vector.empty,
    var moduleExports: Vector[String] = Vector.empty,
    var moduleOpens: Vector[String] = Vector.empty,
    var moduleProvides: Map[String, Vector[String]] = Map.empty,
    var moduleUses: Vector[String] = Vector.empty,
    var graalNativeImageProps: Map[String, String] = Map.empty,
    var jenkinsPlugin: Boolean = false,
    var osgiHeaders: Map[String, String] = Map.empty
)

/** State maintained during Maven artifact processing.
  *
  * Tracks POM file content and source file mappings to enable:
  *   - Package URL generation from POM metadata
  *   - Source-to-class file mapping for "built from" relationships
  *
  * For JAR markers, metadata is accumulated during child processing via
  * `JarAccumulatedState` (the `jarAccumulated` field). GAV resolution and pURL
  * generation are deferred to `applyAccumulatedAugmentation`, which runs after
  * all children have been processed.
  *
  * IMPORTANT: Several fields are `var` rather than `val` because they are
  * mutated in `applyAccumulatedAugmentation` after child processing completes.
  * The `jarAccumulated` field is set in `beginProcessing(JAR)`, populated via
  * `accumulateInfo` during child processing, consumed in
  * `applyAccumulatedAugmentation`, and then reset to `None`.
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
  * @param groupId
  *   resolved group ID (mutable: set in beginProcessing for POM, set in
  *   applyAccumulatedAugmentation for JAR)
  * @param artifactId
  *   resolved artifact ID (mutable: same lifecycle as groupId)
  * @param version
  *   resolved version (mutable: same lifecycle as groupId)
  * @param buildDate
  *   build date from manifest or POM (mutable: may be updated from accumulated
  *   manifest data)
  * @param metadataXmlContent
  *   raw maven-metadata.xml content (for Metadata marker)
  * @param parsedMetadata
  *   parsed maven-metadata.xml (for Metadata marker)
  * @param jarAccumulated
  *   mutable accumulator for JAR-structure metadata;
  *   `Some(JarAccumulatedState())` during JAR processing (set in
  *   beginProcessing), `None` otherwise; reset to `None` after
  *   `applyAccumulatedAugmentation` consumes it
  */
case class MavenState(
    pomFile: String = "",
    pomXml: NodeSeq = NodeSeq.Empty,
    parsedPom: Option[PomParser.ParsedPom] = None,
    sources: Map[String, Item] = Map(),
    sourceGitoids: Map[String, GitOID] = Map(),
    var groupId: Option[String] = None,
    var artifactId: Option[String] = None,
    var version: Option[String] = None,
    var buildDate: Option[Date] = None,
    var metadataXmlContent: Option[String] = None,
    var parsedMetadata: Option[ParsedMavenMetadata] = None,
    var jarAccumulated: Option[JarAccumulatedState] = None
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

  /** Parse a Java properties-style string (key=value lines).
    */
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
      // Initialize the JAR accumulation container. No archive walks here —
      // all metadata is collected via `accumulateInfo` as children are
      // processed, then applied in `applyAccumulatedAugmentation` after all
      // children are done.
      //
      // Previously, beginProcessing(JAR) opened the archive 3 times:
      //   1. To read META-INF/MANIFEST.MF (manifest map + build date)
      //   2. To extract pom.properties/pom.xml (extractAllEmbeddedGavs)
      //   3. To scan for jar structure metadata (buildJarStructureMetadata)
      // Now the same entries are encountered during child processing and
      // accumulated via the `accumulateInfo` override in the ParentScope
      // created by `generateParentScope(JAR)`.
      //
      // GAV resolution is deferred to `applyAccumulatedAugmentation` because
      // it requires data from children (manifest, embedded pom.properties,
      // etc.) that isn't available until after child processing completes.
      this.jarAccumulated = Some(JarAccumulatedState())
      this

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
    // For JAR markers, GAV is not yet resolved at this point in the pipeline.
    // getPurls is called BEFORE children are processed, but GAV depends on
    // data accumulated from children (manifest, pom.properties, etc.).
    // The pURL is instead generated in applyAccumulatedAugmentation after
    // all children have been processed and GAV has been resolved.
    //
    // For non-JAR markers (POM, Sources, JavaDocs, Metadata), GAV is already
    // available from beginProcessing, so we generate the pURL here as before.
    (groupId, artifactId, version, jarAccumulated) match {
      case (_, _, _, Some(_)) =>
        // JAR accumulation in progress — GAV not yet resolved.
        // pURL will be created in applyAccumulatedAugmentation.
        (Vector.empty, this)
      case (Some(g), Some(a), Some(v), None) =>
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

    // For JAR markers, manifest data lives in jarAccumulated.
    // For other markers, there is no manifest.
    val currentManifest = jarAccumulated
      .map(_.manifest)
      .getOrElse(TreeMap.empty[String, TreeSet[StringOrPair]])

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
      currentManifest
    )

    val parentMeta = parsedPom
      .map(buildParentPomMetadata)
      .getOrElse(TreeMap.empty[String, TreeSet[StringOrPair]])
    val metaMeta = buildMavenMetadata()

    // For JAR markers, build jar-structure metadata from the accumulated
    // state (no archive walk). This may be called before children are
    // fully processed, in which case jarAccumulated may be partially
    // populated. The full metadata is also applied in
    // applyAccumulatedAugmentation after all children are done.
    val jarStructureMeta = marker match {
      case MavenMarkers.JAR =>
        buildJarStructureMetadataFromAccumulated(jarAccumulated)
      case _ => TreeMap.empty[String, TreeSet[StringOrPair]]
    }

    val merged1 = Helpers.mergeTreeMaps(baseTree, extendedPomMeta)
    val merged2 = Helpers.mergeTreeMaps(merged1, depMeta)
    val merged3 = Helpers.mergeTreeMaps(merged2, licenseMeta)
    val merged4 = Helpers.mergeTreeMaps(merged3, currentManifest)
    val merged5 = Helpers.mergeTreeMaps(merged4, parentMeta)
    val merged6 = Helpers.mergeTreeMaps(merged5, metaMeta)
    val merged7 = Helpers.mergeTreeMaps(merged6, jarStructureMeta)

    merged7 -> this
  }

  /** Accumulate metadata from a child artifact into the JAR accumulation state.
    * This is called from `passToParent` in the processing pipeline for each
    * child entry found inside a JAR archive.
    *
    * The method inspects the child's path to determine what kind of metadata it
    * contains (manifest, pom.properties, pom.xml, Spring Boot entries, etc.)
    * and mutates the `JarAccumulatedState` fields directly.
    *
    * This replaces the 3 separate archive walks that previously occurred in
    * `beginProcessing(JAR)`:
    *   - manifest reading → handled by META-INF/MANIFEST.MF path check
    *   - extractAllEmbeddedGavs → handled by pom.properties/pom.xml checks
    *   - buildJarStructureMetadata → handled by all other path checks
    *
    * Path traversal protection: any path containing ".." is silently skipped to
    * prevent malicious JAR entries from escaping the archive structure.
    *
    * @param parentId
    *   the GitOID of the parent JAR item
    * @param item
    *   the child Item (unused here; metadata comes from the artifact)
    * @param artifact
    *   the child ArtifactWrapper — its path() identifies the entry type
    * @param store
    *   the Storage (unused here; all writes happen in
    *   applyAccumulatedAugmentation)
    */
  def accumulateInfo(
      parentId: String,
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): Unit = {
    // Only accumulate if we're in JAR processing mode
    val acc = jarAccumulated match {
      case Some(a) => a
      case None    => return
    }
    val path = artifact.path()
    val lowerPath = path.toLowerCase

    // Path traversal protection — skip entries that could escape the archive
    if (path.contains("..")) return

    // ---- META-INF/MANIFEST.MF ----
    // The manifest is the single richest source of JAR metadata.
    // It provides: OSGi headers, Spring Boot main class, automatic module
    // name, multi-release flag, shade plugin detection, Jenkins detection,
    // and build date.
    if (lowerPath == "meta-inf/manifest.mf") {
      val manifestStr = artifact.withStream(Helpers.slurpInputToString(_))
      val map = Helpers.treeInfoFromManifest(manifestStr)
      acc.manifest = map
      acc.buildDate = buildDateFromManifest(map)

      // Spring Boot Start-Class
      acc.springBootMainClass =
        map.get("start-class").flatMap(_.headOption).map(_.value)

      // Automatic-Module-Name
      acc.automaticModuleName =
        map.get("automatic-module-name").flatMap(_.headOption).map(_.value)

      // Multi-Release JAR detection
      if (
        map
          .get("multi-release")
          .flatMap(_.headOption)
          .map(_.value)
          .exists(_.toLowerCase == "true")
      ) {
        acc.jarType = Some("multi-release")
      }

      // Shade plugin detection via Created-By header
      if (
        map
          .get("created-by")
          .flatMap(_.headOption)
          .map(_.value)
          .exists(_.toLowerCase.contains("shade plugin"))
      ) {
        acc.jarType = Some("shaded-jar")
      }

      // Jenkins plugin detection via Group-Id manifest header
      val isJenkinsByGroupId = map
        .get("group-id")
        .flatMap(_.headOption)
        .map(_.value)
        .exists(_.toLowerCase.contains("jenkins.plugins"))
      val isJenkinsByExt = {
        val lower = artifact.filenameWithNoPath.toLowerCase
        // We can't check the JAR's own extension from a child entry,
        // so we check the filename from the parent artifact's name.
        // This is a limitation — jenkins detection by extension is
        // handled in applyAccumulatedAugmentation using the parent
        // artifact's filename.
        lower.endsWith(".jpi") || lower.endsWith(".hpi")
      }
      if (isJenkinsByGroupId || isJenkinsByExt) {
        acc.jenkinsPlugin = true
      }

      // OSGi headers from manifest
      val osgiKeys = Vector(
        "bundle-name" -> "BundleName",
        "bundle-description" -> "BundleDescription",
        "bundle-vendor" -> "BundleVendor",
        "bundle-docurl" -> "BundleDocURL"
      )
      val osgiValues = osgiKeys.flatMap { case (mk, ok) =>
        map.get(mk).flatMap(_.headOption).map(_.value).map(ok -> _)
      }
      val fullOsgiKeys = Vector(
        "export-package" -> "ExportPackage",
        "import-package" -> "ImportPackage",
        "require-capability" -> "RequireCapability",
        "provide-capability" -> "ProvideCapability",
        "fragment-host" -> "FragmentHost"
      )
      val fullOsgiValues = fullOsgiKeys.flatMap { case (mk, ok) =>
        map.get(mk).flatMap(_.headOption).map(_.value).map(ok -> _)
      }
      acc.osgiHeaders = (osgiValues ++ fullOsgiValues).toMap
    }

    // ---- META-INF/maven/*/pom.properties ----
    // Extract GAV coordinates and properties from embedded pom.properties.
    // All GAVs are accumulated; primary selection happens later in
    // applyAccumulatedAugmentation using determinePrimaryGav.
    if (
      lowerPath
        .startsWith("meta-inf/maven/") && lowerPath.endsWith("/pom.properties")
    ) {
      val content = artifact.withStream(Helpers.slurpInputToString(_))
      val parsed = parsePropertiesString(content)
      for {
        g <- parsed.get("groupid")
        a <- parsed.get("artifactid")
        v <- parsed.get("version")
      } {
        acc.embeddedGavs = acc.embeddedGavs :+ (g, a, v)
      }
      acc.embeddedProps = acc.embeddedProps ++ parsed
    }

    // ---- META-INF/maven/*/pom.xml ----
    // Parse embedded POM files. All POMs are stored with their path;
    // primary selection happens later in applyAccumulatedAugmentation.
    if (
      lowerPath.startsWith("meta-inf/maven/") && lowerPath.endsWith("/pom.xml")
    ) {
      val content = artifact.withStream(Helpers.slurpInputToString(_))
      PomParser.parse(content).foreach { p =>
        acc.embeddedPoms = acc.embeddedPoms :+ (path, p)
      }
    }

    // ---- Spring Boot fat JAR detection ----
    if (lowerPath.startsWith("boot-inf/classes/")) {
      acc.jarType = acc.jarType.orElse(Some("spring-boot-fat-jar"))
    }
    if (lowerPath.startsWith("boot-inf/lib/") && lowerPath.endsWith(".jar")) {
      acc.nestedJars = acc.nestedJars :+ path
    }
    if (lowerPath == "boot-inf/layers.idx") {
      acc.layersIdx = artifact
        .withStream(Helpers.slurpInputToString(_))
        .linesIterator
        .filter(_.nonEmpty)
        .toVector
    }
    if (lowerPath == "boot-inf/classpath.idx") {
      acc.classpathIdx = artifact
        .withStream(Helpers.slurpInputToString(_))
        .linesIterator
        .filter(_.nonEmpty)
        .toVector
    }

    // ---- Maven Shade Plugin detection ----
    if (lowerPath.contains("maven-shade-plugin")) {
      acc.jarType = acc.jarType.orElse(Some("shaded-jar"))
    }

    // ---- WAR structure ----
    if (lowerPath.startsWith("web-inf/lib/") && lowerPath.endsWith(".jar")) {
      acc.warLibJars = acc.warLibJars :+ path
    }
    if (lowerPath == "web-inf/web.xml") {
      acc.jarType = acc.jarType.orElse(Some("war"))
    }

    // ---- EAR structure ----
    if (lowerPath == "meta-inf/application.xml") {
      acc.jarType = acc.jarType.orElse(Some("ear"))
      val appXmlStr = artifact.withStream(Helpers.slurpInputToString(_))
      val modulePattern =
        "<(ejb|web-uri|alt-dd|connector|java|web)>([^<]+)</(ejb|web-uri|alt-dd|connector|java|web)>".r
      val extracted = modulePattern
        .findAllMatchIn(appXmlStr)
        .map(m => Option(m.group(2)).map(_.trim).getOrElse(""))
        .filter(_.nonEmpty)
        .toVector
        .distinct
      acc.earModules = acc.earModules ++ extracted
    }

    // ---- Multi-Release JAR versions ----
    if (lowerPath.startsWith("meta-inf/versions/")) {
      val parts = lowerPath.split('/')
      if (parts.length >= 3) {
        val ver = parts(2)
        if (!acc.multiReleaseVersions.contains(ver)) {
          acc.multiReleaseVersions = acc.multiReleaseVersions :+ ver
        }
      }
    }

    // ---- JAR signatures ----
    if (
      lowerPath.startsWith("meta-inf/") && (lowerPath.endsWith(
        ".sf"
      ) || lowerPath.endsWith(".rsa") || lowerPath.endsWith(".dsa"))
    ) {
      acc.signatureFiles = acc.signatureFiles :+ path
    }

    // ---- ServiceLoader providers ----
    if (lowerPath.startsWith("meta-inf/services/")) {
      val serviceName = path.substring("meta-inf/services/".length)
      val impls = artifact
        .withStream(Helpers.slurpInputToString(_))
        .linesIterator
        .filter(_.nonEmpty)
        .toVector
      acc.serviceProviders = acc.serviceProviders + (serviceName -> impls)
    }

    // ---- JPMS module-info.class ----
    if (lowerPath == "module-info.class") {
      val bytes = artifact.withStream(Helpers.slurpInputNoClose)
      parseModuleInfoClass(bytes).foreach { info =>
        acc.automaticModuleName = Some(info.name)
        acc.moduleRequires = info.requires
        acc.moduleExports = info.exports
        acc.moduleOpens = info.opens
        acc.moduleProvides = info.provides
        acc.moduleUses = info.uses
      }
    }

    // ---- GraalVM native-image.properties ----
    if (
      lowerPath.endsWith("native-image.properties") && lowerPath.startsWith(
        "meta-inf/native-image/"
      )
    ) {
      val props = artifact
        .withStream(Helpers.slurpInputToString(_))
        .linesIterator
        .filterNot(_.trim.startsWith("#"))
        .filter(_.contains("="))
        .map { line =>
          val idx = line.indexOf('=')
          line.substring(0, idx).trim -> line.substring(idx + 1).trim
        }
        .toMap
      acc.graalNativeImageProps = acc.graalNativeImageProps ++ props
    }
  }

  /** Apply accumulated JAR metadata to the item in the store.
    *
    * This method runs AFTER all children have been processed and
    * `accumulateInfo` has been called for each child. It:
    *
    *   1. Resolves GAV from the accumulated data (manifest, pom.properties,
    *      embedded POM) using the 5-level priority chain in `resolveGAV`. 2.
    *      Creates/updates the pURL item in the store with an `aliasTo` edge
    *      pointing back to the JAR item. A single pURL can have multiple
    *      `aliasTo` entries (e.g., if the same GAV appears in multiple JARs).
    *      3. Updates the JAR item with an `aliasFrom` edge pointing to the pURL
    *      and merges jar-structure metadata into the item's body. 4. Clears the
    *      `jarAccumulated` state to prevent double-application.
    *
    * IMPORTANT: All store operations use the `store.write(path, opr, ctx)`
    * callback pattern. There are NO standalone `store.read` calls followed by
    * `store.write` — the read-modify-write cycle is always within a single
    * `store.write` call to maintain the row-level lock. There are also NO
    * nested `store.write` calls for the same path (which would deadlock on the
    * row lock).
    *
    * @param item
    *   the JAR Item that was written to the store during processing
    * @param artifact
    *   the JAR ArtifactWrapper (used for filename heuristics in GAV resolution)
    * @param store
    *   the Storage to update with pURL items and backlinks
    * @return
    *   this MavenState with jarAccumulated cleared to None
    */
  def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): MavenState = {
    jarAccumulated match {
      case None =>
        // Non-JAR marker or already applied — nothing to do
        this
      case Some(acc) =>
        // ---- Step 1: Resolve GAV from accumulated data ----
        // Select the primary embedded GAV that matches the JAR filename.
        // This prevents a fat JAR's random embedded dependencies from
        // overriding the JAR's own identity.
        val filenameArt = artifact.filenameWithNoPath.takeWhile(_ != '.')
        val primaryOpt = determinePrimaryGav(acc.embeddedGavs, filenameArt)

        // Build effective embedded props map (prefer primary if ambiguous).
        // If no primary match, do NOT fall back to a random dependency's
        // properties from a fat jar; let the priority chain fall through
        // to external POM / manifest / filename.
        val effectiveProps = primaryOpt
          .map { case (g, a, v) =>
            Map("groupId" -> g, "artifactId" -> a, "version" -> v)
          }
          .getOrElse(Map.empty)

        // Select the primary embedded POM (matching the JAR filename)
        val primaryPomOpt = acc.embeddedPoms.collectFirst {
          case (_, p)
              if p.artifactId.exists(a =>
                filenameArt.contains(a) || a.contains(filenameArt)
              ) =>
            p
        }

        // Resolve final GAV via the 5-level priority chain:
        //   1. embeddedProps (pom.properties)
        //   2. externalPom (parsedPom from external POM file)
        //   3. embeddedPom (pom.xml inside JAR)
        //   4. manifest OSGi / standard headers
        //   5. filename heuristics
        val (g, a, v) = resolveGAV(
          artifact = artifact,
          externalPom = this.parsedPom,
          manifest = acc.manifest,
          embeddedProps = effectiveProps,
          embeddedPom = primaryPomOpt
        )
        this.groupId = g
        this.artifactId = a
        this.version = v
        this.buildDate = acc.buildDate.orElse(this.buildDate)

        // Jenkins plugin detection by file extension — this can only be
        // done here (not in accumulateInfo) because the child entries
        // don't carry the JAR's own filename.
        if (!acc.jenkinsPlugin) {
          val lower = artifact.filenameWithNoPath.toLowerCase
          if (lower.endsWith(".jpi") || lower.endsWith(".hpi")) {
            acc.jenkinsPlugin = true
          }
        }

        // Build jar-structure metadata tree from accumulated state
        val jarStructureMeta =
          buildJarStructureMetadataFromAccumulated(Some(acc))

        // Build the full metadata tree that getMetadata would produce
        // NOW (after children are processed), since getMetadata ran before
        // children and had an empty manifest/pomFile. The manifest and
        // pom entries must be merged into the store item here.
        val fullMeta = {
          val manifestTree = acc.manifest
          val baseTree =
            if (pomFile.length() > 4)
              TreeMap("pom" -> TreeSet(StringOrPair("text/xml", pomFile)))
            else TreeMap.empty[String, TreeSet[StringOrPair]]
          val extendedPomMeta = parsedPom
            .map(buildExtendedPomMetadata)
            .getOrElse(TreeMap.empty[String, TreeSet[StringOrPair]])
          val depMeta = parsedPom
            .map(buildDependencyMetadata)
            .getOrElse(TreeMap.empty[String, TreeSet[StringOrPair]])
          val licenseMeta = buildLicenseMetadata(
            parsedPom.map(_.licenses).getOrElse(Vector.empty),
            manifestTree
          )
          val parentMeta = parsedPom
            .map(buildParentPomMetadata)
            .getOrElse(TreeMap.empty[String, TreeSet[StringOrPair]])
          val metaMeta = buildMavenMetadata()
          Helpers.mergeTreeMaps(
            Helpers.mergeTreeMaps(
              Helpers.mergeTreeMaps(
                Helpers.mergeTreeMaps(
                  Helpers.mergeTreeMaps(
                    Helpers.mergeTreeMaps(
                      Helpers.mergeTreeMaps(baseTree, extendedPomMeta),
                      depMeta
                    ),
                    licenseMeta
                  ),
                  manifestTree
                ),
                parentMeta
              ),
              metaMeta
            ),
            jarStructureMeta
          )
        }

        // ---- Step 2: Create/update pURL and fix backlinks ----
        // For a complete GAV, create a pURL item and establish bidirectional
        // alias edges:
        //   - pURL item gets aliasTo -> JAR item
        //   - JAR item gets aliasFrom -> pURL
        //
        // A single pURL can have multiple aliasTo entries (e.g., if the
        // same GAV appears in multiple JARs, each JAR gets its own aliasTo
        // entry on the shared pURL item).
        (g, a, v) match {
          case (Some(groupId), Some(artId), Some(ver)) =>
            val purl = PURLHelpers
              .buildPackageURL(
                Ecosystems.Maven,
                Some(groupId),
                artId,
                ver,
                None
              )
              .toCanonical()
              .nn

            // Register the pURL with the store's pURL index
            store.addPurl(purl)

            // WRITE 1: Update JAR item — add aliasFrom -> pURL and merge
            // full metadata (manifest, pom, jar-structure, etc.).
            // Uses store.write callback to ensure atomic read-modify-write
            // under the row-level lock.
            // No prior store.read call; no nested store.write for same path.
            store.write(
              item.identifier,
              {
                case Some(existing) =>
                  val withAlias = existing.copy(
                    connections =
                      existing.connections + (EdgeType.aliasFrom -> purl)
                  )
                  val withMeta =
                    if (fullMeta.nonEmpty)
                      withAlias.enhanceWithMetadata(
                        extra = fullMeta,
                        filenames = Vector.empty,
                        mimeTypes = Vector.empty
                      )
                    else withAlias
                  Some(withMeta)
                case None =>
                  val base = item.copy(
                    connections =
                      item.connections + (EdgeType.aliasFrom -> purl)
                  )
                  val withMeta =
                    if (fullMeta.nonEmpty)
                      base.enhanceWithMetadata(
                        extra = fullMeta,
                        filenames = Vector.empty,
                        mimeTypes = Vector.empty
                      )
                    else base
                  Some(withMeta)
              },
              _ => s"accumulated augmentation: aliasFrom $purl + metadata"
            )

            // WRITE 2: Create/update pURL item with aliasTo -> JAR item.
            // Different path from WRITE 1, so different row lock — no
            // deadlock risk. If the pURL item already exists (another JAR
            // with the same GAV), we add another aliasTo entry.
            store.write(
              purl,
              {
                case Some(existingPurlItem) =>
                  // pURL item already exists — add another aliasTo pointing
                  // to this JAR item
                  Some(
                    existingPurlItem.copy(
                      connections =
                        existingPurlItem.connections + (EdgeType.aliasTo -> item.identifier)
                    )
                  )
                case None =>
                  // First time we've seen this pURL — create the item
                  Some(
                    Item(
                      purl,
                      TreeSet(EdgeType.aliasTo -> item.identifier),
                      Some(ItemMetaData.mimeType),
                      Some(
                        ItemMetaData(
                          fileNames = TreeSet(purl),
                          mimeType = TreeSet[String](),
                          fileSize = 0,
                          extra = TreeMap[String, TreeSet[StringOrPair]]()
                        )
                      )
                    )
                  )
              },
              _ => s"pURL item for $purl"
            )

          case _ =>
            // Incomplete GAV — no pURL generated, but still apply full
            // metadata (manifest, pom, jar-structure, etc.) from accumulated
            // state
            if (fullMeta.nonEmpty) {
              store.write(
                item.identifier,
                {
                  case Some(existing) =>
                    Some(
                      existing.enhanceWithMetadata(
                        extra = fullMeta,
                        filenames = Vector.empty,
                        mimeTypes = Vector.empty
                      )
                    )
                  case None =>
                    Some(
                      item.enhanceWithMetadata(
                        extra = fullMeta,
                        filenames = Vector.empty,
                        mimeTypes = Vector.empty
                      )
                    )
                },
                _ => "accumulated augmentation: metadata only (no pURL)"
              )
            }
        }

        // ---- Step 3: Clear accumulated state ----
        // All accumulated data has been consumed (GAV resolved, pURL
        // created, metadata merged). Reset to None to prevent
        // double-application if applyAccumulatedAugmentation is somehow
        // called again.
        this.jarAccumulated = None

        this
    }
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
  /** Build jar-structure metadata TreeMap from accumulated state.
    *
    * This replaces the old `buildJarStructureMetadata(artifact, manifest)`
    * method that opened the archive to scan for structural metadata. Now the
    * data is collected via `accumulateInfo` during child processing and stored
    * in `JarAccumulatedState`. This method simply reads the accumulated fields
    * and builds the same metadata TreeMap — no archive walk needed.
    *
    * @param acc
    *   the accumulated state from child processing; None if no accumulation
    *   (non-JAR marker or before children are processed)
    * @return
    *   TreeMap of metadata key → values for jar structure entries
    */
  private def buildJarStructureMetadataFromAccumulated(
      acc: Option[JarAccumulatedState]
  ): TreeMap[String, TreeSet[StringOrPair]] = acc match {
    case None => TreeMap.empty
    case Some(a) =>
      val b = scala.collection.mutable.ArrayBuffer
        .empty[(String, TreeSet[StringOrPair])]

      a.jarType.foreach(t =>
        b += MetadataKeyConstants.adHoc("maven")("JarType") -> TreeSet(
          StringOrPair(t)
        )
      )
      if (a.nestedJars.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")("NestedJars") -> TreeSet(
          StringOrPair(compact(render(a.nestedJars)))
        )
      a.springBootMainClass.foreach(c =>
        b += MetadataKeyConstants.adHoc("maven")(
          "SpringBootMainClass"
        ) -> TreeSet(StringOrPair(c))
      )
      if (a.layersIdx.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")("LayersIdx") -> TreeSet(
          StringOrPair(compact(render(a.layersIdx)))
        )
      if (a.classpathIdx.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")("ClasspathIdx") -> TreeSet(
          StringOrPair(compact(render(a.classpathIdx)))
        )
      if (a.warLibJars.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")("WarLibJars") -> TreeSet(
          StringOrPair(compact(render(a.warLibJars)))
        )
      if (a.earModules.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")("EarModules") -> TreeSet(
          StringOrPair(compact(render(a.earModules)))
        )
      if (a.multiReleaseVersions.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")(
          "MultiReleaseVersions"
        ) -> TreeSet(StringOrPair(compact(render(a.multiReleaseVersions))))
      if (a.signatureFiles.nonEmpty) {
        b += MetadataKeyConstants.adHoc("maven")("JarSigned") -> TreeSet(
          StringOrPair("true")
        )
        b += MetadataKeyConstants.adHoc("maven")("SignatureFiles") -> TreeSet(
          StringOrPair(compact(render(a.signatureFiles)))
        )
      }
      if (a.serviceProviders.nonEmpty) {
        val json = compact(render(a.serviceProviders.map { case (k, v) =>
          k -> v
        }.toMap))
        b += MetadataKeyConstants.adHoc("maven")("ServiceProviders") -> TreeSet(
          StringOrPair(json)
        )
      }
      a.automaticModuleName.foreach(n =>
        b += MetadataKeyConstants.adHoc("maven")(
          "AutomaticModuleName"
        ) -> TreeSet(StringOrPair(n))
      )
      if (a.moduleRequires.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")("ModuleRequires") -> TreeSet(
          StringOrPair(compact(render(a.moduleRequires)))
        )
      if (a.moduleExports.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")("ModuleExports") -> TreeSet(
          StringOrPair(compact(render(a.moduleExports)))
        )
      if (a.moduleOpens.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")("ModuleOpens") -> TreeSet(
          StringOrPair(compact(render(a.moduleOpens)))
        )
      if (a.moduleProvides.nonEmpty) {
        val json = compact(render(a.moduleProvides))
        b += MetadataKeyConstants.adHoc("maven")("ModuleProvides") -> TreeSet(
          StringOrPair(json)
        )
      }
      if (a.moduleUses.nonEmpty)
        b += MetadataKeyConstants.adHoc("maven")("ModuleUses") -> TreeSet(
          StringOrPair(compact(render(a.moduleUses)))
        )
      if (a.graalNativeImageProps.nonEmpty) {
        val json = compact(render(a.graalNativeImageProps.toMap))
        b += MetadataKeyConstants.adHoc("maven")("GraalNativeImage") -> TreeSet(
          StringOrPair(json)
        )
      }
      if (a.jenkinsPlugin)
        b += MetadataKeyConstants.adHoc("maven")("JenkinsPlugin") -> TreeSet(
          StringOrPair("true")
        )
      if (a.osgiHeaders.nonEmpty) {
        a.osgiHeaders.foreach { case (k, v) =>
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

        /** Override accumulateInfo to collect JAR-structure metadata from child
          * entries. This is called for each child artifact found inside the JAR
          * during the processing pipeline. The child's path determines what
          * kind of metadata to accumulate.
          *
          * This override delegates to MavenState.accumulateInfo, which mutates
          * the JarAccumulatedState fields directly.
          */
        override def accumulateInfo(
            parentId: String,
            item: Item,
            artifact: ArtifactWrapper,
            store: Storage
        ): Unit = {
          MavenState.this.accumulateInfo(parentId, item, artifact, store)
        }

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

  def computeMavenFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val jars = byName.toVector.filter { case (name, artifacts) =>
      isMavenArchive(name) &&
      artifacts.exists(_.mimeType.contains("application/java-archive"))
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
