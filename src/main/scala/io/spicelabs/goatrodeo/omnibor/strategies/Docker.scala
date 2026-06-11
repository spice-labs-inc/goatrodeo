package io.spicelabs.goatrodeo.omnibor.strategies

import com.typesafe.scalalogging.Logger
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
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.PURLHelpers
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?
import org.json4s.*
import org.json4s.native.JsonMethods.*

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** Markers for different Docker/OCI image component types.
  *
  * Each marker identifies the type of artifact being processed within a Docker
  * image (manifest, layers, config).
  */
enum DockerMarkers extends ProcessingMarker {

  /** The manifest.json file listing all image configurations. */
  case Manifest

  /** A layer tarball containing filesystem changes.
    *
    * @param hash
    *   the SHA256 hash of the layer
    */
  case Layer(hash: String)

  /** The configuration JSON for an image.
    *
    * @param info
    *   the parsed manifest information
    */
  case Config(info: ManifestInfo)
}

/** Extracts structured metadata from Docker/OCI image config and manifest JSON.
  *
  * Parses the OCI image config JSON and the Docker manifest entry JSON to
  * produce a searchable, structured TreeMap of metadata keys rather than
  * dumping raw JSON blobs.
  */
object DockerMetadataExtractor {

  private val adHoc = MetadataKeyConstants.adHoc("docker")

  /** Extract a string value from a JValue path.
    */
  private def stringAt(jv: JValue, path: String*): Option[String] = {
    val result = path.foldLeft[Option[JValue]](Some(jv)) {
      case (Some(cur), key) =>
        val next = cur \ key
        if (next == JNothing) None else Some(next)
      case _ => None
    }
    result match {
      case Some(JString(s)) if s.nonEmpty => Some(s)
      case _ => None
    }
  }

  /** Extract an array of strings from a JValue path.
    */
  private def stringArrayAt(jv: JValue, path: String*): Vector[String] = {
    val nested = path.foldLeft[Option[JValue]](Some(jv)) {
      case (Some(cur), key) =>
        val next = cur \ key
        if (next == JNothing) None else Some(next)
      case _ => None
    }
    nested match {
      case Some(JArray(arr)) => arr.collect { case JString(s) => s }.toVector
      case _ => Vector.empty
    }
  }

  /** Extract an integer count from a JValue path.
    */
  private def countAt(jv: JValue, path: String*): Option[Int] = {
    val result = path.foldLeft[Option[JValue]](Some(jv)) {
      case (Some(cur), key) =>
        val next = cur \ key
        if (next == JNothing) None else Some(next)
      case _ => None
    }
    result match {
      case Some(JArray(arr)) => Some(arr.length)
      case _ => None
    }
  }

  /** Heuristic to detect an OCI image manifest blob.
    */
  private[strategies] def isOciManifestBlob(json: JValue): Boolean = {
    val hasSchema = (json \ "schemaVersion") match {
      case JInt(_) | JLong(_) => true
      case _ => false
    }
    val hasMediaType = (json \ "mediaType") match {
      case JString(s) =>
        s.contains("vnd.oci.image.manifest") || s.contains(
          "vnd.docker.distribution.manifest"
        )
      case _ => false
    }
    val hasConfigDigest = (json \ "config" \ "digest") match {
      case JString(d) => d.startsWith("sha256:")
      case _ => false
    }
    hasSchema && hasMediaType && hasConfigDigest
  }

  /** Parse all environment variables from config JSON as KEY -> value pairs.
    */
  private def parseEnvVars(configJson: JValue): Map[String, String] = {
    (configJson \ "config" \ "Env") match {
      case JArray(arr) =>
        arr.collect {
          case JString(s) if s.nonEmpty =>
            s.split("=", 2).toList match {
              case key :: value :: Nil => key -> value
              case key :: Nil => key -> ""
              case _ => s -> ""
            }
        }.toMap
      case _ => Map.empty
    }
  }

  /** Extract labels as a Map from config JSON.
    */
  private def extractLabels(configJson: JValue): Map[String, String] = {
    (configJson \ "config" \ "Labels") match {
      case JObject(fields) =>
        fields.collect { case (k, JString(v)) if v.nonEmpty => k -> v }.toMap
      case _ => Map.empty
    }
  }

