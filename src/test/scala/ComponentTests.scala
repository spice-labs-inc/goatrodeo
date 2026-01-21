package io.spicelabs.goatrodeo.components.testing

import io.spicelabs.goatrodeo.components.RodeoHost
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.APIFactoryReceiver
import io.spicelabs.rodeocomponents.APIFactorySource
import io.spicelabs.rodeocomponents.RodeoIdentity
import java.lang.Runtime.Version
import io.spicelabs.rodeocomponents.RodeoEnvironment
import io.spicelabs.rodeocomponents.APIS.purls.PurlAPIConstants
import io.spicelabs.rodeocomponents.APIS.purls.PurlAPI
import java.util.Optional
import scala.jdk.OptionConverters.*
import io.spicelabs.goatrodeo.components.PurlAdapter
import java.net.MalformedURLException
import io.spicelabs.goatrodeo.components.testing.PurlComponent.exMessage

class SimpleIdentity(private val _name: String, private val _publisher: String)
    extends RodeoIdentity {
  override def name(): String = _name
  override def publisher(): String = _publisher
}

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

class ComponentTests extends munit.FunSuite {

  test("basic smoke") {
    val host = RodeoHost()
    host.begin()
    assertEquals(host.activeComponentCount, 3)
    host.end()
  }

  test("smoke-import-export") {
    val host = RodeoHost()
    host.begin()
    host.exportImport()
    assertEquals(host.publishedAPICount, 3)
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

}
