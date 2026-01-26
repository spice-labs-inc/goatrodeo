package io.spicelabs.goatrodeo.components.testing

import io.spicelabs.goatrodeo.components.PurlAdapter
import io.spicelabs.goatrodeo.components.RodeoHost
import io.spicelabs.rodeocomponents.APIFactoryReceiver
import io.spicelabs.rodeocomponents.APIFactorySource
import io.spicelabs.rodeocomponents.APIS.purls.PurlAPI
import io.spicelabs.rodeocomponents.APIS.purls.PurlAPIConstants
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.RodeoEnvironment
import io.spicelabs.rodeocomponents.RodeoIdentity

import java.lang.Runtime.Version
import java.net.MalformedURLException
import scala.jdk.OptionConverters.*
import io.spicelabs.rodeocomponents.APIS.mimes.MimeConstants
import io.spicelabs.rodeocomponents.APIS.mimes.MimeIdentifierRegistrar
import io.spicelabs.goatrodeo.components.testing.MimeComponent.componetLoaded
import io.spicelabs.rodeocomponents.APIS.mimes.MimeInputStreamIdentifier
import java.io.InputStream
import java.{util => ju}
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import org.apache.tika.io.TikaInputStream
import java.io.File
import io.spicelabs.rodeocomponents.APIS.mimes.MimeFileInputStreamIdentifier
import java.io.FileInputStream

class SimpleIdentity(private val _name: String, private val _publisher: String)
    extends RodeoIdentity {
  override def name(): String = _name
  override def publisher(): String = _publisher
}

// If you feel inclined to rename this class, you MUST modify the entry in:
// resources/META-INF/services/io.spicelabs.rodeocomponents.RodeoComponent
// The consequences of renaming this class without the change in the resource
// is that java's component system will not load this class as a component and
// the tests will fail.
class SmokeTestComponent extends RodeoComponent {
  private lazy val _identity = SimpleIdentity("SmokeTest", "ComponentTests")
  override def initialize(): Unit = SmokeTestComponent.initialized = true
  override def getIdentity(): RodeoIdentity = _identity
  override def getComponentVersion(): Version =
    RodeoEnvironment.currentVersion()
  override def exportAPIFactories(receiver: APIFactoryReceiver): Unit =
    SmokeTestComponent.exported = true
  override def importAPIFactories(factorySource: APIFactorySource): Unit =
    SmokeTestComponent.imported = true
  override def onLoadingComplete(): Unit = SmokeTestComponent.loadingCompleted =
    true
  override def shutDown(): Unit = SmokeTestComponent.shutdown = true
}

object SmokeTestComponent {
  var initialized = false
  var exported = false
  var imported = false
  var loadingCompleted = false
  var shutdown = false
  def reset() = {
    initialized = false
    exported = false
    imported = false
    loadingCompleted = false
    shutdown = false
  }
}

// If you feel inclined to rename this class, you MUST modify the entry in:
// resources/META-INF/services/io.spicelabs.rodeocomponents.RodeoComponent
// The consequences of renaming this class without the change in the resource
// is that java's component system will not load this class as a component and
// the tests will fail.
class PurlComponent extends RodeoComponent {
  private var purlAPI: Option[PurlAPI] = None
  private lazy val _identity = SimpleIdentity("PurlTest", "ComponentTests")
  override def initialize(): Unit = {}
  override def getIdentity(): RodeoIdentity = _identity
  override def getComponentVersion(): Version =
    RodeoEnvironment.currentVersion()
  override def exportAPIFactories(receiver: APIFactoryReceiver): Unit = {}
  override def importAPIFactories(factorySource: APIFactorySource): Unit = {
    val purlAPIFactory = factorySource
      .getAPIFactory(PurlAPIConstants.NAME, this, classOf[PurlAPI])
      .toScala
    purlAPI = purlAPIFactory.map(paf => paf.buildAPI(this))
  }
  override def onLoadingComplete(): Unit = {
    purlAPI.map(api => {
      val factory = api
        .newPurlFactory()
        .withType("maven")
        .withName("rodeo-components")
        .withNamespace("spice-labs")
      try {
        val purlAdapter = factory.toPurl().asInstanceOf[PurlAdapter]
        val stringRep = purlAdapter.purl.toString()
        PurlComponent.correctPurl =
          stringRep == "pkg:maven/spice-labs/rodeo-components"
      } catch {
        case badURL: MalformedURLException =>
          PurlComponent.exMessage = badURL.getMessage()
        case other: Throwable => PurlComponent.exMessage = other.getMessage()
      }
    })
  }
  override def shutDown(): Unit = {
    purlAPI.map(pa => pa.release())
  }
}

object PurlComponent {
  var correctPurl = false
  var exMessage = ""
}

