package goatrodeo.omnibor.strategies

import goatrodeo.util.ArtifactWrapper
import goatrodeo.omnibor.ProcessingState
import com.typesafe.scalalogging.Logger
import goatrodeo.omnibor.ToProcess
import goatrodeo.omnibor.ToProcess.ByUUID
import goatrodeo.omnibor.ToProcess.ByName
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.JsonAST._
import goatrodeo.omnibor.ProcessingMarker
import goatrodeo.omnibor.Item
import scala.collection.immutable.TreeSet
import goatrodeo.omnibor.Storage
import goatrodeo.util.GitOID
import scala.collection.immutable.TreeMap
import com.github.packageurl.PackageURL
import goatrodeo.omnibor.StringOrPair
import com.github.packageurl.PackageURLBuilder
import goatrodeo.omnibor.EdgeType

enum DockerMarkers extends ProcessingMarker {
  case Manifest
  case Layer(hash: String)
  case Config(info: ManifestInfo)
}

case class DockerState(
    layerToGitoidMapping: Map[String, String]
) extends ProcessingState[DockerMarkers, DockerState] {

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: DockerMarkers
  ): DockerState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: DockerMarkers
  ): (Vector[PackageURL], DockerState) = marker match {
    case DockerMarkers.Config(info) =>
      val tags = for {
        case JArray(tags) <- info.manifestConfig \ "RepoTags"
        case JString(tag) <- tags
      } yield {
        tag.lastIndexOf(":") match {
          case x if x > 0 => tag.substring(0, x) -> tag.substring(x + 1)
        }
      }

      val tagBase = Set(tags.map(t => t._1)*)

      val allTags: Seq[(String, String)] = (tags ::: Nil)

      val purls = allTags.map { case (name, label) =>
        PackageURLBuilder
          .aPackageURL()
          .withType("docker")
          .withName(name)
          .withVersion(label)
          .build()
      }
      purls.toVector -> this
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
      marker: DockerMarkers
  ): (Item, DockerState) = marker match {
    case DockerMarkers.Layer(hash) =>
      // Associate the item's hash with the item's gitoid/identifier
      item -> this.copy(layerToGitoidMapping =
        this.layerToGitoidMapping + (hash -> item.identifier)
      )

    case DockerMarkers.Config(info) =>
      // for config, make sure it contains all the layers
      // and the layers will have a containedBy reference
      // to the config
      val itemWithConnections = info.layers.foldLeft(item) {
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

}

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

  override def mimeType: String = manifest.mimeType

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

object DockerToProcess {
  val jsonMimeType = "application/json"
  private val logger: Logger = Logger(getClass())
  def computeDockerFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {

    val maybeManifest = byName.get("manifest.json")

    val configInfo =
      for {
        manifestVec <- maybeManifest if manifestVec.length == 1 &&
          manifestVec(0).mimeType.startsWith(jsonMimeType)

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
                if a.length == 1 && a(0).mimeType.startsWith(jsonMimeType) =>
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

case class ManifestInfo(
    manifest: ArtifactWrapper,
    manifestConfig: JValue,
    configHash: String,
    configFile: ArtifactWrapper,
    configJson: JValue,
    layers: List[String]
)
