/* Copyright 2024-2026 David Pollak, Spice Labs, Inc. & Contributors

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

import io.spicelabs.rodeocomponents.APIS.mimes.MimeFileInputStreamIdentifier
import java.util.concurrent.atomic.AtomicReference
import io.spicelabs.rodeocomponents.APIS.mimes.MimeInputStreamIdentifier
import io.spicelabs.rodeocomponents.APIS.mimes.MimeIdentifierRegistrar
import io.spicelabs.rodeocomponents.APIFactory
import io.spicelabs.rodeocomponents.RodeoComponent
import io.spicelabs.rodeocomponents.APIS.mimes.MimeConstants

// This class represents the Mime identification API implementation

class MimeHandling { }

/** Represents internal methods for registering sets of MIME identifiers */
object MimeHandling {
  /** MIME identifiers for InputStream object */
  val inputStreamIdentifiers =
    AtomicReference[Vector[MimeInputStreamIdentifier]](Vector())
  /** MIME identifiers for FileInputStream objects */
  val fileStreamIdentifiers =
    AtomicReference[Vector[MimeFileInputStreamIdentifier]](Vector())

  /**
    * Add a new MimeInputStreamIdentifier to the existing set of MIME identifiers
    *
    * @param identifier an object to identify MIME types from an InputStream
    */
  def addInputStreamIdentifier(identifier: MimeInputStreamIdentifier) = {
    inputStreamIdentifiers.getAndUpdate(v => v :+ identifier)
    ()
  }

  /**
    * Add a new MimeFileInputStreamIdentifier to the existing set of MIME identifiers
    *
    * @param identifier an object to identify MIME types from a FileInputStream
    */
  def addFileInputStreamIdentifier(
      identifier: MimeFileInputStreamIdentifier
  ) = {
    fileStreamIdentifiers.getAndUpdate(v => v :+ identifier)
    ()
  }

  /**
    * An instance of the MimeAPI
    */
  val mimeAPI = LocalMimeAPI()
}

/**
  * Represents the local implementation of the MIME Identifier registrar API
  */
class LocalMimeAPI extends MimeIdentifierRegistrar {
  /**
    * Register a MIME Identifier for FileInputStreams
    *
    * @param mimeIdentifier an object to identify MIME types from a FileInputStream
    */
  override def register(mimeIdentifier: MimeFileInputStreamIdentifier): Unit =
    MimeHandling.addFileInputStreamIdentifier(mimeIdentifier)
  /**
    * Resiter a MIME Identifier for an InputStream
    *
    * @param mimeIdentifier an object to identify MIME types from an InputStream
    */
  override def register(mimeIdentifier: MimeInputStreamIdentifier): Unit =
    MimeHandling.addInputStreamIdentifier(mimeIdentifier)
  /**
    * Release the API
    */
  override def release(): Unit = {}
}

/**
  * A class that represents the factory for the MIME Identifier Registrar API
  */
class MimeAPIFactory extends APIFactory[MimeIdentifierRegistrar] {
  /**
    * Build an API for the MimeIdentifierRegistrar
    *
    * @param subscriber a subscriber to the API
    * @return an API for the MimeIdentifierRegistrar
    */
  override def buildAPI(subscriber: RodeoComponent): MimeIdentifierRegistrar =
    MimeHandling.mimeAPI
  /**
    * Get the name of the API
    *
    * @return the name of the MimeIdentifierRegsitrar API
    */
  override def name(): String = MimeConstants.NAME
}
