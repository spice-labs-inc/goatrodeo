package io.spicelabs.goatrodeo.util

import com.github.packageurl.PackageURL
import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.*
import io.spicelabs.goatrodeo.omnibor.ConnectionAugmentation
import io.spicelabs.goatrodeo.omnibor.EdgeType
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import org.json4s.*
import org.json4s.native.JsonMethods.*

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import scala.util.Try

/** The bridge to Syft https://github.com/anchore/syft
  */
object StaticMetadata {
  private val logger = Logger(getClass())
  private lazy val staticMetadataMimeTypes = Set(
    "application/zip",
    "application/java-archive",
    "application/vnd.android.package-archive",
    "application/x-rpm",
    "application/x-archive",
    "application/x-iso9660-image",
    "application/x-gtar",
    "application/x-debian-package",
    "application/gzip",
    "application/zstd"
  )

  /** Is the artifact a container that this module how to process?
    *
    * @param artifact
    * @return
    */
  def isContainer(artifact: ArtifactWrapper): Boolean = {
    staticMetadataMimeTypes.contains(artifact.mimeType)
  }

  /** The beginning of a block list. Does nothing.
    *
    * @param the
    *   artifact to test
    * @return
    *   if it should not be processed by Syft
    */
  def isBlocked(artifact: ArtifactWrapper): Boolean = {
    false
  }

  lazy val hasSyft: Boolean = {
    Try {
      val process = ProcessBuilder("syft", "--help").start()
      while (process.isAlive()) {
        Thread.sleep(100)
      }
      process.exitValue() == 0
    }.toOption == Some(true)
  }

  def runStaticMetadataGather(
      artfiact: ArtifactWrapper,
      tempDir: Path
  ): Option[StaticMetadataResult] = {
    if (hasSyft && !isBlocked(artfiact)) {
      Try {
        val targetFile = artfiact.forceFile(tempDir)
        val pb = ProcessBuilder(
          List(
            "syft",
            "scan",
            f"file:${targetFile.getName()}",
            "--enrich",
            "all",
            "--scope",
            "all-layers",
            "--output",
            "syft-json"
          )*
        ).directory(targetFile.getCanonicalFile().getParentFile())
        val ret = StaticMetadataResult(pb, targetFile.getCanonicalPath())
        ret.go()
        ret
      }.toOption
    } else None

  }
}

object StaticMetadataResult {
  private val logger = Logger(this.getClass())
}

class StaticMetadataResult(private val process: ProcessBuilder, dir: String) {
  val startedAt = System.currentTimeMillis()
  @volatile private var running = false
  @volatile private var answer: Option[(String, org.json4s.JValue)] = None
  @volatile private var theProcess: Option[Process] = None
  @volatile private var processExitCode: Option[Int] = None

  /** Kick off the process
    */
  def go(): Unit = {
    Thread(
      () => {
        doBackground()
      },
      f"Metadata Gather ${dir}"
    ).start()
    this.synchronized {
      while (!running) {
        this.wait(10)
      }
    }
  }

  def exitCode(): Option[Int] = processExitCode
  def isRunning(): Boolean = running
  def theAnswer(): Option[(String, JValue)] = answer
  def runForMillis(howLongMillis: Long): Option[(String, JValue)] = {
    this.synchronized {
      if (running) {
        this.wait(howLongMillis)
      }
      theAnswer()
    }
  }

