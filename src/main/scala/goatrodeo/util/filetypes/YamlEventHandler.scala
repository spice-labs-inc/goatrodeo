package goatrodeo.util.filetypes

import com.typesafe.scalalogging.Logger
import org.yaml.snakeyaml.events._
import org.yaml.snakeyaml.{LoaderOptions, Yaml}

import java.io.StringReader
import scala.jdk.CollectionConverters.*
/**
 * An event handler for processing Snake YAML Parser Events into a Map[String, String] for metadata handling
 */
protected class YamlEventHandler {
  private val logger = Logger("YamlEventHandler")
  // single thread for this code path so we *shouldn't* need to worry about volatile / races (famous last words, I know)
  private var streamStarted: Boolean = false
  private var documentStarted: Boolean = false
  private var buildingMap: Boolean = false // todo - how do we handle nested maps?!
  private var buildingList: Boolean = false
  /**
   * the test file i'm running spits things out as
   *  - start stream
   *  - start document
   *  - start mapping (with next key being the property name for the first entry…)
   */
  private var outerMapOpen: Boolean = false

  private val builder = Map.newBuilder[String, MetadataValue]
  private var openMap = Map.newBuilder[String, MetadataValue]
  // to handle nested, we're only going to support nesting one level down for now
  private var subMap = Map.newBuilder[String, MetadataValue]
  private var openList = List.newBuilder[MetadataValue]
  // to handle nested, we're only going to support nesting one level down for now
  private var subList = List.newBuilder[MetadataValue]

  // what was the last element we saw?
  private var lastEvent: Option[Event] = None
  // is there a key open that we're waiting on a value for?this is for toplevel constructs
  private var openKey: Option[String] = None
  // is there a key open for mapping or sequence?
  private var collectionOpenKey: Option[String] = None


  def handleEvent(event: Event): Unit = {
    lastEvent = Some(event)
    event match {
      case sse: StreamStartEvent =>
        logger.debug(s"Stream Started: $sse")
        streamStarted = true
      case dse: DocumentStartEvent =>
        logger.debug(s"Document Started: $dse")
        documentStarted = true
      case mse: MappingStartEvent =>
        logger.debug(s"Mapping Started: $mse")
        if (outerMapOpen) {
          logger.debug(s"Outer Map is open, continuing")
          buildingMap = true
        } else {
          // the outer map hasn't opened yet
          buildingMap = false
          outerMapOpen = true
        }
      case sse: SequenceStartEvent =>
        logger.debug(s"Sequence Started: $sse")
      case se: ScalarEvent =>
        logger.debug(s"Scalar Event: $se")
        if (buildingMap && buildingList) {
          val err = "Can't be building both a map and a list at same time…"
          logger.error(err)
          throw new IllegalStateException(err)
        } else if (buildingMap) {
          collectionOpenKey match {
            case Some(key) =>
              logger.debug(s"Found an open collection key: $key")
              openMap += key -> MetadataString(se.getValue)
            case None =>
              val err = "No open collection key, but we're building a Map…"
              logger.error(err)
              throw new IllegalStateException()
          }
        } else if (buildingList) {
        } else {
          openKey match {
            case Some(key) => // we already had a key looking for a value…
              logger.debug(s"Found an open key: $key Value: ${se.getValue}")
              addKVPair(key, MetadataString(se.getValue))
              openKey = None
            case None =>
              logger.debug(s"No open key; setting this ('${se.getValue}') to the openKey")
              openKey = Some(se.getValue)
          }
        }
      case ce: CommentEvent =>
        logger.debug(s"Comment Event: $ce")
      case see: SequenceEndEvent =>
        logger.debug(s"Sequence Ended: $see")
        buildingList = false
        collectionOpenKey match {
          case Some(key) =>
            addKVPair(key, MetadataList(openList.result()))
            openList = List.newBuilder[MetadataValue]
            openKey = None
          case None =>
            val err = "No Open Key set for Collection building; can't close out list"
            logger.error(err)
            throw new IllegalStateException(err)
        }
      case mee: MappingEndEvent =>
        logger.debug(s"Mapping Ended: $mee")
        buildingMap = false
        collectionOpenKey match {
          case Some(key) =>
            addKVPair(key, MetadataMap(openMap.result()))
            openMap = Map.newBuilder[String, MetadataValue]
            openKey = None
          case None =>
            val err = "No Open Key set for Collection building; can't close out map"
            logger.error(err)
            throw new IllegalStateException(err)
        }
      case see: StreamEndEvent =>
        logger.debug(s"Stream Ended: $see")
        streamStarted = false
      case dee: DocumentEndEvent =>
        logger.debug(s"Document Ended: $dee")
        documentStarted = false
    }
  }

  def result(): Map[String, MetadataValue] = {
    if (documentStarted || streamStarted)
      logger.warn(s"Result called but document ($documentStarted) and/or stream ($streamStarted) are still open…")

    val result = builder.result()
    logger.info(s"Builder Result: $result")
    result
  }

  def addKVPair(key: String, value: MetadataValue): Unit = builder += key -> value
}

object YamlEventHandler {
  private val logger = Logger("YamlEventHandler$")
  def apply(rawYaml: String): YamlEventHandler = {
    val yamlOpts = new LoaderOptions()
    val yaml = new Yaml(yamlOpts)

    val handler = new YamlEventHandler
    for (event <- yaml.parse(new StringReader(rawYaml)).asScala) {
      logger.trace(s"Yaml Event: $event")
      handler.handleEvent(event)
    }
    handler
  }

}