  /** Compute total layer size from manifest JSON.
    */
  private def computeSize(manifestConfig: JValue): Option[Long] = {
    (manifestConfig \ "LayerSources") match {
      case JObject(sources) =>
        val total = sources.map(_._2).flatMap {
          case JObject(fields) =>
            fields.collectFirst {
              case ("size", JInt(n))  => n.toLong
              case ("size", JLong(n)) => n.toLong
            }
          case _ => None
        }.sum
        if (total > 0) Some(total) else None
      case _ => None
    }
  }

  /** Extract the last N non-empty history entries.
    */
  private def extractHistory(configJson: JValue, n: Int = 3): Vector[String] = {
    (configJson \ "history") match {
      case JArray(arr) =>
        val all = arr.map {
          case JObject(fields) =>
            val hasEmpty = fields.exists {
              case ("empty_layer", JBool(true)) => true
              case _ => false
            }
            val createdBy =
              fields.collectFirst { case ("created_by", JString(s)) => s }
            (hasEmpty, createdBy)
          case other => (true, None)
        }
        all.collect { case (false, Some(s)) => s }.toVector.takeRight(n)
      case _ => Vector.empty
    }
  }

  /** Build an optional key-value pair for TreeMap insertion.
    */
  private def maybePair(
      key: String,
      value: Option[String]
  ): Option[(String, TreeSet[StringOrPair])] = {
    value.map(v => key -> TreeSet(StringOrPair(v)))
  }

  /** Normalize known OCI / label-schema label keys into Goat Rodeo metadata keys.
    *
    * OCI keys take precedence over label-schema keys. If both are present,
    * the OCI value wins.
    */
  private def normalizeLabels(
      labels: Map[String, String]
  ): (TreeMap[String, TreeSet[StringOrPair]], Set[String]) = {
    var metadata = TreeMap[String, TreeSet[StringOrPair]]()
    var used = Set[String]()

    def addOCI(key: String, grKey: String): Unit = {
      labels.get(key).foreach { value =>
        metadata = metadata + (adHoc(grKey) -> TreeSet(StringOrPair(value)))
        used = used + key
      }
    }

    def addFallback(
        ociKey: String,
        fallbackKey: String,
        grKey: String
    ): Unit = {
      val valueOpt = labels.get(ociKey).orElse(labels.get(fallbackKey))
      valueOpt.foreach { value =>
        metadata = metadata + (adHoc(grKey) -> TreeSet(StringOrPair(value)))
        used = used + ociKey + fallbackKey
      }
    }

    // OCI annotations (primary)
    addOCI("org.opencontainers.image.source", "Source")
    addOCI("org.opencontainers.image.revision", "Revision")
    addOCI("org.opencontainers.image.licenses", "License")
    addOCI("org.opencontainers.image.title", "Title")
    addOCI("org.opencontainers.image.description", "Description")
    addOCI("org.opencontainers.image.url", "Url")
    addOCI("org.opencontainers.image.vendor", "Vendor")
    addOCI("org.opencontainers.image.version", "ImageLabelVersion")
    addOCI("org.opencontainers.image.base.name", "BaseImageRef")
    addOCI("org.opencontainers.image.base.digest", "BaseImageDigest")
    addOCI("org.opencontainers.image.created", "LabelCreated")

    // Label-schema fallbacks (only if OCI not present)
    addFallback(
      "org.opencontainers.image.source",
      "org.label-schema.vcs-url",
      "Source"
    )
    addFallback(
      "org.opencontainers.image.revision",
      "org.label-schema.vcs-ref",
      "Revision"
    )
    addFallback(
      "org.opencontainers.image.title",
      "org.label-schema.name",
      "Title"
    )
    addFallback(
      "org.opencontainers.image.description",
      "org.label-schema.description",
      "Description"
    )
    addFallback(
      "org.opencontainers.image.url",
      "org.label-schema.url",
      "Url"
    )
    addFallback(
      "org.opencontainers.image.vendor",
      "org.label-schema.vendor",
      "Vendor"
    )
    addFallback(
      "org.opencontainers.image.version",
      "org.label-schema.version",
      "ImageLabelVersion"
    )
    addFallback(
      "org.opencontainers.image.created",
      "org.label-schema.build-date",
      "LabelCreated"
    )
    addFallback(
      "org.opencontainers.image.created",
      "org.label-schema.build-date",
      "BuildDate"
    )

    (metadata, used)
  }

