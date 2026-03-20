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

import io.spicelabs.rodeocomponents.APIS.containers.ContainerAPI
import io.spicelabs.rodeocomponents.APIS.containers.ContainerFactory
import io.spicelabs.goatrodeo.util.Containers
import io.spicelabs.goatrodeo.util.ComponentContainerFactoryAdapter
import java.{util => ju}
import scala.jdk.CollectionConverters._
import io.spicelabs.rodeocomponents.APIFactory
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.APIS.containers.ContainerConstants

class ComponentContainers extends ContainerAPI {
  override def registerContainerFactory(factory: ContainerFactory): Unit = {
    Containers.addContainerFactory(ComponentContainerFactoryAdapter(factory))
  }

  override def removeContainerFactory(name: String): Unit =
    Containers.removeContainerFactory(name)

  override def getContainerFactoryNames(): ju.List[String] = {
    val containerNames = Containers.factoryNames()
    containerNames.asJava
  }

  override def release(): Unit = {}
}

object ComponentContainers {
  lazy val registrar = ComponentContainers()
}

class ComponentContainersFactory extends APIFactory[ComponentContainers] {
  override def buildAPI(subscriber: RodeoComponent): ComponentContainers = {
    ComponentContainers.registrar
  }

  override def name(): String = ContainerConstants.NAME
}
