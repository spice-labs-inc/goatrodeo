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
import io.spicelabs.goatrodeo.util.CarvedCertAugmenter
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?

import java.util.Arrays
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Detects X.509 certificates carved (DER-encoded, at arbitrary offsets) out of
  * binary artifacts — firmware ELF sections, raw blobs.
  *
  * Claims purely by the `application/x-goatrodeo-carved-x509` MIME emitted
  * during the MIME pass (bounded 256 KB probe); processing performs the bounded
  * carve ([[CarvedCertAugmenter.carveCertificates]], 16 MiB) and emits per-cert
  * metadata through the existing `perCertMetadata` path under
  * `Certificates:Cert:<idx>:*` keys, so the CBOM emitter produces ordinary
  * certificate components — including `KeySize` (e.g. an RSA-1024 cert surfaces
  * with KeySize 1024).
  *
  * Safety: only fully parsed certificates are emitted; no raw bytes and no
  * private key material leave the artifact.
  */
object CarvedCertificatesStrategy {

  /** Compute artifacts carrying carved certificates. */
  def computeCarvedCertificateFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values
      .filter(_.mimeType.contains(CarvedCertAugmenter.CarvedMime))
      .toVector

    val uuids = mine.map(_.uuid).toSet

    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map(a => new CarvedCertificatesToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "CarvedCertificates"
    )
  }
}

class CarvedCertificatesToProcess(val artifact: ArtifactWrapper)
    extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = CarvedCertificatesState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new CarvedCertificatesState(artifact)
}

class CarvedCertificatesState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, CarvedCertificatesState] {

  private val adHoc = MKC.adHoc("Certificates")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): CarvedCertificatesState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, CarvedCertificatesState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], CarvedCertificatesState) = {
    val path = artifact.path()
    val fileName = path.split('/').lastOption.getOrElse(path)
    val (certs, capExceeded) = readAndCarve(artifact)

    var tm: TreeMap[String, TreeSet[StringOrPair]] =
      TreeMap[String, TreeSet[StringOrPair]]() +?
        Some(MKC.NAME -> TreeSet(StringOrPair(fileName))) +?
        Some(
          MKC.DESCRIPTION -> TreeSet(
            StringOrPair(
              "Binary artifact with carved X.509 certificate material"
            )
          )
        ) +?
        Some(
          adHoc("CarvedCertCount") -> TreeSet(
            StringOrPair(certs.length.toString)
          )
        ) +?
        (if (capExceeded)
           Some(
             adHoc("CarvedCertScanCapExceeded") -> TreeSet(
               StringOrPair("true")
             )
           )
         else None)

    certs.zipWithIndex.foreach { case (cert, idx) =>
      val certAdHoc: String => String =
        sub => MKC.adHoc("Certificates")(s"Cert:$idx:$sub")
      tm = tm ++ Certificates.perCertMetadata(certAdHoc, cert)
    }
    tm -> this
  }

  private def readAndCarve(
      artifact: ArtifactWrapper
  ): (Vector[java.security.cert.X509Certificate], Boolean) = {
    val bytes = Try {
      artifact.withStream { s =>
        val buf = new Array[Byte](CarvedCertAugmenter.MaxScanBytes)
        val n = s.read(buf, 0, CarvedCertAugmenter.MaxScanBytes)
        if (n <= 0) Array.emptyByteArray else Arrays.copyOf(buf, n)
      }
    }.getOrElse(Array.emptyByteArray)
    if (bytes.isEmpty) (Vector(), false)
    else
      CarvedCertAugmenter.carveCertificates(
        bytes,
        CarvedCertAugmenter.MaxScanBytes,
        CarvedCertAugmenter.MaxCerts
      )
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, CarvedCertificatesState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): CarvedCertificatesState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): CarvedCertificatesState = this
}
