package io.spicelabs.goatrodeo.components.testing

import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.goatrodeo.components.testing.SimpleIdentity
import io.spicelabs.rodeocomponents.RodeoIdentity
import java.lang.Runtime.Version
import io.spicelabs.rodeocomponents.RodeoEnvironment
import io.spicelabs.rodeocomponents.APIFactoryReceiver
import io.spicelabs.rodeocomponents.APIFactorySource
import io.spicelabs.rodeocomponents.APIS.purls.PurlFactory
import io.spicelabs.rodeocomponents.APIS.purls.PurlAPI
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactHandlerRegistrar
import io.spicelabs.rodeocomponents.APIS.purls.PurlAPIConstants
import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*
import io.spicelabs.rodeocomponents.APIFactory
import io.spicelabs.rodeocomponents.API
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactConstants
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoProcessFilter
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoArtifact
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoProcessItems
import io.spicelabs.rodeocomponents.APIS.artifacts.Triple
import java.{util => ju}
import ju.ArrayList
import io.spicelabs.rodeocomponents.APIS.artifacts.Pair
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoItemMarker
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactHandler
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactMemento
import java.io.FileInputStream
import io.spicelabs.rodeocomponents.APIS.artifacts.WorkItem
import java.io.InputStream
import scala.annotation.tailrec
import java.nio.charset.Charset
import MyArtifactHandler.chunkFromStm
import ju.zip.CRC32
import ChunkyMemento.toKvp
import java.io.ByteArrayInputStream
import ju.zip.InflaterInputStream
import java.io.ByteArrayOutputStream
import ChunkyMemento.toFlateKvp
import io.spicelabs.rodeocomponents.APIS.artifacts.Metadata
import io.spicelabs.rodeocomponents.APIS.artifacts.MetadataTag
import io.spicelabs.rodeocomponents.APIS.artifacts.BackendStorage
import io.spicelabs.rodeocomponents.APIS.artifacts.ParentFrame
import io.spicelabs.rodeocomponents.APIS.purls.Purl
import io.spicelabs.goatrodeo.util.FileWrapper
import java.io.File
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.util.Config
import io.spicelabs.goatrodeo.components.RodeoHost
import io.spicelabs.goatrodeo.components.testing.PngComponent.installFilter

case class PngChunk(name: String, data: Array[Byte])

// we're not using the marker so this is the simplest one
class MyItemMarker extends RodeoItemMarker {}
// for the case of a FileInputStream, we don't need real memento
class EmptyMemento extends ArtifactMemento {}

// memento to hold png chunks
class ChunkyMemento(chunks: Vector[PngChunk]) extends ArtifactMemento {
  val exif = chunks.find(chunk => chunk.name == "eXIf")
  val text = chunks
    .filter(chunk => chunk.name == "tEXt")
    .map(chunk => toKvp(chunk.data, MyArtifactHandler.latinCharset))
    .toMap
  val itext = chunks
    .filter(chunk => chunk.name == "iTXt")
    .map(chunk => toKvp(chunk.data, MyArtifactHandler.utf8Charset))
    .toMap
  val ztext = chunks
    .filter(chunk => chunk.name == "zTXt")
    .map(chunk => toFlateKvp(chunk.data, MyArtifactHandler.latinCharset))
}

object ChunkyMemento {
  // convert a png text array to a pair of strings
  def toKvp(data: Array[Byte], charset: Charset): (String, String) = {
    val split = data.indexOf(0)
    val key = String(data, 0, split, charset)
    val value = String(data, split + 1, data.length - split - 1, charset)
    key -> value
  }
  // convert a compressed png text array to a pair of strings
  def toFlateKvp(data: Array[Byte], charset: Charset): (String, String) = {
    val split = data.indexOf(0)
    val key = String(data, 0, split, charset)
    val baIS = ByteArrayInputStream(data, split + 1, data.length - split - 1)
    val inflateStm = InflaterInputStream(baIS)
    val os = ByteArrayOutputStream(data.length)
    inflateStm.transferTo(os)
    val flatedata = os.toByteArray()
    val value = String(flatedata, charset)
    key -> value
  }
}

class MyArtifactHandler(purlAPI: PurlAPI) extends ArtifactHandler {
  // don't need a file
  override def requiresFile(): Boolean = false