  def buildAugmentation(): Map[String, Vector[Augmentation]] = {
    answer match {
      case None => Map()
      case Some((str, json)) => {

        val files = Map((for {
          files <- (json \ "files" match {
            case JArray(arr) => List(arr)
            case _           => List()
          })
          file <- files
          case JString(id) <- file \ "id"
          case JArray(digests) <- file \ "digests"
          digest <- digests
          case JString(algo) <- digest \ "algorithm"
          case JString(value) <- digest \ "value"
        } yield id -> f"${algo}:${value}")*)

        val relatationships = Map((for {
          rels <- (json \ "artifactRelationships" match {
            case JArray(arr) => List(arr)
            case _           => List()
          })
          rel <- rels
          case JString(parent) <- rel \ "parent"
          case JString(child) <- rel \ "child"
          key <- files.get(child).toList
        } yield parent -> key)*)

        val artifacts = (json \ "artifacts" match {
          case JArray(arr) => arr
          case _           => List()
        }): List[JValue]

        val digestedAugmentations = (for {

          artifact <- artifacts
        } yield {
          val purls = artifact \ "purl" match {
            case JString(purl) => Vector(purl)
            case _ =>
              Vector()
          }

          val metadataDigest = artifact \ "metadata" \ "digest" match {
            case JArray(rawDigests) =>
              (rawDigests.flatMap { digest =>
                (digest \ "algorithm", digest \ "value") match {
                  case (JString(algo), JString(value)) =>
                    Vector(f"${algo}:${value}")
                  case _ => Vector()
                }
              }).toVector

            case _ => Vector()
          }

          val digests = metadataDigest ++ (for {
            case JString(id) <- artifact \ "id"
            hash <- relatationships.get(id).toList
          } yield hash)

          val purlAugment = for {
            digest <- digests
            purl <- purls
            augmentation <- {
              try {
                List(
                  ConnectionAugmentation(
                    digest,
                    (EdgeType.aliasFrom, purl),
                    PackageURL(purl)
                  )
                )
              } catch {
                case e: Exception =>
                  StaticMetadataResult.logger.debug(
                    f"Failed to create Package URL from '${purl}'"
                  )
                  List()
              }
            }
          } yield augmentation
          val artifactStr = pretty(render(artifact match {
            case JObject(obj) => JObject(obj.filter(_._1 != "id"))
            case v            => v
          }))

          val extra =
            for { digest <- digests } yield ExtraAugmentation(
              digest,
              "static-metadata-artifact",
              StringOrPair("application/json", artifactStr)
            )

          extra ++ purlAugment
        }).toVector.flatten

        val ret = (digestedAugmentations).foldLeft(
          Map[String, Vector[Augmentation]]()
        ) { case (map, item) =>
          map.get(item.hashValue) match {
            case None    => map + (item.hashValue -> Vector(item))
            case Some(v) => map + (item.hashValue -> (v :+ item))
          }
        }

        ret
      }
    }
  }

  private def doBackground(): Unit = {
    try {
      val myProcess = process.start()
      running = true
      this.synchronized {
        this.notifyAll()
      }
      try {

        theProcess = Some(myProcess)
        val reader = myProcess.getInputStream()
        val bos = ByteArrayOutputStream()
        val bytes: Array[Byte] = new Array(4096)
        var bytesRead = reader.read(bytes)
        while (bytesRead >= 0) {
          if (bytesRead > 0) {
            bos.write(bytes, 0, bytesRead)
          }
          bytesRead = reader.read(bytes)
        }

        // wait a while for the process to finish even after stdout has closed
        while (myProcess.isAlive()) {
          Thread.sleep(100)
        }

        if (myProcess.exitValue() == 0) {
          answer = Try {

            val theBytes = bos.toByteArray()
            val str = String(theBytes, "UTF-8")
            val jsonBase = parse(str)

            val json = jsonBase match {
              case JObject(obj) =>
                // remove some Syft noise
                JObject(obj.filter { f =>
                  f._1 != "source" && f._1 != "descriptor" && f._1 != "distro"
                })
              case v => v
            }
            (pretty(render(json)), json)
          }.toOption
        }
      } catch {
        case e: Throwable =>
          StaticMetadataResult.logger.error("Failed to read", e)

      } finally {
        processExitCode = Some(myProcess.exitValue())
        running = false

        theProcess = None
        this.synchronized {
          this.notifyAll()
        }
      }

    } catch {
      case e: Throwable =>
        StaticMetadataResult.logger.error("Failed to read", e)

    }
  }

}
