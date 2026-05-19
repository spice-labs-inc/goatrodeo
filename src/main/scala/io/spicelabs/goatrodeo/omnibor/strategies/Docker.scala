package io.spicelabs.goatrodeo.omnibor.strategies

import com.github.packageurl.PackageURL
import com.github.packageurl.PackageURLBuilder
import com.typesafe.scalalogging.Logger
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
import io.spicelabs.goatrodeo.util.Gitoid
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

/** State maintained during Docker image processing.
  *
  * Tracks layer-to-GitOID mappings for establishing "contains" relationships
  * between config and layers.
  *
  * @param layerToGitoidMapping
  *   map of layer SHA256 hashes to their GitOIDs
  */
case class DockerState(
    layerToGitoidMapping: Map[String, Gitoid],
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

  private def computePurls(info: ManifestInfo): Vector[PackageURL] = {
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
        case Nil                    => ??? /// this should never happen
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
      val withName = PackageURLBuilder
        .aPackageURL()
        .withType("docker")
        .withName(path)

      val withVersion = version match {
        case Some(v) => withName.withVersion(v)
        case None    => withName
      }

      val withNamespace = namespace match {
        case Some(ns) => withVersion.withNamespace(ns)
        case None     => withVersion
      }

      withNamespace.build()
    }

    purls.toVector
  }

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: DockerMarkers
  ): (Vector[PackageURL], DockerState) = marker match {
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
      TreeMap(
        "docker_config" -> TreeSet(
          StringOrPair(pretty(render(info.configJson)))
        ),
        "docker_manifest" -> TreeSet(
          StringOrPair(pretty(render(info.manifestConfig)))
        )
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
        this.layerToGitoidMapping + (hash -> Gitoid(updatedItem.identifierString))
      )

    case DockerMarkers.Config(info) =>
      store.write(
        parentScope.scopeFor(),
        {
          case Some(parentItem) =>
            val thePurls = computePurls(info)
            thePurls.foreach(store.addPurl(_))
            Some(
              parentItem
                .enhanceWithMetadata(
                  mimeTypes = Vector("application/vnd.oci.image"),
                  extra = TreeMap(
                    "docker_config" -> TreeSet(
                      StringOrPair(pretty(render(info.configJson)))
                    ),
                    "docker_manifest" -> TreeSet(
                      StringOrPair(pretty(render(info.manifestConfig)))
                    )
                  )
                )
                .enhanceItemWithPurls(thePurls)
                .updateBackReferences(store, parentScope)
            )
          case None => None
        },
        _.identifierString
      )
      val updatedItem = item.enhanceWithMetadata(mimeTypes =
        Vector(
          "application/vnd.oci.image.config.v1+json",
          "application/vnd.oci.image.manifest.v1+json"
        )
      )

      // for config, make sure it contains all the layers
      // and the layers will have a containedBy reference
      // to the config
      val itemWithConnections = info.layers.foldLeft(updatedItem) {
        case (item, layer) =>
          layerToGitoidMapping.get(layer) match {
            case None => item
            case Some(gitoid) =>
              item.copy(connections =
                item.connections + (EdgeType.contains -> gitoid())
              )
          }
      }

      itemWithConnections -> this

    case _ => (item, this)
  }

  override def postChildProcessing(
      kids: Option[Vector[Gitoid]],
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
            layers
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
    layers: List[String]
)