  // since we don't need a file, just return an empty memento
  override def begin(
      stream: FileInputStream,
      artifact: RodeoArtifact,
      item: WorkItem,
      marker: RodeoItemMarker
  ): ArtifactMemento = {
    EmptyMemento()
  }

  // parse out all the chunks from the png
  override def begin(
      stream: InputStream,
      artifact: RodeoArtifact,
      item: WorkItem,
      marker: RodeoItemMarker
  ): ArtifactMemento = {
    println("making chunks")
    val chunks = pngChunks(stream).filter(chunk =>
      MyArtifactHandler.chunksWeCareAbout.contains(chunk.name)
    )
    ChunkyMemento(chunks)
  }

  override def getMetadata(
      memento: ArtifactMemento,
      artifact: RodeoArtifact,
      item: WorkItem,
      marker: RodeoItemMarker
  ): ju.List[Metadata] = {
    val chunky = memento.asInstanceOf[ChunkyMemento]
    val meta = ju.ArrayList[Metadata]()
    maybeAdd(MetadataTag.DESCRIPTION, chunky.text.get("Description"), meta)
    maybeAdd(MetadataTag.COPYRIGHT, chunky.text.get("Copyright"), meta)
    meta
  }

  override def augment(
      memento: ArtifactMemento,
      artifact: RodeoArtifact,
      item: WorkItem,
      parent: ParentFrame,
      storage: BackendStorage,
      marker: RodeoItemMarker
  ): WorkItem = {
    item
  }

  override def getPurls(
      memento: ArtifactMemento,
      artifact: RodeoArtifact,
      item: WorkItem,
      marker: RodeoItemMarker
  ): ju.List[Purl] = {
    // fake up a purl
    val result = ju.ArrayList[Purl]()
    val purlfactory = purlAPI.newPurlFactory()
    result.add(
      purlfactory
        .withType("Unknown")
        .withName("png-haus")
        .withVersion("0.0.0")
        .toPurl()
    )
    result
  }

  override def postChildProcessing(
      memento: ArtifactMemento,
      gitoids: ju.Optional[ju.List[String]],
      storage: BackendStorage,
      marker: RodeoItemMarker
  ): Unit = {}

  override def end(memento: ArtifactMemento): Unit = {}

  private def maybeAdd(
      tag: MetadataTag,
      value: Option[String],
      list: ju.List[Metadata]
  ) = {
    value match {
      case Some(v) => list.add(Metadata(tag, v))
      case None    =>
    }
  }

  private def pngChunks(stm: InputStream): Vector[PngChunk] = {
    val header = stm.readNBytes(8)
    if (!header.sameElements(MyArtifactHandler.pngHeader)) {
      Vector()
    } else {
      pngChunksTail(stm, Vector())
    }
  }

  @tailrec
  private def pngChunksTail(
      stm: InputStream,
      result: Vector[PngChunk]
  ): Vector[PngChunk] = {
    val chunkOpt = chunkFromStm(stm)
    chunkOpt match {
      case Some(chunk) => pngChunksTail(stm, result :+ chunk)
      case None        => result
    }
  }
}

object MyArtifactHandler {
  val chunksWeCareAbout = Array("eXIf", "iTXt", "tEXt", "zTXt")
  val pngHeader =
    Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
  lazy val latinCharset = Charset.forName("ISO-8859-1")
  lazy val utf8Charset = Charset.forName("UTF-8")

  private def intFromStmBigEndian(stm: InputStream): Option[Int] = {
    val buffer = stm.readNBytes(4)
    buffer.length match {
      case 4 => Some(intFromArrayBigEndian(buffer))
      case _ => None
    }
  }

  private def intFromArrayBigEndian(arr: Array[Byte]): Int = {
    (arr(0).toInt << 24) |
      ((arr(1).toInt & 0xff) << 16) |
      ((arr(2).toInt & 0xff) << 8) |
      (arr(3).toInt & 0xff)
  }

  def chunkFromStm(stm: InputStream): Option[PngChunk] = {
    val lenOpt = intFromStmBigEndian(stm)
    lenOpt match {
      case Some(len) => chunkOpt(stm, len)
      case _         => None
    }
  }

