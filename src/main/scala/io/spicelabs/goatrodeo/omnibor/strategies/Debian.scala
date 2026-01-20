package io.spicelabs.goatrodeo.omnibor.strategies

import com.github.packageurl.PackageURL
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.SingleMarker
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

import java.io.BufferedReader
import java.io.InputStreamReader
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** State maintained during Debian package processing.
  *
  * Extracts metadata from the DEB control file to generate:
  *   - Package URLs (pURL) for package identification
  *   - Metadata including package, version, architecture, etc.
  *
  * @param artifact
  *   the DEB artifact to extract metadata from
  */
class DebianState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, DebianState] {

  lazy val artifactMetaData
      : Option[(Option[PackageURL], TreeMap[String, TreeSet[StringOrPair]])] =
    Debian.computePurl(artifact)

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
      marker: SingleMarker
  ): DebianState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (Vector[PackageURL], DebianState) =
    artifactMetaData.flatMap(_._1).toVector -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], DebianState) = artifactMetaData
    .map(_._2)
    .getOrElse(TreeMap[String, TreeSet[StringOrPair]]()) -> this

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, DebianState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): DebianState = this

}

/** A Debian package (.deb) to process.
  *
  * @param deb
  *   the DEB artifact
  */
final case class Debian(deb: ArtifactWrapper) extends ToProcess {

  /** Call at the end of successfull completing the operation
    */
  def markSuccessfulCompletion(): Unit = {
    deb.finished()
  }
  override def itemCnt: Int = 1
  override def main: String = deb.path()

  /** The mime type of the main artifact
    */
  def mimeType: String = deb.mimeType

  type MarkerType = SingleMarker
  type StateType = DebianState
  def getElementsToProcess(): (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(deb -> SingleMarker()) -> DebianState(deb)
}

/** Factory methods and utilities for Debian package processing. */
object Debian {

  /** Extract package URL and metadata from a DEB file.
    *
    * Parses the control.tar file inside the DEB to extract:
    *   - Package name, version, architecture
    *   - All control file fields as metadata
    *
    * @param f
    *   the DEB artifact
    * @return
    *   optional tuple of (pURL, metadata map)
    */
  def computePurl(
      f: ArtifactWrapper
  ): Option[(Option[PackageURL], TreeMap[String, TreeSet[StringOrPair]])] = {
    val name = f.path()
    if (f.mimeType == "application/x-debian-package") {
      val maybeRawLines: Option[(String, Vector[String])] = (FileWalker
        .withinArchiveStream(f) { files =>
          files
            .filter(a => {
              val path = a.path()
              val pathLc = path.toLowerCase

              pathLc.startsWith("control.tar")
            })
            .headOption
            .flatMap { art =>
              FileWalker.withinArchiveStream(art) { files =>
                files
                  .filter(_.path().toLowerCase == "control")
                  .headOption
                  .map(innerArt => {
                    val str =
                      innerArt.withStream(Helpers.slurpInputToString(_))
                    import scala.jdk.CollectionConverters.*

                    val lr = innerArt.withStream { stream =>
                      val br = BufferedReader(InputStreamReader(stream))
                      br.lines().iterator().asScala.toVector
                    }
                    str -> lr
                  })
              }

            }
            .flatten

        })
        .flatten

      val purlAndAttrs = for { (_, rawLines) <- maybeRawLines } yield {
        // squash the multi-line representations into a single line
        val lines: Vector[String] = rawLines.foldLeft(Vector[String]()) {
          case (cur, next) if cur.isEmpty => Vector(next)
          case (cur, next) if next.startsWith(" ") =>
            cur.dropRight(1) :+ f"${cur.last} ${next}"
          case (cur, next) => cur :+ next
        }

        val attrs = Map(lines.flatMap(s => {
          s.split(":").toList match {
            case a :: b =>
              Vector(
                (
                  a.trim().toLowerCase(),
                  b.foldLeft("") {
                    case (a, b) if a.length() > 0 => a + ":" + b
                    case (_, b)                   => b
                  }.trim()
                )
              )
            case _ => Vector()
          }
        })*)

        val pkg = attrs.get("package")
        val version = attrs.get("version")
        val arch = attrs.get("architecture")

        val purl: Option[PackageURL] = (pkg, version) match {
          case (Some(thePkg), Some(theVersion)) =>
            Some(
              PURLHelpers.buildPackageURL(
                Ecosystems.Debian,
                Some(
                  if (f.path().contains("ubuntu")) "ubuntu"
                  else "debian"
                ),
                thePkg,
                theVersion,
                None,
                arch.map(a => "arch" -> a).toVector
              )
            )
          case _ => None
        }
        val treeAttrs: TreeMap[String, TreeSet[StringOrPair]] =
          TreeMap(attrs.toSeq.map { case (k, v) =>
            k /*.intern()*/ -> TreeSet(StringOrPair(v))
          }*) ++ maybeRawLines.toVector.map { case (str, _) =>
            "control" -> TreeSet(StringOrPair("text/debian-control", str))
          }
        purl -> treeAttrs
      }

      purlAndAttrs

    } else None
  }

  /** Identify Debian packages from a collection of files.
    *
    * Finds files with MIME type "application/x-debian-package"
    * and creates Debian ToProcess instances for them.
    *
    * @param byUUID
    *   artifacts indexed by UUID
    * @param byName
    *   artifacts indexed by filename
    * @return
    *   tuple of (ToProcess items, remaining UUID map, remaining name map, strategy name)
    */
  def computeDebianFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    var ret: Vector[ToProcess] = Vector()
    var retByUUID = byUUID

    val retByName = byName.map { case (k, v) =>
      val isDeb = v.filter(_.mimeType == "application/x-debian-package")

      // no debian files, just continue
      if (isDeb.isEmpty) {
        k -> v
      } else {
        // all the non-debian files
        val newV = v.filter(_.mimeType != "application/x-debian-package")

        // for each of the debian files, add to ret, substract from uuid
        for { deb <- isDeb } {
          retByUUID -= deb.uuid
          ret = ret :+ Debian(deb)
        }

        k -> newV
      }
    }

    (ret, retByUUID, retByName, "Debian")
  }
}