  /** Extract full structured metadata from config and manifest JSONs.
    *
    * @param configJson
    *   the parsed OCI image configuration JSON
    * @param manifestConfig
    *   the parsed Docker manifest.json entry for this image
    * @param ociManifest
    *   optional OCI image manifest JSON for this image
    * @return
    *   a TreeMap of ad-hoc metadata keys to their values
    */
  def extractMetadata(
      configJson: JValue,
      manifestConfig: JValue,
      ociManifest: Option[JValue] = None
  ): TreeMap[String, TreeSet[StringOrPair]] = {

    var metadata = TreeMap[String, TreeSet[StringOrPair]]()

    // Platform: os/architecture/variant
    val osOpt = stringAt(configJson, "os")
    val archOpt = stringAt(configJson, "architecture")
    val variantOpt = stringAt(configJson, "variant")
    val platformParts = osOpt.toList ++ archOpt.toList ++ variantOpt.toList
    if (platformParts.nonEmpty) {
      metadata = metadata + (
        adHoc("Platform") -> TreeSet(StringOrPair(platformParts.mkString("/")))
      )
    }

    // Core config fields
    metadata = metadata +? maybePair(adHoc("Created"), stringAt(configJson, "created"))
    metadata = metadata +? maybePair(adHoc("Author"), stringAt(configJson, "author"))
    metadata = metadata +? maybePair(
      adHoc("WorkingDir"),
      stringAt(configJson, "config", "WorkingDir")
    )
    metadata = metadata +? maybePair(
      adHoc("User"),
      stringAt(configJson, "config", "User")
    )

    // Entrypoint and Command
    val entrypointParts = stringArrayAt(configJson, "config", "Entrypoint")
    if (entrypointParts.nonEmpty) {
      metadata = metadata + (
        adHoc("Entrypoint") -> TreeSet(
          StringOrPair(entrypointParts.mkString(" "))
        )
      )
    }

    val cmdParts = stringArrayAt(configJson, "config", "Cmd")
    if (cmdParts.nonEmpty) {
      metadata =
        metadata + (adHoc("Cmd") -> TreeSet(StringOrPair(cmdParts.mkString(" "))))
    }

    // Env count (avoids leaking secrets while preserving cardinality)
    countAt(configJson, "config", "Env").foreach { n =>
      metadata = metadata + (
        adHoc("EnvCount") -> TreeSet(StringOrPair(n.toString))
      )
    }

    // Layer count
    countAt(configJson, "rootfs", "diff_ids").foreach { n =>
      metadata = metadata + (
        adHoc("LayerCount") -> TreeSet(StringOrPair(n.toString))
      )
    }

    // History (last 3 non-empty build commands)
    val historyEntries = extractHistory(configJson, 3)
    if (historyEntries.nonEmpty) {
      metadata = metadata + (
        adHoc("History") -> TreeSet.from(historyEntries.map(StringOrPair(_)))
      )
    }

    // Total size from manifest LayerSources
    computeSize(manifestConfig).foreach { size =>
      metadata = metadata + (
        adHoc("Size") -> TreeSet(StringOrPair(size.toString))
      )
    }

    // RepoDigest (immutable reference)
    (manifestConfig \ "RepoDigests") match {
      case JArray(arr) =>
        arr.collectFirst { case JString(s) if s.nonEmpty => s }.foreach { digest =>
          metadata = metadata + (
            adHoc("RepoDigest") -> TreeSet(StringOrPair(digest))
          )
        }
      case _ =>
    }

    // OCI manifest metadata (schemaVersion, config mediaType)
    ociManifest.foreach { oci =>
      metadata = metadata +? maybePair(
        adHoc("ConfigMediaType"),
        stringAt(oci, "config", "mediaType")
      )
      metadata = metadata +? maybePair(
        adHoc("SchemaVersion"),
        stringAt(oci, "schemaVersion")
      )
    }

    // Env variables: capture all present in the image config
    parseEnvVars(configJson).foreach { case (key, value) =>
      metadata = metadata + (
        adHoc(s"Env:$key") -> TreeSet(StringOrPair(value))
      )
    }

    // Raw JSON retention for audit/completeness
    val compactConfig = compact(render(configJson))
    if (compactConfig.nonEmpty) {
      metadata = metadata + (
        adHoc("ConfigJson") -> TreeSet(StringOrPair(compactConfig))
      )
    }
    val compactManifest = compact(render(manifestConfig))
    if (compactManifest.nonEmpty) {
      metadata = metadata + (
        adHoc("ManifestJson") -> TreeSet(StringOrPair(compactManifest))
      )
    }

    // Labels: Tier 1 (normalized OCI / label-schema)
    val allLabels = extractLabels(configJson)
    val (normalizedMeta, usedKeys) = normalizeLabels(allLabels)
    metadata = metadata ++ normalizedMeta

    // Labels: Tier 2 (remaining labels preserved verbatim)
    for ((k, v) <- allLabels if !usedKeys.contains(k)) {
      metadata = metadata + (
        adHoc(s"Label:$k") -> TreeSet(StringOrPair(v))
      )
    }

    metadata
  }
}