class MyMimeISIdentifier extends MimeInputStreamIdentifier {
  override def preferredHeaderLength(): Int = 10
  override def canHandleHeader(header: Array[Byte]): Boolean = {
    val result = header.startsWith(MyMimeISIdentifier.myHeader)
    result
  }
  override def identifyMimeType(
      stream: InputStream,
      mimeSoFar: String
  ): ju.Optional[String] = {
    val header = stream.readNBytes(MyMimeISIdentifier.myHeader.length)
    if (header.startsWith(MyMimeISIdentifier.myHeader)) {
      ju.Optional.of("application/postscript; format=kinda")
    } else {
      ju.Optional.empty()
    }
  }
}

object MyMimeISIdentifier {
  lazy val myHeader = Array(
    '%'.toByte,
    '!'.toByte,
    's'.toByte,
    'o'.toByte,
    'r'.toByte,
    't'.toByte,
    'a'.toByte,
    '-'.toByte,
    'p'.toByte,
    's'.toByte
  )
}

class MyMimeFSIdentifier extends MimeFileInputStreamIdentifier {
  override def preferredHeaderLength(): Int = 5
  override def canHandleHeader(header: Array[Byte]): Boolean = {
    val result = header.startsWith(MyMimeFSIdentifier.myHeader)
    result
  }
  override def identifyMimeType(
      stream: FileInputStream,
      mimeSoFar: String
  ): ju.Optional[String] = {
    val header = stream.readNBytes(MyMimeFSIdentifier.myHeader.length)
    if (header.startsWith(MyMimeFSIdentifier.myHeader)) {
      ju.Optional.of("text/c-sharp; format=sorta")
    } else {
      ju.Optional.empty()
    }
  }
}

object MyMimeFSIdentifier {
  lazy val myHeader =
    Array('/'.toByte, '/'.toByte, ' '.toByte, 'C'.toByte, '#'.toByte)
}

class MimeComponent extends RodeoComponent {
  private var mimeAPI: Option[MimeIdentifierRegistrar] = None
  private lazy val _identity = SimpleIdentity("MimeTest", "ComponentTests")

  override def initialize(): Unit = { componetLoaded = true }
  override def getIdentity(): RodeoIdentity = _identity
  override def getComponentVersion(): Version =
    RodeoEnvironment.currentVersion()
  override def exportAPIFactories(receiver: APIFactoryReceiver): Unit = {}
  override def importAPIFactories(factorySource: APIFactorySource): Unit = {
    val mimeAPIFactory = factorySource
      .getAPIFactory(MimeConstants.NAME, this, classOf[MimeIdentifierRegistrar])
      .toScala
    mimeAPI = mimeAPIFactory.map(maf => maf.buildAPI(this))
    MimeComponent.apiLoaded = mimeAPI.isDefined
  }
  override def onLoadingComplete(): Unit = {
    mimeAPI.foreach(api => {
      api.register(MyMimeISIdentifier())
      api.register(MyMimeFSIdentifier())
    })
  }
  override def shutDown(): Unit = {
    mimeAPI.foreach(api => api.release())
  }
}

object MimeComponent {
  var componetLoaded = false
  var apiLoaded = false
}

class ComponentTests extends munit.FunSuite {

  test("basic smoke") {
    val host = RodeoHost()
    host.begin()
    assertEquals(host.activeComponentCount, 4)
    host.end()
  }

  test("smoke-import-export") {
    val host = RodeoHost()
    host.begin()
    host.exportImport()
    assertEquals(host.publishedAPICount, 4)
    host.end()
  }

  test("smoke-performs-functions") {
    val host = RodeoHost()
    SmokeTestComponent.reset()
    host.begin()
    assert(SmokeTestComponent.initialized)
    host.exportImport()
    assert(SmokeTestComponent.exported)
    assert(SmokeTestComponent.imported)
    host.completeLoading()
    assert(SmokeTestComponent.loadingCompleted)
    host.end()
    assert(SmokeTestComponent.shutdown)
  }

  test("smoke-does-purls") {
    val host = RodeoHost()
    SmokeTestComponent.reset()
    host.begin()
    host.exportImport()
    host.completeLoading()
    host.end()
    assert(PurlComponent.correctPurl)
  }

  test("smoke-loads-mime") {
    val host = RodeoHost()
    SmokeTestComponent.reset()
    host.begin()
    host.exportImport()
    host.completeLoading()
    host.end()
    assert(MimeComponent.componetLoaded)
    assert(MimeComponent.apiLoaded)
  }

  test("identifies-mime") {
    val host = RodeoHost()
    SmokeTestComponent.reset()
    host.begin()
    host.exportImport()
    host.completeLoading()
    val path = File("test_data/sorta.ps").toPath()
    val tika = TikaInputStream.get(path)
    val mime = ArtifactWrapper.mimeTypeFor(tika, "sorta.ps")
    host.end()
    assertEquals("application/postscript; format=kinda", mime)
  }

  test("identifies-mime-with-file") {
    val host = RodeoHost()
    SmokeTestComponent.reset()
    host.begin()
    host.exportImport()
    host.completeLoading()
    val path = File("test_data/sorta.cs").toPath()
    val tika = TikaInputStream.get(path)
    val mime = ArtifactWrapper.mimeTypeFor(tika, "sorta.cs")
    host.end()
    assertEquals("text/c-sharp; format=sorta", mime)
  }
}
