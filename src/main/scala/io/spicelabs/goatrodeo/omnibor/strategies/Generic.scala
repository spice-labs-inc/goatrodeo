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
import io.spicelabs.goatrodeo.util.GitOID

import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet

/** State for generic file processing.
  *
  * A no-op state used for files that don't match any specific strategy
  * (Maven, Docker, Debian, .NET). All methods return unchanged state
  * with no additional metadata or package URLs.
  */
class GenericFileState extends ProcessingState[SingleMarker, GenericFileState] {

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
  ): GenericFileState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (Vector[PackageURL], GenericFileState) = Vector.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], GenericFileState) =
    TreeMap[String, TreeSet[StringOrPair]]() -> this

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, GenericFileState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): GenericFileState = this

}

/** A generic file to process.
  *
  * Used as a fallback for files that don't match any specific strategy.
  * Generates basic Item entries with GitOID and MIME type but no
  * ecosystem-specific metadata or package URLs.
  *
  * @param file
  *   the artifact to process
  */
final case class GenericFile(file: ArtifactWrapper) extends ToProcess {

  /** Call at the end of successfull completing the operation
    */
  def markSuccessfulCompletion(): Unit = {
    file.finished()
  }
  type MarkerType = SingleMarker
  type StateType = GenericFileState
  override def main: String = file.path()

  override def itemCnt: Int = 1

  /** The mime type of the main artifact
    */
  def mimeType: String = file.mimeType

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(file -> SingleMarker()) -> GenericFileState()

}

/** Factory methods for creating generic file processing strategies. */
object GenericFile {

  /** Convert all remaining artifacts to generic files.
    *
    * This is typically the last strategy applied, consuming all
    * artifacts not matched by other strategies (Maven, Docker, etc.).
    *
    * @param byUUID
    *   artifacts indexed by UUID
    * @param byName
    *   artifacts indexed by filename
    * @return
    *   tuple of (ToProcess items, empty UUID map, empty name map, strategy name)
    */
  def computeGenericFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val ret = for {
      vals <- byName.values
      wrapper <- vals
    } yield GenericFile(wrapper)

    (ret.toVector, Map(), Map(), "Generic")
  }
}