/** State maintained during Docker image processing.
  *
  * Tracks layer-to-GitOID mappings for establishing "contains" relationships
  * between config and layers.
  *
  * @param layerToGitoidMapping
  *   map of layer SHA256 hashes to their GitOIDs
  */
case class DockerState(
    layerToGitoidMapping: Map[String, String],
    configInfo: Option[ManifestInfo] = None
) extends ProcessingState[DockerMarkers, DockerState] {

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: DockerMarkers
  ): DockerState = marker match {
    case DockerMarkers.Config(info) =>
      this.copy(configInfo = Some(info))
    case _ => this
  }

  private def computePurls(info: ManifestInfo): Vector[String] = {
    val purls = for {
      // get "RepoTags" which should be an Array of tags
      case JArray(tags) <- info.manifestConfig \ "RepoTags"

      // for each of the found tags
      case JString(tag) <- tags
    } yield {
      val (base, version) = tag.lastIndexOf(":") match {
        case x if x > 0 => (tag.substring(0, x), Some(tag.substring(x + 1)))
        case _          => (tag, None)
      }

      val (namespace, path) = base.split("/").toList match {
        case Nil                    => (None, base)
        case blob :: Nil            => (None, blob)
        case path :: subPath :: Nil => (None, f"${path}/${subPath}")
        case namespace :: pathAndSubpath =>
          (
            Some(namespace),
            pathAndSubpath.reduceLeft { case (a, b) =>
              a + "/" + b
            }
          )
      }

      // construct a Docker Package URL based on the pURL examples
      // https://github.com/package-url/purl-spec?tab=readme-ov-file#some-purl-examples
      PURLHelpers
        .purl(
          `type` = "docker",
          name = path,
          namespace = namespace.orNull,
          version = version.orNull
        )
        .toCanonical()
        .nn
    }

    purls.toVector
  }

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: DockerMarkers
  ): (Vector[String], DockerState) = marker match {
    case DockerMarkers.Config(info) =>
      val purls = computePurls(info)

      // purls.toVector -> this
      Vector.empty -> this
    case _ => Vector.empty -> this
  }

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: DockerMarkers
  ): (TreeMap[String, TreeSet[StringOrPair]], DockerState) = marker match {
    case DockerMarkers.Config(info) =>
      DockerMetadataExtractor.extractMetadata(
        info.configJson,
        info.manifestConfig,
        info.ociManifest
      ) -> this
    case _ => (TreeMap(), this)
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: DockerMarkers,
      parentScope: ParentScope,
      store: Storage
  ): (Item, DockerState) = marker match {
    case DockerMarkers.Layer(hash) =>
      val updatedItem = item.enhanceWithMetadata(mimeTypes =
        Vector("application/vnd.oci.image.layer.v1.tar")
      )

      // Associate the item's hash with the item's gitoid/identifier
      updatedItem -> this.copy(layerToGitoidMapping =
        this.layerToGitoidMapping + (hash -> updatedItem.identifier)
      )

    case DockerMarkers.Config(info) =>
      val thePurls = computePurls(info)
      thePurls.foreach(store.addPurl(_))

      // Enhance the config item directly with mime types and pURL connections.
      // ToProcess.process backref logic will create pURL items that alias to
      // this config item, giving each Docker image its own distinct metadata
      // namespace rather than sharing a single parent item.
      val updatedItem = item
        .enhanceWithMetadata(mimeTypes =
          Vector(
            "application/vnd.oci.image.config.v1+json",
            "application/vnd.oci.image.manifest.v1+json"
          )
        )
        .enhanceItemWithPurls(thePurls)

      // for config, make sure it contains all the layers
      // and the layers will have a containedBy reference
      // to the config
      val itemWithConnections = info.layers.foldLeft(updatedItem) {
        case (item, layer) =>
          layerToGitoidMapping.get(layer) match {
            case None => item
            case Some(gitoid) =>
              item.copy(connections =
                item.connections + (EdgeType.contains -> gitoid)
              )
          }
      }

      itemWithConnections -> this

    case _ => (item, this)
  }

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: DockerMarkers
  ): DockerState = this

  /** Generate per-package tag info for Docker images. Only Config marker
    * produces a tag.
    */
  override def maybePackageTag(marker: DockerMarkers): Option[PackageTagInfo] =
    marker match {
      case DockerMarkers.Config(info) =>
        // Extract name and version from RepoTags
        val repoTags = for {
          case JArray(tags) <- info.manifestConfig \ "RepoTags"
          case JString(tag) <- tags
        } yield tag

        repoTags.headOption.flatMap { tag =>
          // For Docker, the tag includes both repository and version (e.g., "bigtent:2025_03_22")
          // We extract the version portion but keep the full repository:tag as the name
          val versionOpt = tag.lastIndexOf(":") match {
            case x if x > 0 => Some(tag.substring(x + 1))
            case _          => None
          }

          Some(
            PackageTagInfo(
              name = tag, // Use full repository:tag format
              version = versionOpt,
              date =
                None // Docker config doesn't consistently have created date in manifest.json
            )
          )
        }
      case _ => None
    }

}

