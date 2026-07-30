/* Copyright 2026 David Pollak, Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.goatrodeo.omnibor.strategies

import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants as MKC
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.PurlSet
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.Helpers

import java.nio.charset.StandardCharsets
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects shared libraries and other binary objects that contain embedded
  * certificate material (PEM delimiters, DER-encoded X.509 structures, or known
  * X.509 API strings). This is common in TLS/Crypto libraries such as mbed TLS,
  * OpenSSL, wolfSSL, and GnuTLS that ship certificate handling code.
  */
object EmbeddedCertificatesStrategy {
  private val logger = Logger(this.getClass())

  private val BinaryMimes: Set[String] = Set(
    "application/x-sharedlib",
    "application/x-executable",
    "application/x-pie-executable",
    "application/x-object",
    "application/octet-stream"
  )

  private val LibraryPathPatterns: Seq[String] = Seq(
    "/lib/",
    "/lib64/",
    "/usr/lib/",
    "/usr/lib64/",
    "/usr/local/lib/",
    "/usr/local/lib64/"
  )

  private val CryptoLibraryNamePatterns: Seq[String] = Seq(
    "mbedtls",
    "mbedx509",
    "mbedcrypto",
    "libcrypto",
    "libssl",
    "libtls",
    "wolfssl",
    "gnutls",
    "nss3",
    "nssutil",
    "libssh",
    "libssh2",
    "libdropbear"
  )

  private val PemMarkers: Seq[String] = Seq(
    "-----BEGIN CERTIFICATE-----",
    "-----END CERTIFICATE-----",
    "-----BEGIN X509 CRL-----",
    "-----END X509 CRL-----",
    "X509Certificate",
    "CertificateFactory"
  )

  /** Compute binary files that contain embedded certificate material. */
  def computeEmbeddedCertificateFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values.filter { artifact =>
      isBinaryArtifact(artifact) && hasEmbeddedCertificateMarkers(artifact)
    }.toVector

    val uuids = mine.map(_.uuid).toSet

    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map(a => new EmbeddedCertificateToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "EmbeddedCertificates"
    )
  }

  private def isBinaryArtifact(artifact: ArtifactWrapper): Boolean = {
    val path = artifact.path().toLowerCase
    val mimes = artifact.mimeType
    val inLibDir = LibraryPathPatterns.exists(p => path.contains(p))
    val knownCryptoLib = CryptoLibraryNamePatterns.exists(path.contains)
    val binaryMime = BinaryMimes.exists(mimes.contains)
    (binaryMime && inLibDir) || knownCryptoLib
  }

  private def hasEmbeddedCertificateMarkers(
      artifact: ArtifactWrapper
  ): Boolean = {
    Try {
      artifact.withStream { stream =>
        val prefix = new String(
          Helpers.slurpInput(stream),
          StandardCharsets.ISO_8859_1
        )
        PemMarkers.exists(prefix.contains)
      }
    }.getOrElse(false)
  }
}

class EmbeddedCertificateToProcess(val artifact: ArtifactWrapper)
    extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = EmbeddedCertificateState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new EmbeddedCertificateState(artifact)
}

class EmbeddedCertificateState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, EmbeddedCertificateState] {

  private val adHoc = MKC.adHoc("EmbeddedCertificates")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): EmbeddedCertificateState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, EmbeddedCertificateState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], EmbeddedCertificateState) = {
    val meta = buildMetadata(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, EmbeddedCertificateState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): EmbeddedCertificateState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): EmbeddedCertificateState = this

  private def buildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val path = artifact.path()
    val fileName = path.split('/').lastOption.getOrElse(path)
    TreeMap[String, TreeSet[StringOrPair]](
      MKC.NAME -> TreeSet(StringOrPair(fileName)),
      MKC.DESCRIPTION -> TreeSet(
        StringOrPair("Shared library containing embedded certificate material")
      ),
      adHoc("FilePath") -> TreeSet(StringOrPair("/" + path)),
      adHoc("Marker") -> TreeSet(
        StringOrPair("PEM certificate delimiters")
      )
    )
  }
}
