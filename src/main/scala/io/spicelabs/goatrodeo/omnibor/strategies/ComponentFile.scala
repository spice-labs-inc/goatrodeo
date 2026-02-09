package io.spicelabs.goatrodeo.omnibor.strategies

import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoProcessFilter
import scala.jdk.CollectionConverters._
import java.util.ArrayList
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoArtifact
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoProcessItems
import io.spicelabs.rodeocomponents.APIS.artifacts.Pair
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoItemMarker
import io.spicelabs.goatrodeo.omnibor.ProcessingMarker
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactHandler
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactMemento
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.rodeocomponents.APIS.artifacts.Triple
import scala.collection.immutable.TreeSet
import io.spicelabs.goatrodeo.util.TreeMapExtensions.JUListExtensions.asVector
import io.spicelabs.goatrodeo.util.TreeMapExtensions.JUListExtensions.asVectorMap
import scala.annotation.tailrec
import io.spicelabs.goatrodeo.omnibor.strategies.ComponentFile.elementsAsVector
import io.spicelabs.goatrodeo.omnibor.Item
import java.io.FileInputStream
import java.io.InputStream
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import scala.collection.immutable.TreeMap
import io.spicelabs.rodeocomponents.APIS.artifacts.Metadata
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.rodeocomponents.APIS.artifacts.MetadataTag
import io.spicelabs.goatrodeo.omnibor.strategies.ComponentState.toGoatMeta
import com.github.packageurl.PackageURL
import io.spicelabs.goatrodeo.components.PurlAdapter
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.util.GitOID
import java.util.Optional
import io.spicelabs.goatrodeo.omnibor.strategies.ComponentState.toGitoids

/** This is the marker that gets used for components. It holds a RodeoItemMarker
  * which is a component owned type
  *
  * @param marker
  *   the component's marker
  */
class ComponentMarker(val marker: RodeoItemMarker) extends ProcessingMarker {}

/** The state for processing an artifact.
  *
  * @param handler
  *   a component-supplied handler for all the steps
  * @param memento
  *   a component-supplied memento
  * @param stream
  *   the stream to operate on
  */
class ComponentState(
    handler: ArtifactHandler,
    memento: Option[ArtifactMemento] = None,
    stream: Option[InputStream] = None
) extends ProcessingState[ComponentMarker, ComponentState] {

  /** start processing the artifact
    *
    * @param artifact
    *   the artifact to process
    * @param item
    *   the item that accumulates information
    * @param marker
    *   the marker for this step
    * @return
    *   a possibly new state
    */
  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: ComponentMarker
  ): ComponentState = {
    // if the artiface requires a file, we'll make sure that there is one and hold onto it until processing is done
    val (stm: Option[InputStream], mem: ArtifactMemento) =
      if (handler.requiresFile()) {
        val fileStm = io.spicelabs.goatrodeo.util.FileWalker.withinTempDir {
          path => FileInputStream(artifact.forceFile(path))
        }
        val mem = handler.begin(fileStm, artifact, item, marker.marker)
        (Some(fileStm), mem)
      } else {
        artifact.withStream(stm => {
          val mem = handler.begin(stm, artifact, item, marker.marker)
          (None, mem)
        })
      }
    ComponentState(handler, Some(mem), stm)
  }

  /** Get the metadata for the artifact translating the Java representation to
    * scala types
    *
    * @param artifact
    *   the artifact to process
    * @param item
    *   the item to accumulate information
    * @param marker
    *   the component supplied marker
    * @return
    *   a possibly new state
    */
  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: ComponentMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], ComponentState) = {
    memento match {
      case None => TreeMap[String, TreeSet[StringOrPair]]() -> this
      case Some(mem) => {
        val metadata = handler.getMetadata(mem, artifact, item, marker.marker)
        val convertedMetadata = toGoatMeta(metadata)

        convertedMetadata -> this
      }
    }
  }

  /** Get package URLs for the artifact
    *
    * @param artifact
    *   the artifact to process
    * @param item
    *   the item to accumulate information
    * @param marker
    *   the component supplied marker
    * @return
    *   a possibly new state
    */
  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: ComponentMarker
  ): (Vector[PackageURL], ComponentState) = {
    memento match {
      case None => Vector[PackageURL]() -> this
      case Some(mem) => {
        val purls = handler.getPurls(mem, artifact, item, marker.marker)
        purls.asVectorMap(p => p.asInstanceOf[PurlAdapter].purl) -> this
      }
    }
  }

  /** Perform final augmentation on the item
    *
    * @param artifact
    *   the artifact to process
    * @param item
    *   the item to accumulate information
    * @param marker
    *   the component supplied marker
    * @param parentScope
    *   the parent scope
    * @param store
    *   a store for data
    * @return
    *   a possibly new item
    */
  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: ComponentMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, ComponentState) = {
    memento match {
      case None => item -> this
      case Some(mem) => {
        var workItem = handler.augment(
          mem,
          artifact,
          item,
          parentScope,
          store,
          marker.marker
        )
        workItem.asInstanceOf[Item] -> this
      }
    }
  }

  /** Perform any final processing on the data
    *
    * @param kids
    *   gitoids of the children
    * @param store
    *   a store for data
    * @param marker
    *   the component supplied marker
    * @return
    *   a possibly new item
    */
  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: ComponentMarker
  ): ComponentState = {
    memento match {
      case None => this
      case Some(mem) => {
        val gitoids = toGitoids(kids)
        handler.postChildProcessing(mem, gitoids, store, marker.marker)
        handler.end(mem)
        this
      }
    }
  }
}