/** A Docker/OCI image to process.
  *
  * Represents a complete Docker image including the manifest, config, and all
  * layer tarballs.
  *
  * @param manifest
  *   the manifest.json artifact
  * @param config
  *   list of configuration files with their parsed info
  * @param layers
  *   map of layer hashes to their artifacts
  */
final case class DockerToProcess(
    manifest: ArtifactWrapper,
    config: List[ManifestInfo],
    layers: Map[String, ArtifactWrapper]
) extends ToProcess {
  type MarkerType = DockerMarkers
  type StateType = DockerState
  override def main: String =
    f"${manifest.path()}${config.foldLeft(" ") { case (s, m) =>
        f"${s}${m.configHash} "
      }}"

  override def mimeType: Set[String] = manifest.mimeType

  override def itemCnt: Int = 1 + config.size + layers.size

  override def markSuccessfulCompletion(): Unit = {
    manifest.finished()
    layers.foreach { case (_, wrapper) => wrapper.finished() }
    config.foreach(mi => mi.configFile.finished())
  }

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) = (layers.values
    .map(v => v -> DockerMarkers.Layer(v.filenameWithNoPath))
    .toList ::: List(manifest -> DockerMarkers.Manifest) ::: config.map(m =>
    m.configFile -> DockerMarkers.Config(m)
  )) -> DockerState(
    Map()
  )

}

/** Factory methods for creating Docker image processing strategies. */
object DockerToProcess {

  /** MIME type for JSON files. */
  val jsonMimeType = "application/json"
  private val logger: Logger = Logger(getClass())

