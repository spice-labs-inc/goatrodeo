package io.spicelabs.goatrodeo.components

import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactHandlerRegistrar
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoProcessFilter
import io.spicelabs.goatrodeo.omnibor.strategies.ComponentFile
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.APIFactory
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactConstants

/** This implements the ArtifactHandlerRegistrar API. This allows a component to
  * insert itself into the chain of strategies for handling artifacts
  */
class ArtifactHandling extends ArtifactHandlerRegistrar {
  override def registerProcessFilter(filter: RodeoProcessFilter) = {

    // computeComponentFiles has an extra argument at the beginning that we curry
    // into a function that we will use in the strategies list
    val partialFunc = ComponentFile.computeComponentFiles(filter)
    ToProcess.addNewToProcessComputer(partialFunc)
  }

  override def release(): Unit = {}
}

object ArtifactHandling {
  // we only need one registrar, so a singleton is just fine
  lazy val registrar = ArtifactHandling()
}

class ArtifactHandlerFactory extends APIFactory[ArtifactHandlerRegistrar] {
  override def buildAPI(
      subscriber: RodeoComponent
  ): ArtifactHandlerRegistrar = {
    ArtifactHandling.registrar
  }

  override def name(): String = ArtifactConstants.NAME
}