  private def chunkOpt(stm: InputStream, len: Int): Option[PngChunk] = {
    val fourCCData = stm.readNBytes(4)
    if (fourCCData.length != 4)
      return None
    val data = stm.readNBytes(len)
    if (data.length != len)
      return None
    val expectedCRCOpt = intFromStmBigEndian(stm)
    expectedCRCOpt match {
      case Some(expectedCrc) => {
        val longCRC = expectedCrc.toLong & 0xffffffffL
        val crcCalc = CRC32()
        crcCalc.update(fourCCData)
        crcCalc.update(data)
        val calculatedCrc = crcCalc.getValue()
        val fourCC = String(fourCCData, latinCharset)
        if calculatedCrc == longCRC then Some(PngChunk(fourCC, data)) else None
      }
      case None => None
    }
  }
}

class MyProcessItem(artifact: RodeoArtifact, purlAPI: PurlAPI)
    extends RodeoProcessItems {
  lazy val itemList = {
    val l = ArrayList[Pair[RodeoArtifact, RodeoItemMarker]]()
    l.add(Pair(artifact, MyItemMarker()))
    l
  }

  override def getItemsToProcess()
      : Pair[ju.List[Pair[RodeoArtifact, RodeoItemMarker]], ArtifactHandler] = {
    Pair(itemList, MyArtifactHandler(purlAPI))
  }

  override def length(): Int = 1

  override def onCompletion(artifact: RodeoArtifact): Unit = {}
}

class PngHandler(purlAPI: PurlAPI) extends RodeoProcessFilter {
  override def getName(): String = "PngHandler"
  override def filterByName(
      namesToFilter: ju.Map[String, ju.List[RodeoArtifact]]
  ): ju.List[Triple[String, RodeoArtifact, RodeoProcessItems]] = {
    if (PngHandler.isActive) {
      val names = namesToFilter.asScala
      val result =
        ju.ArrayList[Triple[String, RodeoArtifact, RodeoProcessItems]]()
      names.foreachEntry((name, artifacts) => {
        val scalaArtifacts = artifacts.asScala
        scalaArtifacts
          .filter(art => art.getMimeType() == "image/png")
          .foreach(art => {
            result.add(Triple(name, art, MyProcessItem(art, purlAPI)))
          })
      })

      result
    } else {
      ju.ArrayList[Triple[String, RodeoArtifact, RodeoProcessItems]]()
    }
  }
}

object PngHandler {
  var isActive = false
}

class PngComponent extends RodeoComponent {
  private lazy val _identity = SimpleIdentity("Png Handler", "Spice Labs, Inc")
  private var purlAPI: Option[PurlAPI] = None
  private var artifactAPI: Option[ArtifactHandlerRegistrar] = None

  override def initialize(): Unit = {}
  override def getIdentity(): RodeoIdentity = _identity
  override def getComponentVersion(): Version =
    RodeoEnvironment.currentVersion()
  override def exportAPIFactories(receiver: APIFactoryReceiver): Unit = {}
  override def importAPIFactories(factorySource: APIFactorySource): Unit = {
    purlAPI = makeAPI(
      factorySource
        .getAPIFactory(PurlAPIConstants.NAME, this, classOf[PurlAPI])
        .toScala
    )
    artifactAPI = makeAPI(
      factorySource
        .getAPIFactory(
          ArtifactConstants.NAME,
          this,
          classOf[ArtifactHandlerRegistrar]
        )
        .toScala
    )
  }

  override def onLoadingComplete(): Unit = {
    if (installFilter)
      purlAPI.foreach(pa =>
        artifactAPI.foreach(aa => aa.registerProcessFilter(PngHandler(pa)))
      )
  }

  override def shutDown(): Unit = {
    purlAPI.foreach(pa => pa.release())
    artifactAPI.foreach(a => a.release())
  }

  private def makeAPI[T <: API](
      factoryOpt: Option[APIFactory[T]]
  ): Option[T] = {
    factoryOpt match {
      case Some(factory) => Some(factory.buildAPI(this))
      case None          => None
    }
  }
}

object PngComponent {
  var installFilter = false
}

class PngArtifactTest extends munit.FunSuite {
  test("did we process a png") {
    PngComponent.installFilter = true
    PngHandler.isActive = true
    val host = RodeoHost.host
    host.begin()
    host.exportImport()
    host.completeLoading()
    val name = "test_data/png_tests/totoro.png"

    val nested = FileWrapper(File(name), name, None)
    val store1 =
      ToProcess.buildGraphFromArtifactWrapper(nested, args = Config())

    val result = store1.purls()
    host.end()
    ToProcess.resetComputeToProcess()
    PngComponent.installFilter = false
    PngHandler.isActive = false
    assertEquals(result.size, 1)
  }
}