  /** Identify and group Docker image components from a collection of files.
    *
    * Looks for manifest.json and associated config and layer files to construct
    * a complete Docker image for processing.
    *
    * @param byUUID
    *   artifacts indexed by UUID
    * @param byName
    *   artifacts indexed by filename
    * @return
    *   tuple of (ToProcess items, remaining UUID map, remaining name map,
    *   strategy name)
    */
  def computeDockerFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {

    val maybeManifest = byName.get("manifest.json")

    val configInfo =
      for {
        manifestVec <- maybeManifest if manifestVec.length == 1 &&
          manifestVec(0).mimeType.exists(_.startsWith(jsonMimeType))

        manifest = manifestVec(0)

        // parse the manifest
        manifestJson <- manifest.withStream(stream => parseOpt(stream))

        // get the elements of the manifest array
        manifestElements <- manifestJson match {
          case JArray(arr) => Some(arr)
          case _           => None
        }
      } yield {
        // Scan for OCI manifest blobs keyed by config digest
        val ociManifests: Map[String, JValue] = {
          val candidates = for {
            (name, artifacts) <- byName
            if artifacts.length == 1 && artifacts(0).mimeType.exists(
              _.startsWith(jsonMimeType)
            )
            if name.startsWith("blobs/sha256/")
            json <- artifacts(0).withStream(stream => parseOpt(stream)).toList
            if DockerMetadataExtractor.isOciManifestBlob(json)
          } yield json

          candidates.flatMap { json =>
            (json \ "config" \ "digest") match {
              case JString(digest) if digest.startsWith("sha256:") =>
                List(digest.substring(7) -> json)
              case _ => Nil
            }
          }.toMap
        }

        for {
          manifestConfig <- manifestElements
          configHash <- (manifestConfig \ "Config") match {
            case JString(s) if s.startsWith("blobs/sha256/") =>
              List(s.substring(13))
            case _ => Nil
          }
          configFile <- byName.get(configHash) match {
            case Some(a)
                if a.length == 1 && a(0).mimeType.exists(
                  _.startsWith(jsonMimeType)
                ) =>
              List(a(0))
            case _ => Nil
          }
          configJson <- configFile.withStream(stream => parseOpt(stream))
          // get the layers

        } yield {
          val layers = for {

            case JArray(layers) <- manifestConfig \ "Layers"
            case JString(shaLayer) <- layers
            layer = shaLayer.substring(13)
            artifactWrapper <- byName.get(layer) match {
              case Some(ar) if ar.length == 1 => ar.headOption.toList
              case _                          => Nil
            }
          } yield layer

          ManifestInfo(
            manifest,
            manifestConfig,
            configHash,
            configFile,
            configJson,
            layers,
            ociManifests.get(configHash)
          )
        }
      }

    configInfo match {
      case Some(item :: rest) =>
        val manifestArtifactWrapper = item.manifest
        val all = item :: rest

        // get the layers
        val layers = Map((for {
          item <- all
          layer <- item.layers
          artifactWrapper <- byName.get(layer) match {
            case Some(ar) if ar.length == 1 => ar.headOption.toList
            case _                          => Nil
          }
        } yield layer -> artifactWrapper)*)

        // remove the layer names and
        val (uuidSansLayer, nameSansLayer) = layers.foldLeft(
          (byUUID - item.manifest.uuid, byName - "manifest.json")
        ) { case ((uuid, name), (layerName, layerArtifact)) =>
          (uuid - layerArtifact.uuid) -> (name - layerName)
        }

        val (finalUuid, finalName) =
          all.foldLeft((uuidSansLayer, nameSansLayer)) {
            case ((uuid, name), manifestInfo) =>
              (uuid - manifestInfo.configFile.uuid) -> (name - manifestInfo.configHash)
          }

        (
          Vector(DockerToProcess(item.manifest, all, layers)),
          finalUuid,
          finalName,
          "Docker"
        )

      // didn't find anything, just return
      case _ => (Vector.empty, byUUID, byName, "Docker")
    }

  }
}

/** Parsed information about a Docker image manifest entry.
  *
  * @param manifest
  *   the manifest.json artifact
  * @param manifestConfig
  *   the JSON for this manifest entry
  * @param configHash
  *   the SHA256 hash of the config file
  * @param configFile
  *   the config JSON artifact
  * @param configJson
  *   the parsed config JSON
  * @param layers
  *   list of layer SHA256 hashes
  */
case class ManifestInfo(
    manifest: ArtifactWrapper,
    manifestConfig: JValue,
    configHash: String,
    configFile: ArtifactWrapper,
    configJson: JValue,
    layers: List[String],
    ociManifest: Option[JValue] = None
)