/** Helper methods to support data type translation
  */
object ComponentState {

  /** Converts a java List[Metadata] to a TreeMap
    *
    * @param meta
    *   component supplied metadata
    * @return
    *   the metadata reformed into the internal format
    */
  def toGoatMeta(
      meta: java.util.List[Metadata]
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val metavec = meta.asVector
    metavec.foldLeft(TreeMap[String, TreeSet[StringOrPair]]())((map, meta) => {
      map + toGoatMeta(meta)
    })
  }

  /** Convert a unit of metadata into a key and value
    *
    * @param meta
    * @return
    */
  def toGoatMeta(meta: Metadata): (String, TreeSet[StringOrPair]) = {
    val key = toKey(meta.tag())
    val value = TreeSet[StringOrPair](meta.value())
    key -> value
  }

  /** Convert the component metadata tag to the String name
    *
    * @param tag
    *   the metadata data
    * @return
    *   a string representing the metadata tag
    */
  def toKey(tag: MetadataTag) = {
    tag match {
      case MetadataTag.NAME             => MetadataKeyConstants.NAME
      case MetadataTag.SIMPLE_NAME      => MetadataKeyConstants.SIMPLE_NAME
      case MetadataTag.VERSION          => MetadataKeyConstants.VERSION
      case MetadataTag.LOCALE           => MetadataKeyConstants.LOCALE
      case MetadataTag.PUBLIC_KEY       => MetadataKeyConstants.PUBLIC_KEY
      case MetadataTag.PUBLISHER        => MetadataKeyConstants.PUBLISHER
      case MetadataTag.PUBLICATION_DATE => MetadataKeyConstants.PUBLICATION_DATE
      case MetadataTag.COPYRIGHT        => MetadataKeyConstants.COPYRIGHT
      case MetadataTag.DESCRIPTION      => MetadataKeyConstants.DESCRIPTION
      case MetadataTag.TRADEMARK        => MetadataKeyConstants.TRADEMARK
      case MetadataTag.ARTIFACT_ID      => MetadataKeyConstants.ARTIFACTID
      case MetadataTag.LICENSE          => MetadataKeyConstants.LICENSE
      case MetadataTag.DEPENDENCIES     => MetadataKeyConstants.DEPENDENCIES
    }
  }

