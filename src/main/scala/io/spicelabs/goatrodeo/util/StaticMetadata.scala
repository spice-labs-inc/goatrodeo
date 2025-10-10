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
import java.nio.file.*
import scala.util.Try

/** The bridge to Syft https://github.com/anchore/syft
  */
object StaticMetadata {
  private val logger = Logger(getClass())
  private lazy val staticMetadataMimeTypes = Set(
    "application/x-archive",
    "application/x-cpio",
    "application/x-shar",
    "application/x-iso9660-image",
    "application/x-sbx",
    "application/x-tar",
    // compression only
    "application/x-bzip2",
    "application/gzip",
    "application/x-lzip",
    "application/x-lzma",
    "application/x-lzop",
    "application/x-snappy-framed",
    "application/x-xz",
    "application/x-compress",
    "application/zstd",
    // archiving and compression
    "application/x-7z-compressed",
    "application/x-ace-compressed",
    "application/x-astrotite-afa",
    "application/x-alz-compressed",
    "application/vnd.android.package-archive",
    "application/x-freearc",
    "application/x-arj",
    "application/x-b1",
    "application/vnd.ms-cab-compressed",
    "application/x-cfs-compressed",
    "application/x-dar",
    "application/x-dgc-compressed",
    "application/x-apple-diskimage",
    "application/x-gca-compressed",
    "application/java-archive",
    "application/x-lzh",
    "application/x-lzx",
    "application/x-rar-compressed",
    "application/x-stuffit",
    "application/x-stuffitx",
    "application/x-gtar",
    "application/x-ms-wim",
    "application/x-xar",
    "application/zip",
    "application/x-zoo",
    "application/x-executable",
    "application/x-mach-binary",
    "application/x-elf",
    "application/x-sharedlib",
    "application/vnd.microsoft.portable-executable",
    "application/x-executable",
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

  private lazy val globSet = Set(
    "**/*.app",
    "**/*.bom",
    "**/*.bom.*",
    "**/bom",
    "**/cabal.project.freeze",
    "**/Cargo.lock",
    "**/*.cdx",
    "**/*.cdx.*",
    "**/Cellar/*/*/.brew/*.rb",
    "**/composer.lock",
    "**/conanfile.txt",
    "**/conaninfo.txt",
    "**/conan.lock",
    "**/conda-meta/*.json",
    "**/*.deb",
    "**/DESCRIPTION",
    "**/*dist-info/METADATA",
    "**/*DIST-INFO/METADATA",
    "**/*.dll",
    "**/doc/linux-modules-*/changelog.Debian.gz",
    "**/*.ear",
    "**/*.egg-info",
    "**/*egg-info/PKG-INFO",
    "**/*EGG-INFO/PKG-INFO",
    "**/*.exe",
    "**/Gemfile.lock",
    "**/*.gemspec",
    "**/.github/actions/*/action.yaml",
    "**/.github/actions/*/action.yml",
    "**/.github/workflows/*.yaml",
    "**/.github/workflows/*.yml",
    "**/go.mod",
    "**/gradle.lockfile*",
    "**/*.hpi",
    "**/installed.json",
    "**/*.jar",
    "**/*.jpi",
    "**/*.kar",
    "**/kernel",
    "**/kernel-*",
    "**/lib/apk/db/installed",
    "**/lib/dpkg/status",
    "**/lib/dpkg/status.d/*",
    "**/lib/modules/**/*.ko",
    "**/lib/opkg/info/*.control",
    "**/lib/opkg/status",
    "**/Library/Taps/*/*/Formula/*.rb",
    "**/*.lpkg",
    "**/meta/snap.yaml",
    "**/mix.lock",
    "**/*.nar",
    "**/*opam",
    "/opt/bitnami/**/.spdx-*.spdx",
    "**/package.json",
    "**/package-lock.json",
    "**/.package.resolved",
    "**/Package.resolved",
    "**/packages.lock.json",
    "**/pack.pl",
    "**/*.par",
    "**/php/.registry/.channel.*/*.reg",
    "**/php/.registry/**/*.reg",
    "**/Pipfile.lock",
    "**/pnpm-lock.yaml",
    "**/Podfile.lock",
    "**/poetry.lock",
    "**/pubspec.lock",
    "**/pubspec.yaml",
    "**/pubspec.yml",
    "**/rebar.lock",
    "**/release",
    "**/*requirements*.txt",
    "**/*.rockspec",
    "**/*.sar",
    "**/*.sbom",
    "**/*.sbom.*",
    "**/sbom",
    "**/setup.py",
    "**/snap/manifest.yaml",
    "**/snap/snapcraft.yaml",
    "**/*.spdx",
    "**/*.spdx.*",
    "**/specifications/**/*.gemspec",
    "**/stack.yaml",
    "**/stack.yaml.lock",
    "**/*.syft.json",
    "**/*.tar",
    "**/*.tar.br",
    "**/*.tar.bz",
    "**/*.tar.bz2",
    "**/*.tar.gz",
    "**/*.tar.lz4",
    "**/*.tar.sz",
    "**/*.tar.xz",
    "**/*.tar.zst",
    "**/*.tar.zstd",
    "**/*.tbr",
    "**/*.tbz",
    "**/*.tbz2",
    "**/.terraform.lock.hcl",
    "**/*.tgz",
    "**/*.tlz4",
    "**/*.tsz",
    "**/*.txz",
    "**/*.tzst",
    "**/*.tzstd",
    "**/usr/share/snappy/dpkg.yaml",
    "**/uv.lock",
    "**/var/db/pkg/*/*/CONTENTS",
    "**/var/lib/pacman/local/**/desc",
    "**/var/lib/rpmmanifest/container-manifest-2",
    "**/{var/lib,usr/share,usr/lib/sysimage}/rpm/{Packages,Packages.db,rpmdb.sqlite}",
    "**/vmlinux",
    "**/vmlinux-*",
    "**/vmlinuz",
    "**/vmlinuz-*",
    "**/*.war",
    "**/wp-content/plugins/*/*.php",
    "**/yarn.lock",
    "**/*.zip"
  )

  private lazy val pathMatchers = {
    val fs = FileSystems.getDefault()
    for {
      glob <- globSet.toVector
      pm <- Try { fs.getPathMatcher(f"glob:${glob}") }.toOption.toVector
    } yield pm
  }

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
  def isBlocked(
      artifact: ArtifactWrapper,
      mimeFilter: IncludeExclude
  ): Boolean = {
    !mimeFilter.shouldInclude(artifact.mimeType)
  }

  def isAllowed(artifact: ArtifactWrapper): Boolean = {
    staticMetadataMimeTypes.contains(artifact.mimeType) || {
      artifact match {
        case fw: FileWrapper => {
          val path = fw.wrappedFile.toPath
          pathMatchers.find(pm => pm.matches(path)).isDefined
        }
        case _ => false
      }
    }
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
      artifact: ArtifactWrapper,
      tempDir: Path,
      mimeFilter: IncludeExclude
  ): Option[StaticMetadataResult] = {
    if (hasSyft && isAllowed(artifact) && !isBlocked(artifact, mimeFilter)) {
      artifact match {
        case fw: FileWrapper =>
          Try {
            val thePath = fw.wrappedFile.getCanonicalPath()
            // println(s"Syfting ${thePath}")
            val pb = ProcessBuilder(
              List(
                "syft",
                "scan",
                f"file:${thePath}",
                "--enrich",
                "all",
                "--scope",
                "all-layers",
                "--output",
                "syft-json"
              )*
            )
            val ret = StaticMetadataResult(pb, thePath)
            ret.go()
            ret
          }.toOption
        case _ => None
      }
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
