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
import io.spicelabs.goatrodeo.omnibor.PurlSet
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
  * (manifest reading, extractAllEmbeddedGroupIdArtifactIdVersion,
  * buildJarStructureMetadata) that previously occurred in
  * `beginProcessing(JAR)`.
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
  * @param embeddedGroupIdArtifactIdVersions
  *   all embedded groupId/artifactId/version tuples found in pom.properties
  *   files inside the JAR; each tuple is (groupId, artifactId, version)
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
    var embeddedGroupIdArtifactIdVersions: Vector[(String, String, String)] =
      Vector.empty,
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
  * Three separate accumulators — one per marker type that collects metadata
  * from archive children (pom.properties, MANIFEST.MF, embedded POMs). Each is
  * set by its own `beginProcessing` call, populated via `accumulateInfo` during
  * child processing, and consumed by `applyAccumulatedAugmentation` after all
  * children are done. They are independent — no cross-contamination, no reset
  * between markers.
  *
  * groupId, artifactId, and version are NOT stored on MavenState. They are
  * derived data, computed from the accumulator + parsedPom via
  * `resolveGroupIdArtifactIdVersion` and used as local variables in
  * `applyAccumulatedAugmentation` and the `getPurls` fallback.
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
  * @param buildDate
  *   build date from manifest or POM (mutable: may be updated from accumulated
  *   manifest data)
  * @param metadataXmlContent
  *   raw maven-metadata.xml content (for Metadata marker)
  * @param parsedMetadata
  *   parsed maven-metadata.xml (for Metadata marker)
  * @param jarAccumulated
  *   accumulator for JAR marker's archive-structure metadata; set in
  *   `beginProcessing(JAR)`, consumed in `applyAccumulatedAugmentation`
  * @param sourcesAccumulated
  *   accumulator for Sources marker's archive-structure metadata; set in
  *   `beginProcessing(Sources)`, consumed in `applyAccumulatedAugmentation`
  * @param javadocAccumulated
  *   accumulator for JavaDocs marker's archive-structure metadata; set in
  *   `beginProcessing(JavaDocs)`, consumed in `applyAccumulatedAugmentation`
  * @param currentMarker
  *   which marker is currently being processed (set in `beginProcessing`). Used
  *   by `accumulateInfo` and `applyAccumulatedAugmentation` to select the
  *   correct accumulator and determine the classifier.
  */
