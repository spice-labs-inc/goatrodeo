/* Copyright 2025 David Pollak, Steve Hawley Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.components

import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.RodeoIdentity
import io.spicelabs.rodeocomponents.APIFactoryReceiver
import java.lang.Runtime.Version
import io.spicelabs.rodeocomponents.RodeoEnvironment
import io.spicelabs.rodeocomponents.APIFactorySource
import io.spicelabs.rodeocomponents.APIS.logging.RodeoLogger
import io.spicelabs.rodeocomponents.APIS.arguments.RodeoArgumentRegistrar
import io.spicelabs.rodeocomponents.APIS.purls.PurlAPI

private class BuiltInIdentity extends RodeoIdentity {
    override def name(): String = "GoatRodeoComponent"
    override def publisher(): String = "Spice Labs, Inc."
}

class BuiltInComponent extends RodeoComponent {
    private lazy val _identity: RodeoIdentity = BuiltInIdentity()
    private lazy val _version: Version = RodeoEnvironment.currentVersion()

    override def getIdentity(): RodeoIdentity = _identity
    override def getComponentVersion(): Version = _version
    override def exportAPIFactories(receiver: APIFactoryReceiver): Unit = {
        val argRegistrar = ArgumentsFactory()
        receiver.publishFactory(this, argRegistrar.name(), argRegistrar, classOf[RodeoArgumentRegistrar])
        
        val logger = LoggingAPIFactory()
        receiver.publishFactory(this, logger.name(), logger, classOf[RodeoLogger])

        val purls = PurlsAPIFactory()
        receiver.publishFactory(this, purls.name(), purls, classOf[PurlAPI])
    }
    override def importAPIFactories(factorySource: APIFactorySource): Unit = { }
    override def initialize(): Unit = { }
    override def onLoadingComplete(): Unit = { }
    override def shutDown(): Unit = { }
}

object BuiltInComponent {
    lazy val component = BuiltInComponent()
}