  /** Convert an java Optional[List[String]] to a scala Option[Vector]
    *
    * @param oids
    * @return
    */
  def toGitoids(
      oids: Option[Vector[GitOID]]
  ): Optional[java.util.List[String]] = {
    oids match {
      case None => Optional.empty()
      case Some(vec) => {
        Optional.of(vec.foldLeft(ArrayList())((list, gitOID) => {
          list.add(gitOID)
          list
        }))
      }
    }
  }
}

/** A type for processing items for components
  *
  * @param file
  *   the artifact wrapper for the item to work on
  * @param processItems
  *   the items to process
  */
final class ComponentFile(
    file: ArtifactWrapper,
    processItems: RodeoProcessItems
) extends ToProcess {
  type MarkerType = ComponentMarker
  type StateType = ComponentState

  override def markSuccessfulCompletion(): Unit = file.finished()
  override def itemCnt: Int = processItems.length()
  override def mimeType: String = file.mimeType
  override def main: String = file.path()
  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) = {
    val toProcess = processItems.getItemsToProcess()
    val tuplesToProcess = elementsAsVector(toProcess.first())
    val state = ComponentState(toProcess.second())
    (tuplesToProcess, state)
  }
}

object ComponentFile {
  def computeComponentFiles(componentFilter: RodeoProcessFilter)(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ToProcess.ByUUID, ToProcess.ByName, String) = {

    // get a java map of the byName collection
    val jbyName = byName.map((s, v) => s -> vectorConvert(v)).asJava

    // get the ones that we claim
    val filteredNames = componentFilter.filterByName(jbyName).asVector
    val namesOnly = getNames(filteredNames)
    val uuidsOnly = getUUIDS(filteredNames)
    val name = componentFilter.getName()

    // remove all the names that we grabbed
    val newbyName = byName.filter((s, v) => !namesOnly.contains(s))
    // remove all the uuids referenced by the filtered names
    val newbyUUID = byUUID.filter((uuid, wrapper) => !uuidsOnly.contains(uuid))

    val toProcess = filteredNames.foldLeft(Vector[ToProcess]())(
      (
          toProc: Vector[ToProcess],
          ra_rpi: Triple[String, RodeoArtifact, RodeoProcessItems]
      ) => {
        val artWrapper = ra_rpi.second.asInstanceOf[ArtifactWrapper]
        val processItems = ra_rpi.third()
        toProc :+ ComponentFile(artWrapper, processItems)

      }
    )
    (toProcess, newbyUUID, newbyName, name)
  }

  // convert a vectory or artifact wrappers into a list of RodeoArtifact
  private def vectorConvert(
      v: Vector[ArtifactWrapper]
  ): java.util.List[RodeoArtifact] = {
    val list = ArrayList[RodeoArtifact](v.length)
    for item <- v do list.add(item)
    list
  }

  private def getNames(
      filteredNames: Vector[Triple[String, RodeoArtifact, RodeoProcessItems]]
  ): TreeSet[String] = {
    filteredNames.foldLeft(TreeSet[String]())((set, item) => set + item.first())
  }

  private def getUUIDS(
      filteredNames: Vector[Triple[String, RodeoArtifact, RodeoProcessItems]]
  ): TreeSet[String] = {
    filteredNames.foldLeft(TreeSet[String]())((set, item) =>
      set + item.second().getUuid()
    )
  }

  private def elementsAsVector(
      list: java.util.List[Pair[RodeoArtifact, RodeoItemMarker]]
  ): Vector[(ArtifactWrapper, ComponentMarker)] = {
    elementsAsVectorTail(list, 0, Vector())
  }

  @tailrec
  private def elementsAsVectorTail(
      list: java.util.List[Pair[RodeoArtifact, RodeoItemMarker]],
      index: Int,
      result: Vector[(ArtifactWrapper, ComponentMarker)]
  ): Vector[(ArtifactWrapper, ComponentMarker)] = {
    if (index >= list.size()) {
      result
    } else {
      val elem = list.get(index)
      elementsAsVectorTail(
        list,
        index + 1,
        result :+ elem.first().asInstanceOf[ArtifactWrapper] -> ComponentMarker(
          elem.second()
        )
      )
    }
  }
}
