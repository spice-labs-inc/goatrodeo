package io.spicelabs.goatrodeo.components.testing

import io.spicelabs.goatrodeo.components.RodeoHost
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.APIFactoryReceiver
import io.spicelabs.rodeocomponents.APIFactorySource
import io.spicelabs.rodeocomponents.RodeoIdentity
import java.lang.Runtime.Version
import io.spicelabs.rodeocomponents.RodeoEnvironment

class SimpleIdentity(private val _name: String, private val _publisher: String) extends RodeoIdentity {
    override def name(): String = _name
    override def publisher(): String = _publisher
}

class SmokeTestComponent extends RodeoComponent {
    private lazy val _identity = SimpleIdentity("SmokeTest", "ComponentTests")
    override def initialize(): Unit = SmokeTestComponent.initialized = true
    override def getIdentity(): RodeoIdentity = _identity
    override def getComponentVersion(): Version = RodeoEnvironment.currentVersion()
    override def exportAPIFactories(receiver: APIFactoryReceiver): Unit = SmokeTestComponent.exported = true
    override def importAPIFactories(factorySource: APIFactorySource): Unit = SmokeTestComponent.imported = true
    override def onLoadingComplete(): Unit = SmokeTestComponent.loadingCompleted = true
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

class ComponentTests extends munit.FunSuite {

    test("basic smoke") {
        val host = RodeoHost()
        host.begin()
        assertEquals(host.activeComponentCount, 2)
        host.end()
    }

    test("smoke-import-export") {
        val host = RodeoHost()
        host.begin()
        host.exportImport()
        assertEquals(host.publishedAPICount, 2)
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
}
