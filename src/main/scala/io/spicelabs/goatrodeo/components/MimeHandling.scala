package io.spicelabs.goatrodeo.components

import io.spicelabs.rodeocomponents.APIS.mimes.MimeFileInputStreamIdentifier
import java.util.concurrent.atomic.AtomicReference
import io.spicelabs.rodeocomponents.APIS.mimes.MimeInputStreamIdentifier
import io.spicelabs.rodeocomponents.APIS.mimes.MimeIdentifierRegistrar
import io.spicelabs.rodeocomponents.APIFactory
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.APIS.mimes.MimeConstants

class MimeHandling {}

object MimeHandling {
  val inputStreamIdentifiers =
    AtomicReference[Vector[MimeInputStreamIdentifier]](Vector())
  val fileStreamIdentifiers =
    AtomicReference[Vector[MimeFileInputStreamIdentifier]](Vector())

  def addInputStreamIdentifier(identifier: MimeInputStreamIdentifier) = {
    inputStreamIdentifiers.getAndUpdate(v => v :+ identifier)
    ()
  }

  def addFileInputStreamIdentifier(
      identifier: MimeFileInputStreamIdentifier
  ) = {
    fileStreamIdentifiers.getAndUpdate(v => v :+ identifier)
    ()
  }

  val mimeAPI = LocalMimeAPI()
}

class LocalMimeAPI extends MimeIdentifierRegistrar {
  override def register(mimeIdentifier: MimeFileInputStreamIdentifier): Unit =
    MimeHandling.addFileInputStreamIdentifier(mimeIdentifier)
  override def register(mimeIdentifier: MimeInputStreamIdentifier): Unit =
    MimeHandling.addInputStreamIdentifier(mimeIdentifier)
  override def release(): Unit = {}
}

class MimeAPIFactory extends APIFactory[MimeIdentifierRegistrar] {
  override def buildAPI(subscriber: RodeoComponent): MimeIdentifierRegistrar =
    MimeHandling.mimeAPI
  override def name(): String = MimeConstants.NAME
}
