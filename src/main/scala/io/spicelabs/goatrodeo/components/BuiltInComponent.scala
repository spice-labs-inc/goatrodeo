/* Copyright 2025-2026 David Pollak, Steve Hawley, Spice Labs, Inc. & Contributors

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

import io.spicelabs.rodeocomponents.APIFactoryReceiver
import io.spicelabs.rodeocomponents.APIFactorySource
import io.spicelabs.rodeocomponents.APIS.arguments.RodeoArgumentRegistrar
import io.spicelabs.rodeocomponents.APIS.logging.RodeoLogger
import io.spicelabs.rodeocomponents.APIS.purls.PurlAPI
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.RodeoEnvironment
import io.spicelabs.rodeocomponents.RodeoIdentity

import java.lang.Runtime.Version
import io.spicelabs.rodeocomponents.APIS.mimes.MimeIdentifierRegistrar

// Goat Rodeo exposes its own component which publishes APIs.
// In this sense, Goat Rodeo is no different from other components.
// There is one key difference between Goat Rodeo's BuiltInComponent and
// other components: the Goat Rodeo built-in component always will be first
// in the list of components and will get called first to export APIs.
// The practical result of this is that plug-in components can't export
// APIs that masquerade as Goat Rodeo APIs.


/**
  * The identify of the BuiltInComponent. 
  */
private class BuiltInIdentity extends RodeoIdentity {
  /**
    * The name of the BuiltInComponent
    *
    * @return the name of the BuiltInComponent
    */
  override def name(): String = "GoatRodeoComponent"
  /**
    * The publisher of the BuiltInComponent
    *
    * @return the publisher of the BuiltInComponent
    */
  override def publisher(): String = "Spice Labs, Inc."
}

/**
  * The BuiltInComponent provided by Goat Rodeo to export API factories.
  */
class BuiltInComponent extends RodeoComponent {
  private lazy val _identity: RodeoIdentity = BuiltInIdentity()
  private lazy val _version: Version = RodeoEnvironment.currentVersion()

  /**
    * Gets the identity of the BuiltInComponent. There is no reason to
    * call this directly.
    *
    * @return the identity of the BuiltInComponent
    */
  override def getIdentity(): RodeoIdentity = _identity
  /**
    * Gets the version of the Component API that this component uses. There is
    * no reason to call this directly.
    *
    * @return the version of the Component API that this component uses
    */
  override def getComponentVersion(): Version = _version
  /**
    * Export API factories. There is no reason to call this directly.
    *
    * @param receiver a receiver to hold the factory
    */
  override def exportAPIFactories(receiver: APIFactoryReceiver): Unit = {
    // currently published APIs for:
    // - handling command line arguments
    // - logging
    // - making PURLs
    // - identifying MIME types
    val argRegistrar = ArgumentsFactory()
    receiver.publishFactory(
      this,
      argRegistrar.name(),
      argRegistrar,
      classOf[RodeoArgumentRegistrar]
    )

    val logger = LoggingAPIFactory()
    receiver.publishFactory(this, logger.name(), logger, classOf[RodeoLogger])

    val purls = PurlsAPIFactory()
    receiver.publishFactory(this, purls.name(), purls, classOf[PurlAPI])

    val mimes = MimeAPIFactory()
    receiver.publishFactory(
      this,
      mimes.name(),
      mimes,
      classOf[MimeIdentifierRegistrar]
    )
  }

  /**
    * Import API factories from other components. There is no reason to call this directly.
    * The BuiltInComponent does not import any factories.
    *
    * @param factorySource
    */
  override def importAPIFactories(factorySource: APIFactorySource): Unit = {}
  /**
    * Initializes the BuiltInComponent. There is no reason to call this directly.
    */
  override def initialize(): Unit = {}
  /**
    * Allows the BuiltInComponent to perform any needed late work. There is no reason to call
    * this directly.
    */
  override def onLoadingComplete(): Unit = {}
  /**
    * Allows the BuiltInComponent to release an resources that its holding onto. There is no reason
    * to call this directly.
    */
  override def shutDown(): Unit = {}
}

object BuiltInComponent {
  /**
    * provides a singleton for the BuiltInComponent.
    */
  lazy val component = BuiltInComponent()
}