case class MavenState(
    pomFile: String = "",
    pomXml: NodeSeq = NodeSeq.Empty,
    parsedPom: Option[PomParser.ParsedPom] = None,
    sources: Map[String, Item] = Map(),
    sourceGitoids: Map[String, GitOID] = Map(),
    var buildDate: Option[Date] = None,
    var metadataXmlContent: Option[String] = None,
    var parsedMetadata: Option[ParsedMavenMetadata] = None,
    var jarAccumulated: Option[JarAccumulatedState] = None,
    var sourcesAccumulated: Option[JarAccumulatedState] = None,
    var javadocAccumulated: Option[JarAccumulatedState] = None,
    var currentMarker: Option[MavenMarkers] = None
) extends ProcessingState[MavenMarkers, MavenState] {
  private lazy val logger = Logger(getClass())

  /** Returns the accumulator for the currently active marker, or None. */
  def currentAccumulator: Option[JarAccumulatedState] =
    currentMarker match {
      case Some(MavenMarkers.JAR)      => jarAccumulated
      case Some(MavenMarkers.Sources)  => sourcesAccumulated
      case Some(MavenMarkers.JavaDocs) => javadocAccumulated
      case _                           => None
    }

  /** Returns the classifier for the currently active marker.
    *
    * JAR: derived from filename (Some("sources")/Some("javadoc")/None) Sources:
    * always Some("sources") JavaDocs: always Some("javadoc") Other markers:
    * None
    */
  def currentClassifier: Option[String] =
    currentMarker match {
      case Some(MavenMarkers.Sources)  => Some("sources")
      case Some(MavenMarkers.JavaDocs) => Some("javadoc")
      case Some(MavenMarkers.POM)      => Some("pom")
      case _                           => None
    }

  /** Sets the accumulator for the given marker. */
  private def setAccumulator(
      marker: MavenMarkers,
      acc: Option[JarAccumulatedState]
  ): Unit =
    marker match {
      case MavenMarkers.JAR      => jarAccumulated = acc
      case MavenMarkers.Sources  => sourcesAccumulated = acc
      case MavenMarkers.JavaDocs => javadocAccumulated = acc
      case _                     => ()
    }

  /** Clears the accumulator for the given marker (after consumption). */
  private def clearAccumulator(marker: MavenMarkers): Unit =
    setAccumulator(marker, None)

  /** Resolve groupId/artifactId/version using field-level merge.
    *
    * For each field (groupId, artifactId, version), the best value is picked
    * from the best source FOR THAT FIELD, not from the first source that
    * provides all three. This produces pURLs more likely to exist in Maven
    * Central because:
    *   - Manifest's Implementation-Title is human-readable, NOT the Maven
    *     artifactId. The filename usually matches the Maven artifactId.
    *   - So for artifactId, filename has higher priority than manifest.
    *   - For groupId and version, manifest is still higher priority than
    *     filename (manifest has vendor info and build version).
    *
    * Per-field priority: groupId: external POM > pom.properties > embedded
    * pom.xml > manifest > filename artifactId: external POM > pom.properties >
    * embedded pom.xml > filename > manifest version: external POM >
    * pom.properties > embedded pom.xml > manifest > filename
    *
    * The companion POM (external POM) is the HIGHEST priority source for
    * canonical pURL resolution (REQ-3). It is the authoritative published Maven
    * metadata, more reliable than embedded pom.properties (which may be from
    * shaded dependencies), manifest headers, or filename heuristics.
    * pom.properties is filename-gated by
    * `determinePrimaryGroupIdArtifactIdVersion` in
    * `applyAccumulatedAugmentation` — it only contributes fields when its
    * artifactId matches the JAR filename, preventing cross-artifact mixing.
    */
  def resolveGroupIdArtifactIdVersion(
      artifact: ArtifactWrapper,
      externalPom: Option[PomParser.ParsedPom] = None,
      manifest: TreeMap[String, TreeSet[StringOrPair]] = TreeMap.empty,
      embeddedProps: Map[String, String] = Map.empty,
      embeddedPom: Option[PomParser.ParsedPom] = None
  ): (Option[String], Option[String], Option[String]) = {

    // Gather candidates from each source, per field.
    // .filter(_.nonEmpty) ensures that empty-string values (e.g. from
    // a pom.properties with "groupId=") are treated as missing.
    val propsGroupId = embeddedProps.get("groupId").filter(_.nonEmpty)
    val propsArtifactId = embeddedProps.get("artifactId").filter(_.nonEmpty)
    val propsVersion = embeddedProps.get("version").filter(_.nonEmpty)

    val extGroupId = externalPom
      .flatMap(p => p.groupId.orElse(p.parentGroupId))
      .filter(_.nonEmpty)
    val extArtifactId = externalPom.flatMap(_.artifactId).filter(_.nonEmpty)
    val extVersion = externalPom
      .flatMap(p => p.version.orElse(p.parentVersion))
      .filter(_.nonEmpty)

    val embGroupId = embeddedPom.flatMap(_.groupId).filter(_.nonEmpty)
    val embArtifactId = embeddedPom.flatMap(_.artifactId).filter(_.nonEmpty)
    val embVersion = embeddedPom.flatMap(_.version).filter(_.nonEmpty)

    // Manifest contributes individual fields without a gate — even when
    // it has no artifactId headers, its groupId (Implementation-Vendor-Id)
    // and version (Implementation-Version) are still valid.
    val (manGroupId, manArtifactId, manVersion) =
      resolveGroupIdArtifactIdVersionFromManifest(manifest)

    val (fileGroupId, fileArtifactId, fileVersion) =
      extractIdentityFromFilename(artifact.filenameWithNoPath).getOrElse(
        (None, None, None)
      )

    // Per-field priority:
    // groupId:    external POM > pom.properties > embedded pom.xml > manifest > filename
    // artifactId: external POM > pom.properties > embedded pom.xml > filename > manifest
    // version:    external POM > pom.properties > embedded pom.xml > manifest > filename
    val groupId = extGroupId
      .orElse(propsGroupId)
      .orElse(embGroupId)
      .orElse(manGroupId)
      .orElse(fileGroupId)
    val artifactId = extArtifactId
      .orElse(propsArtifactId)
      .orElse(embArtifactId)
      .orElse(fileArtifactId)
      .orElse(manArtifactId)
    val version = extVersion
      .orElse(propsVersion)
      .orElse(embVersion)
      .orElse(manVersion)
      .orElse(fileVersion)

    // Last-resort fallback: Maven pURLs require a namespace (groupId).
    // If no groupId was found from any source but artifactId exists,
    // use artifactId as both groupId and artifactId. This produces
    // e.g. pkg:maven/collections-generic/collections-generic@4.01
    // which is a valid pURL even if the groupId is not the "real" one.
    // Better to have a lookupable pURL than none at all.
    val finalGroupId = groupId.orElse(artifactId)

    (finalGroupId, artifactId, version)
  }

  /** Derive a groupId from a package-path-style manifest header value.
    *
    * Many JAR manifests store Java package paths in headers like
    * Bundle-SymbolicName, Extension-Name, Implementation-Title, etc. These
    * often follow the convention `groupId.artifactId` (e.g.
    * `org.apache.commons.lang`), so the groupId can be derived by taking all
    * segments except the last.
    *
    * Handles:
    *   - `org.apache.commons.codec.*` → `org.apache.commons` (strip `.*`)
    *   - `org.apache.commons.lang` → `org.apache.commons`
    *   - `collections-generic` → `None` (no dots, can't derive groupId)
    *   - `.*` → `None` (only wildcard, nothing useful)
    */
  private def groupIdFromPackagePath(value: String): Option[String] = {
    val cleaned = value.stripSuffix(".*").trim
    val parts = cleaned.split("\\.")
    if (parts.length > 1) {
      val groupId = parts.init.mkString(".")
      if (groupId.nonEmpty) Some(groupId) else None
    } else None
  }

  /** Extract individual groupId, artifactId, and version from MANIFEST headers.
    *
    * Returns a tuple of individual Option[String] values WITHOUT a gate on
    * artifactId. This allows field-level merge in
    * `resolveGroupIdArtifactIdVersion` to use the manifest's groupId and
    * version even when the manifest has no artifactId headers (e.g., manifest
    * with only Implementation-Vendor-Id and Implementation-Version).
    *
    * groupId is resolved from multiple manifest headers in priority order:
    *   1. Implementation-Vendor-Id (most specific, Maven-native) 2.
    *      Bundle-SymbolicName (OSGi, derive parent path) 3.
    *      Automatic-Module-Name (Java module, derive parent path) 4.
    *      Extension-Name (Java extension, derive parent path) 5.
    *      Implementation-Title (if it looks like a package path) 6. Package
    *      (Java package, derive parent path)
    *
    * All empty-string values are filtered to None so they don't block the
    * orElse chain.
    */
  private def resolveGroupIdArtifactIdVersionFromManifest(
      manifest: TreeMap[String, TreeSet[StringOrPair]]
  ): (Option[String], Option[String], Option[String]) = {
    // Helper: read a manifest header value, filtering empty strings.
    // Java's Manifest parser stores "Key: " (trailing space, no value) as "",
    // which must be treated as missing to avoid PurlException and to
    // prevent Some("") from blocking the orElse chain.
    def header(key: String): Option[String] =
      manifest.get(key).flatMap(_.headOption).map(_.value).filter(_.nonEmpty)

    val bundleSymOpt = header("bundle-symbolicname")
    val bundleVerOpt = header("bundle-version")
    val bundleNameOpt = header("bundle-name")
    val implVendorOpt = header("implementation-vendor-id")
    val implTitleOpt = header("implementation-title")
    val implVerOpt = header("implementation-version")
    val specVerOpt = header("specification-version")
    val extNameOpt = header("extension-name")
    val createdByOpt = header("created-by")
    val autoModuleOpt = header("automatic-module-name")
    val packageOpt = header("package")

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

    // groupId priority chain within manifest:
    //   1. Implementation-Vendor-Id (direct, Maven-native)
    //   2. Bundle-SymbolicName (derive parent path, e.g. org.apache.commons.lang → org.apache.commons)
    //   3. Automatic-Module-Name (derive parent path)
    //   4. Extension-Name (derive parent path, strip .* wildcard)
    //   5. Implementation-Title (derive parent path if it has dots)
    //   6. Package (derive parent path, strip .* wildcard)
    val groupIdOpt = implVendorOpt
      .orElse {
        bundleSymOpt
          .map(_.split(";")(0).trim)
          .flatMap(groupIdFromPackagePath)
      }
      .orElse(autoModuleOpt.flatMap(groupIdFromPackagePath))
      .orElse(extNameOpt.flatMap(groupIdFromPackagePath))
      .orElse(implTitleOpt.flatMap(groupIdFromPackagePath))
      .orElse(packageOpt.flatMap(groupIdFromPackagePath))

    val versionOpt = bundleVerOpt.orElse(implVerOpt).orElse(specVerOpt)

    (groupIdOpt, artifactIdOpt, versionOpt)
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
  def resolveGroupIdArtifactIdVersionFromFilename(
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

  /** Score how well an artifactId matches the filename-derived artifact name.
    * Returns: 3 = exact match (artifactId == filenameArt) 2 = prefix match
    * (artifactId is a prefix of filenameArt, followed by a version separator
    * '-' or '_') 1 = reverse prefix match (filenameArt is a prefix of
    * artifactId, followed by a version separator '-' or '_') 0 = no match
    *
    * This replaces the previous bidirectional `contains` check which was
    * vulnerable to pURL hijacking via short artifactIds (e.g., "commons"
    * matching "commons-collections4"). The separator requirement ensures that
    * "spring" does NOT match "springframework" (no separator after the matched
    * prefix).
    *
    * Both `determinePrimaryGroupIdArtifactIdVersion` and `primaryPomOpt` use
    * this method to ensure consistent matching behavior.
    */
  private def matchScore(artifactId: String, filenameArt: String): Int = {
    if (filenameArt.length < 2) 0
    else if (artifactId == filenameArt) 3
    else if (
      artifactId.length > 2 &&
      filenameArt.startsWith(artifactId) &&
      (filenameArt.length == artifactId.length ||
        filenameArt.charAt(artifactId.length) == '-' ||
        filenameArt.charAt(artifactId.length) == '_')
    ) 2
    else if (
      filenameArt.length > 2 &&
      artifactId.startsWith(filenameArt) &&
      (artifactId.length == filenameArt.length ||
        artifactId.charAt(filenameArt.length) == '-' ||
        artifactId.charAt(filenameArt.length) == '_')
    ) 1
    else 0
  }

  /** Select the primary embedded groupId/artifactId/version that best matches
    * the filename. Returns None if no embedded groupId/artifactId/version
    * matches the filename, so that priority falls through to external POM
    * instead of picking a random dependency's embedded metadata from a fat jar.
    *
    * Matching priority: exact match (score 3) > prefix match (score 2) >
    * reverse prefix match (score 1) > None. Among same-score matches, the
    * longest (most specific) artifactId is preferred.
    */
  private[strategies] def determinePrimaryGroupIdArtifactIdVersion(
      groupIdArtifactIdVersions: Vector[(String, String, String)],
      filenameArt: String
  ): Option[(String, String, String)] = {
    if (filenameArt.length < 2) None
    else {
      val scored = groupIdArtifactIdVersions
        .map { t =>
          (t, matchScore(t._2, filenameArt))
        }
        .filter(_._2 > 0)
      if (scored.isEmpty) None
      else {
        val maxScore = scored.map(_._2).max
        val best = scored.filter(_._2 == maxScore)
        // Among same-score matches, prefer the longest artifactId
        Some(best.maxBy(_._1._2.length)._1)
      }
    }
  }

  // ------------------------------------------------------------------
  // beginProcessing
  // ------------------------------------------------------------------

  /** Detect the Maven classifier from a filename.
    *
    * Used by `beginProcessing(JAR)` to set the classifier for standalone
    * sources/javadoc JARs (claimed by `computeMavenFiles` second pass). Uses
    * lowercased filename for case-insensitive matching. Handles `-javadocs.jar`
    * (plural) as an alias for `-javadoc.jar`.
    */
  private def detectClassifierFromFilename(filename: String): Option[String] = {
    val lower = filename.toLowerCase
    if (lower.endsWith("-sources.jar")) Some("sources")
    else if (lower.endsWith("-javadoc.jar") || lower.endsWith("-javadocs.jar"))
      Some("javadoc")
    else None
  }

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

      val bDate = extractBuildDateFromPom(parsedOpt)

      this.copy(
        pomFile = pomString,
        pomXml = xml,
        parsedPom = parsedOpt,
        buildDate = bDate
      )

    case MavenMarkers.JAR =>
      // Initialize the JAR accumulation container. No archive walks here —
      // all metadata is collected via `accumulateInfo` as children are
      // processed, then applied in `applyAccumulatedAugmentation` after all
      // children are done.
      //
      // groupId/artifactId/version resolution is deferred to
      // `applyAccumulatedAugmentation` because it requires data from
      // children (manifest, embedded pom.properties, etc.) that isn't
      // available until after child processing completes.
      //
      // For standalone sources/javadoc JARs (claimed by computeMavenFiles
      // second pass), the classifier is detected from the filename
      // via detectClassifierFromFilename and stored in currentMarker context.
      this.jarAccumulated = Some(JarAccumulatedState())
      this.currentMarker = Some(MavenMarkers.JAR)
      this

    case MavenMarkers.Sources =>
      // Sources JAR: set up its own dedicated accumulator.
      // accumulateInfo will collect metadata (pom.properties, manifest) from
      // the sources JAR's children. applyAccumulatedAugmentation will resolve
      // groupId/artifactId/version and emit pURL with ?packaging=sources.
      this.sourcesAccumulated = Some(JarAccumulatedState())
      this.currentMarker = Some(MavenMarkers.Sources)
      this

    case MavenMarkers.JavaDocs =>
      // JavaDocs JAR: set up its own dedicated accumulator.
      // accumulateInfo will collect metadata from the javadoc JAR's children.
      // applyAccumulatedAugmentation will emit pURL with ?classifier=javadoc.
      this.javadocAccumulated = Some(JarAccumulatedState())
      this.currentMarker = Some(MavenMarkers.JavaDocs)
      this

    case MavenMarkers.Metadata =>
      val xmlContent = artifact.withStream(Helpers.slurpInputToString(_))
      val parsed = parseMavenMetadata(xmlContent)
      this.copy(
        metadataXmlContent = Some(xmlContent),
        parsedMetadata = parsed
      )
  }

  // ------------------------------------------------------------------
  // getPurls
  // ------------------------------------------------------------------
  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: MavenMarkers
  ): (PurlSet, MavenState) = {
    // For JAR, Sources, and JavaDocs markers, groupId/artifactId/version
    // is not yet resolved at this point in the pipeline. getPurls is called
    // BEFORE children are processed, but resolution depends on data
    // accumulated from children (manifest, pom.properties, etc.).
    // The pURL is instead generated in applyAccumulatedAugmentation after
    // all children have been processed.
    //
    // For non-accumulator markers (POM, Metadata), resolve directly from
    // parsedPom and generate the pURL here.
    currentAccumulator match {
      case Some(_) =>
        // Accumulation in progress — groupId/artifactId/version not yet
        // resolved. pURLs will be created in applyAccumulatedAugmentation.
        PurlSet.empty -> this
      case None =>
        // No accumulator active — resolve from parsedPom directly.
        // This is the POM marker path (and backward-compat for callers
        // that skip beginProcessing).
        val (g, a, v) = parsedPom match {
          case Some(p) =>
            val fallback =
              extractIdentityFromFilename(artifact.filenameWithNoPath)
                .getOrElse((None, None, None))
            (
              p.groupId.orElse(fallback._1),
              p.artifactId.orElse(fallback._2),
              p.version.orElse(fallback._3)
            )
          case None =>
            extractIdentityFromFilename(artifact.filenameWithNoPath)
              .getOrElse((None, None, None))
        }
        val classifier = marker match {
          case MavenMarkers.JAR      => None
          case MavenMarkers.Sources  => Some("sources")
          case MavenMarkers.POM      => Some("pom")
          case MavenMarkers.JavaDocs => Some("javadoc")
          case MavenMarkers.Metadata => None
        }
        (g, a, v) match {
          case (Some(groupId), Some(artId), Some(ver)) =>
            val purlOpt = scala.util.Try {
              PURLHelpers
                .buildPackageURL(
                  Ecosystems.Maven,
                  Some(groupId),
                  artId,
                  ver,
                  classifier
                )
            }.toOption
            purlOpt
              .map(p => PurlSet.single(p))
              .getOrElse(PurlSet.empty) -> this
          case _ => PurlSet.empty -> this
        }
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

    // Manifest data lives in the active accumulator (if any).
    val currentManifest = currentAccumulator
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

    // For markers with accumulators, build jar-structure metadata from
    // the accumulated state (no archive walk). This may be called before
    // children are fully processed, in which case the accumulator may be
    // partially populated. The full metadata is also applied in
    // applyAccumulatedAugmentation after all children are done.
    val jarStructureMeta = marker match {
      case MavenMarkers.JAR =>
        buildJarStructureMetadataFromAccumulated(jarAccumulated)
      case MavenMarkers.Sources =>
        buildJarStructureMetadataFromAccumulated(sourcesAccumulated)
      case MavenMarkers.JavaDocs =>
        buildJarStructureMetadataFromAccumulated(javadocAccumulated)
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
    *   - extractAllEmbeddedGroupIdArtifactIdVersion → handled by
    *     pom.properties/pom.xml checks
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
    // Select the correct accumulator based on currentMarker
    currentAccumulator.foreach { acc =>
      val path = artifact.path()
      val lowerPath = path.toLowerCase

      // Path traversal protection — skip entries that could escape the archive
      if (!path.contains("..")) {
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
        // Extract groupId/artifactId/version coordinates and properties from embedded pom.properties.
        // All groupId/artifactId/version tuples are accumulated; primary selection happens later in
        // applyAccumulatedAugmentation using determinePrimaryGroupIdArtifactIdVersion.
        // DoS guard: values > 1024 chars are skipped as suspicious.
        if (
          lowerPath
            .startsWith("meta-inf/maven/") && lowerPath.endsWith(
            "/pom.properties"
          )
        ) {
          val content = artifact.withStream(Helpers.slurpInputToString(_))
          val parsed = parsePropertiesString(content).filter { case (_, v) =>
            v.length <= 1024
          }
          for {
            g <- parsed.get("groupid")
            a <- parsed.get("artifactid")
            v <- parsed.get("version")
          } {
            acc.embeddedGroupIdArtifactIdVersions =
              acc.embeddedGroupIdArtifactIdVersions :+ (g, a, v)
          }
          acc.embeddedProps = acc.embeddedProps ++ parsed
        }

        // ---- META-INF/maven/*/pom.xml ----
        // Parse embedded POM files. All POMs are stored with their path;
        // primary selection happens later in applyAccumulatedAugmentation.
        if (
          lowerPath
            .startsWith("meta-inf/maven/") && lowerPath.endsWith("/pom.xml")
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
        if (
          lowerPath.startsWith("boot-inf/lib/") && lowerPath.endsWith(".jar")
        ) {
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
        if (
          lowerPath.startsWith("web-inf/lib/") && lowerPath.endsWith(".jar")
        ) {
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
    }
  }

  /** Apply accumulated JAR metadata to the item in the store.
    *
    * This method runs AFTER all children have been processed and
    * `accumulateInfo` has been called for each child. It:
    *
    *   1. Resolves groupId/artifactId/version from the accumulated data
    *      (manifest, pom.properties, embedded POM) using the 5-level priority
    *      chain in `resolveGroupIdArtifactIdVersion`. 2. Creates/updates the
    *      pURL item in the store with an `aliasTo` edge pointing back to the
    *      JAR item. A single pURL can have multiple `aliasTo` entries (e.g., if
    *      the same groupId/artifactId/version appears in multiple JARs). 3.
    *      Updates the JAR item with an `aliasFrom` edge pointing to the pURL
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
    *   the JAR ArtifactWrapper (used for filename heuristics in
    *   groupId/artifactId/version resolution)
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
    currentAccumulator match {
      case None =>
        // No accumulator active — nothing to do
        this
      case Some(acc) =>
        // ---- Step 1: Resolve groupId/artifactId/version from accumulated data ----
        // Select the primary embedded groupId/artifactId/version that matches the archive filename.
        // This prevents a fat JAR's random embedded dependencies from
        // overriding the archive's own identity.
        // Strip the -sources/-javadoc/-javadocs suffix before extraction so
        // that pom.properties matching works for sources/javadoc JARs too.
        val rawFilename = artifact.filenameWithNoPath
        val lowerRaw = rawFilename.toLowerCase
        val strippedFilename =
          if (lowerRaw.endsWith("-sources.jar"))
            rawFilename.substring(0, rawFilename.length - "-sources.jar".length)
          else if (lowerRaw.endsWith("-javadoc.jar"))
            rawFilename.substring(0, rawFilename.length - "-javadoc.jar".length)
          else if (lowerRaw.endsWith("-javadocs.jar"))
            rawFilename.substring(
              0,
              rawFilename.length - "-javadocs.jar".length
            )
          else rawFilename
        // Use extractIdentityFromFilename for consistent artifact name
        // extraction. This correctly splits "guava-33.0.0-jre.jar" into
        // artifactId="guava" (not "guava-33" from takeWhile(_ != '.')).
        // When extractIdentityFromFilename returns None (no version found),
        // fall back to stripping the extension manually.
        val filenameArt = extractIdentityFromFilename(strippedFilename) match {
          case Some((_, Some(art), _)) => art
          case _ =>
            val extIdx = strippedFilename.lastIndexOf('.')
            if (extIdx > 0) strippedFilename.substring(0, extIdx)
            else strippedFilename
        }
        val primaryOpt = determinePrimaryGroupIdArtifactIdVersion(
          acc.embeddedGroupIdArtifactIdVersions,
          filenameArt
        )

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
        // Uses the same matchScore logic as determinePrimaryGroupIdArtifactIdVersion
        // to ensure consistent selection and prevent pURL hijacking via short
        // artifactIds.
        val primaryPomOpt = {
          val scored = acc.embeddedPoms
            .flatMap { case (_, p) =>
              p.artifactId.map(a => (p, matchScore(a, filenameArt)))
            }
            .filter(_._2 > 0)
          if (scored.isEmpty) None
          else {
            val maxScore = scored.map(_._2).max
            val best = scored.filter(_._2 == maxScore)
            // Among same-score matches, prefer the longest artifactId
            Some(best.maxBy(_._1.artifactId.map(_.length).getOrElse(0))._1)
          }
        }

        // Resolve final groupId/artifactId/version via the priority chain:
        //   1. externalPom (parsedPom from external POM file) — HIGHEST
        //   2. embeddedProps (pom.properties)
        //   3. embeddedPom (pom.xml inside JAR)
        //   4. manifest OSGi / standard headers
        //   5. filename heuristics
        // These are local variables — NOT stored on MavenState. They are
        // derived data, computed from the accumulator + parsedPom.
        val (g, a, v) = resolveGroupIdArtifactIdVersion(
          artifact = artifact,
          externalPom = this.parsedPom,
          manifest = acc.manifest,
          embeddedProps = effectiveProps,
          embeddedPom = primaryPomOpt
        )
        this.buildDate = acc.buildDate.orElse(this.buildDate)

        // Determine classifier from currentMarker. For JAR marker, also
        // check the filename for standalone sources/javadoc JARs claimed
        // by the computeMavenFiles second pass.
        val classifier = currentMarker match {
          case Some(MavenMarkers.Sources)  => Some("sources")
          case Some(MavenMarkers.JavaDocs) => Some("javadoc")
          case Some(MavenMarkers.JAR) =>
            detectClassifierFromFilename(artifact.filenameWithNoPath)
          case _ => None
        }

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

        // ---- Step 2: Create/update pURLs and fix backlinks ----
        // For a complete groupId/artifactId/version, create pURL items and
        // establish bidirectional alias edges:
        //   - pURL item gets aliasTo -> JAR item
        //   - JAR item gets aliasFrom -> pURL
        //
        // A single pURL can have multiple aliasTo entries (e.g., if the
        // same pURL appears in multiple JARs, each JAR gets its own
        // aliasTo entry on the shared pURL item).
        //
        // In addition to the canonical pURL (from the primary
        // groupId/artifactId/version), we also emit pURLs for ALL
        // non-primary embedded packages found inside the JAR (e.g.,
        // shaded dependencies from META-INF/maven/*/pom.properties).
        (g, a, v) match {
          case (Some(groupId), Some(artId), Some(ver)) =>
            // Build the canonical pURL string. Split buildPackageURL and
            // toCanonical into separate Try blocks: the constructor rarely
            // throws, but toCanonical() can throw PurlException for malformed
            // pURLs (e.g., maven with null namespace).
            val canonicalPurlStr: Option[String] = scala.util
              .Try {
                PURLHelpers
                  .buildPackageURL(
                    Ecosystems.Maven,
                    Some(groupId),
                    artId,
                    ver,
                    classifier
                  )
              }
              .toOption
              .flatMap(p => scala.util.Try(p.toCanonical()).toOption)

            // Merge canonical pURL metadata into fullMeta so it is written
            // alongside the manifest, pom, and jar-structure metadata.
            val metaWithCanonical = canonicalPurlStr match {
              case Some(purlStr) =>
                Helpers.mergeTreeMaps(
                  fullMeta,
                  TreeMap(
                    MetadataKeyConstants.CANONICAL_PURL ->
                      TreeSet(StringOrPair(purlStr))
                  )
                )
              case None => fullMeta
            }

            canonicalPurlStr match {
              case Some(purl) =>
                // Register the canonical pURL with the store's pURL index
                store.addPurl(purl)

                // WRITE 1: Update JAR item — add aliasFrom -> pURL and merge
                // full metadata (manifest, pom, jar-structure, canonical pURL, etc.).
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
                        if (metaWithCanonical.nonEmpty)
                          withAlias.enhanceWithMetadata(
                            extra = metaWithCanonical,
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
                        if (metaWithCanonical.nonEmpty)
                          base.enhanceWithMetadata(
                            extra = metaWithCanonical,
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
                // with the same pURL), we add another aliasTo entry.
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

                // WRITE 3+: Emit pURLs for ALL non-primary embedded packages.
                // Each embedded pom.properties inside the JAR (e.g., shaded
                // dependencies) gets its own pURL with alias edges.
                // The primary tuple is excluded — it was already written above as
                // the canonical pURL. Duplicates are removed.
                // PurlAliasWriter handles store.addPurl + aliasFrom + aliasTo for
                // each secondary pURL. No metadata is merged (metadata was already
                // written in WRITE 1). The item now exists in the store (from
                // WRITE 1), so PurlAliasWriter's None case won't trigger.
                val secondaryTuples: Vector[(String, String, String)] =
                  acc.embeddedGroupIdArtifactIdVersions.distinct
                    .filter(t => !primaryOpt.contains(t))

                secondaryTuples.foreach { case (sg, sa, sv) =>
                  scala.util
                    .Try {
                      PURLHelpers
                        .buildPackageURL(
                          Ecosystems.Maven,
                          Some(sg),
                          sa,
                          sv,
                          classifier // ← was None: secondary pURLs from
                          // sources/javadoc JARs must include
                          // the classifier (?packaging=sources
                          // or ?classifier=javadoc) per REQ-1
                        )
                    }
                    .toOption
                    .flatMap(p => scala.util.Try(p.toCanonical()).toOption)
                    .foreach(secondaryPurl =>
                      PurlAliasWriter.writeAlias(
                        secondaryPurl,
                        item.identifier,
                        store
                      )
                    )
                }

              case None =>
                // pURL construction failed (e.g. PurlException from
                // malformed namespace/name). Still apply metadata so the
                // JAR item is not left empty.
                if (metaWithCanonical.nonEmpty) {
                  store.write(
                    item.identifier,
                    {
                      case Some(existing) =>
                        Some(
                          existing.enhanceWithMetadata(
                            extra = metaWithCanonical,
                            filenames = Vector.empty,
                            mimeTypes = Vector.empty
                          )
                        )
                      case None =>
                        Some(
                          item.enhanceWithMetadata(
                            extra = metaWithCanonical,
                            filenames = Vector.empty,
                            mimeTypes = Vector.empty
                          )
                        )
                    },
                    _ =>
                      s"accumulated augmentation: metadata only (pURL construction failed)"
                  )
                }
            }

          case _ =>
            // Incomplete groupId/artifactId/version — no pURL generated, but
            // still apply full metadata (manifest, pom, jar-structure, etc.)
            // from accumulated state
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

        // ---- Step 3: Clear the consumed accumulator ----
        // The accumulator has been consumed (groupId/artifactId/version
        // resolved, pURL created, metadata merged). Clear it to prevent
        // double-application. groupId/artifactId/version are local variables
        // — they do not exist on MavenState and need no reset.
        currentMarker.foreach(clearAccumulator)

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
      case MavenMarkers.JAR if currentAccumulator.isDefined =>
        // Accumulator active but groupId/artifactId/version not yet
        // resolved (children not processed). No package tag yet.
        None
      case MavenMarkers.POM =>
        // Resolve from parsedPom for the package tag
        parsedPom.flatMap { p =>
          (p.groupId, p.artifactId) match {
            case (Some(g), Some(a)) =>
              Some(
                PackageTagInfo(
                  name = s"$g:$a",
                  version = p.version,
                  date = buildDate
                )
              )
            case _ => None
          }
        }
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
    case MavenMarkers.JAR | MavenMarkers.Sources | MavenMarkers.JavaDocs =>
      val scopeLabel = marker match {
        case MavenMarkers.JAR      => "Maven/JAR"
        case MavenMarkers.Sources  => "Maven/Sources"
        case MavenMarkers.JavaDocs => "Maven/JavaDocs"
        case _                     => "Maven"
      }
      new ParentScope(augmentationByHash) {
        def scopeFor(): String = item.identifier
        def parentOfParentScope(): Option[ParentScope] = parentScope
        def parentScopeInformation(): String =
          f"$scopeLabel Scope for ${item.identifier}${parentScope match {
              case None     => ""
              case Some(ps) => f" Parent: ${ps.parentScopeInformation()}"
            }}"

        /** Override accumulateInfo to collect archive-structure metadata from
          * child entries. This is called for each child artifact found inside
          * the JAR/Sources/JavaDocs archive during the processing pipeline. The
          * child's path determines what kind of metadata to accumulate.
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
        ): Item = marker match {
          case MavenMarkers.JAR =>
            // Only JAR marker computes builtFrom edges from source files.
            val sources = Helpers.computeAssociatedSource(
              artifact,
              associatedFiles = sourceGitoids
            )
            sources.foldLeft(item) { case (item, source) =>
              item.withConnection(EdgeType.builtFrom, source)
            }
          case _ =>
            // Sources/JavaDocs: no builtFrom edges
            item
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

    // Build directory-to-metadata mapping for maven-metadata.xml files.
    // With full-path keys, metadata files may be at any directory level,
    // so collect all entries whose path ends with "maven-metadata.xml".
    val metadataXmlFiles = byName.collect {
      case (path, artifacts) if path.endsWith("maven-metadata.xml") =>
        artifacts
    }.flatten
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

    // Remove consumed maven-metadata.xml entries from revisedByName.
    // With full-path keys, each metadata file has its own key, so filter
    // by the consumed paths rather than a single hardcoded key.
    val afterMetaFilter = if (consumedMetaPaths.nonEmpty) {
      revisedByName.filterNot { case (k, _) => consumedMetaPaths.contains(k) }
    } else revisedByName

    // ---- Second pass: claim standalone sources/javadoc JARs ----
    // Sources/javadoc JARs that were NOT picked up as companions (because
    // no main JAR exists alongside them) are claimed as primary archives.
    // This ensures standalone sources/javadoc JARs get full Maven
    // processing (accumulator, resolveGroupIdArtifactIdVersion, pURL emission) instead of
    // falling through to GenericFile (which emits 0 pURLs).
    //
    // CRITICAL: Must check mimeType to prevent non-archive files from
    // being processed through the Maven pipeline (security: a text file
    // named foo-sources.jar should NOT be opened as a JAR archive).
    val claimedNames = toProcess.collect { case mtp: MavenToProcess =>
      mtp.jar.filenameWithNoPath
    }.toSet ++ toProcess
      .collect { case mtp: MavenToProcess =>
        mtp.source.toSeq.map(_.filenameWithNoPath) ++
          mtp.javaDoc.toSeq.map(_.filenameWithNoPath)
      }
      .flatten
      .toSet

    val standaloneClassifierJars = afterMetaFilter.toVector.flatMap {
      case (name, artifacts) if !claimedNames.contains(name) =>
        val lower = name.toLowerCase
        val isSources = lower.endsWith("-sources.jar")
        val isJavadoc = lower.endsWith("-javadoc.jar") ||
          lower.endsWith("-javadocs.jar")
        if (
          (isSources || isJavadoc) &&
          artifacts.exists(_.mimeType.contains("application/java-archive"))
        ) {
          // Look for companion POM — strip -sources.jar / -javadoc.jar /
          // -javadocs.jar / .jar and append .pom. Maven shares the POM
          // between the main JAR and sources/javadoc JARs, so even when
          // no main JAR exists, the POM may still be present.
          val baseName = name
            .stripSuffix("-sources.jar")
            .stripSuffix("-javadoc.jar")
            .stripSuffix("-javadocs.jar")
            .stripSuffix(".jar")
          val pomName = baseName + ".pom"
          val companionPom =
            afterMetaFilter.get(pomName).toVector.flatten.headOption
          artifacts.map(a => MavenToProcess(a, companionPom, None, None, None))
        } else Vector.empty
      case _ => Vector.empty
    }

    // Remove standalone sources/javadoc JARs AND their companion POMs
    // from byName so other strategies (e.g., GenericFile) don't pick them up.
    val standaloneNames = standaloneClassifierJars
      .map(_.jar.filenameWithNoPath)
      .toSet
    val standalonePomNames = standaloneClassifierJars
      .flatMap(_.pom.toSeq.map(_.filenameWithNoPath))
      .toSet
    val finalRevisedByName = afterMetaFilter.filterNot { case (name, _) =>
      standaloneNames.contains(name) || standalonePomNames.contains(name)
    }

    // Remove standalone JAR UUIDs and their companion POM UUIDs from byUUID
    val standaloneUUIDs = standaloneClassifierJars.flatMap { mtp =>
      Vector(mtp.jar.uuid) ++ mtp.pom.toSeq.map(_.uuid)
    }.toSet
    val finalRevisedByUUID = revisedByUUID.filterNot { case (uuid, _) =>
      standaloneUUIDs.contains(uuid)
    }

    val finalToProcess = toProcess ++ standaloneClassifierJars

    (finalToProcess, finalRevisedByUUID, finalRevisedByName, "Maven")
  }
}
