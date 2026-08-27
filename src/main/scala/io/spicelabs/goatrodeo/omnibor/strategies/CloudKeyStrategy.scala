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
import io.spicelabs.goatrodeo.util.CryptoContentDetector
import io.spicelabs.goatrodeo.util.GitOID

import java.nio.charset.StandardCharsets
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.util.Try

/** Inventories cloud-managed key references in configuration and IaC files: AWS
  * KMS (key/alias ARNs, `aws_kms_key`/`aws_kms_alias` Terraform resources),
  * Azure Key Vault (`vault.azure.net/keys/…` URLs, `azurerm_key_vault_key`
  * resources), GCP Cloud KMS (`projects/…/keyRings/…/ cryptoKeys/…` names,
  * `google_kms_crypto_key` resources), and HashiCorp Vault (`vault:` URLs,
  * `vault_transit_secret_backend_key` resources).
  *
  * Emits `CloudKey:` metadata: provider, resource id(s), declared key spec,
  * rotation period, purpose, and a **presence-only** flag for key material.
  *
  * Hard constraint: key material and credentials are never emitted. Terraform
  * `key_material` blobs are recorded as a presence flag only, and Vault URLs
  * are sanitized (query strings stripped) so tokens can never leak.
  *
  * Resource rules: config/IaC files only (no source-code scanning), all reads
  * via `ArtifactWrapper.withStream` (never `withFile`), bounded to
  * [[MaxReadBytes]].
  */
object CloudKeyStrategy {
  private val logger = Logger(getClass())

  /** Bound for the strategy's text read; matches the MIME-pass detector budget
    * so whatever was detected is re-parseable here.
    */
  val MaxReadBytes: Int = 256 * 1024

  /** Extensions that may carry cloud-managed key references. */
  private val KeyExtensions: Set[String] =
    Set(".tf", ".tf.json", ".yaml", ".yml", ".json", ".properties", ".env")

  /** Name gate for the MIME-pass detector (config/IaC files only). */
  private[goatrodeo] def cloudKeyName(name: String): Boolean =
    KeyExtensions.exists(name.endsWith)

  // ── Provider signatures (precompiled) ───────────────────────────────────

  private val AwsKmsKeyArn =
    "arn:aws:kms:[a-z0-9-]+:[0-9]{12}:key/[0-9a-fA-F-]+".r
  private val AwsKmsAliasArn =
    "arn:aws:kms:[a-z0-9-]+:[0-9]{12}:alias/[A-Za-z0-9/_-]+".r
  private val AzureKeyUrl =
    "https://[A-Za-z0-9.-]+\\.vault\\.azure\\.net/keys/[^\\s\"'<>]+".r
  private val GcpCryptoKey =
    "projects/[^\\s\"'<>]+/locations/[^\\s\"'<>]+/keyRings/[^\\s\"'<>]+/cryptoKeys/[^\\s\"'<>]+".r
  private val VaultUrl = "vault:[^\\s\"'<>]+".r
  private val TfResource =
    "resource\\s+\"(aws_kms_key|aws_kms_alias|azurerm_key_vault_key|google_kms_crypto_key|vault_transit_secret_backend_key)\"\\s+\"([^\"]*)\"".r
  private val TfKeySpec = "key_spec\\s*=\\s*\"([A-Z0-9_]+)\"".r
  private val TfRotation = "rotation_period\\s*=\\s*\"([^\"]+)\"".r
  private val TfPurpose = "purpose\\s*=\\s*\"([^\"]+)\"".r
  private val TfKeyMaterial = "key_material\\s*=\\s*\"[A-Za-z0-9+/=]*\"".r

  /** Cheap pre-guard: only text that mentions a provider marker is worth
    * probing with the regexes.
    */
  private def mentionsProvider(text: String): Boolean =
    text.contains("arn:") || text.contains("vault") || text.contains("kms") ||
      text.contains("keyRings") || text.contains("cryptoKeys")

  /** True when the text carries a cloud-managed key signature. */
  private[goatrodeo] def detects(text: String): Boolean =
    mentionsProvider(text) && (
      AwsKmsKeyArn.findFirstIn(text).isDefined ||
        AwsKmsAliasArn.findFirstIn(text).isDefined ||
        AzureKeyUrl.findFirstIn(text).isDefined ||
        GcpCryptoKey.findFirstIn(text).isDefined ||
        VaultUrl.findFirstIn(text).isDefined ||
        TfResource.findFirstIn(text).isDefined
    )

  /** Sanitize a Vault URL: drop the query string (which may carry tokens). */
  private def sanitizeUrl(url: String): String =
    url.takeWhile(_ != '?')

  /** Extract cloud-key references from config text. */
  private[strategies] def parse(text: String): ParsedCloudKeys = {
    val providers = Set.newBuilder[String]
    val resourceIds = Vector.newBuilder[String]
    val keySpecs = Set.newBuilder[String]
    val rotations = Set.newBuilder[String]
    val purposes = Set.newBuilder[String]
    var keyMaterialPresent = false

    if (mentionsProvider(text)) {
      if (AwsKmsKeyArn.findFirstIn(text).isDefined) {
        providers += "aws"
        AwsKmsKeyArn.findAllIn(text).foreach(resourceIds += _)
      }
      if (AwsKmsAliasArn.findFirstIn(text).isDefined) {
        providers += "aws"
        AwsKmsAliasArn.findAllIn(text).foreach(resourceIds += _)
      }
      if (AzureKeyUrl.findFirstIn(text).isDefined) {
        providers += "azure"
        AzureKeyUrl.findAllIn(text).foreach(u => resourceIds += sanitizeUrl(u))
      }
      if (GcpCryptoKey.findFirstIn(text).isDefined) {
        providers += "gcp"
        GcpCryptoKey.findAllIn(text).foreach(resourceIds += _)
      }
      if (VaultUrl.findFirstIn(text).isDefined) {
        providers += "vault"
        VaultUrl.findAllIn(text).foreach(u => resourceIds += sanitizeUrl(u))
      }
      TfResource.findAllMatchIn(text).foreach { m =>
        val tfType = Option(m.group(1)).getOrElse("")
        val tfName = Option(m.group(2)).getOrElse("")
        providers += (tfType match {
          case "aws_kms_key" | "aws_kms_alias"    => "aws"
          case "azurerm_key_vault_key"            => "azure"
          case "google_kms_crypto_key"            => "gcp"
          case "vault_transit_secret_backend_key" => "vault"
          case other                              => other
        })
        resourceIds += s"$tfType.$tfName"
      }
      TfKeySpec
        .findAllMatchIn(text)
        .foreach(m => keySpecs += Option(m.group(1)).getOrElse(""))
      TfRotation
        .findAllMatchIn(text)
        .foreach(m => rotations += Option(m.group(1)).getOrElse(""))
      TfPurpose
        .findAllMatchIn(text)
        .foreach(m => purposes += Option(m.group(1)).getOrElse(""))
      keyMaterialPresent = TfKeyMaterial.findFirstIn(text).isDefined
    }

    ParsedCloudKeys(
      providers = providers.result(),
      resourceIds = resourceIds.result().distinct,
      keySpecs = keySpecs.result(),
      rotations = rotations.result(),
      purposes = purposes.result(),
      keyMaterialPresent = keyMaterialPresent
    )
  }

  private[strategies] final case class ParsedCloudKeys(
      providers: Set[String],
      resourceIds: Vector[String],
      keySpecs: Set[String],
      rotations: Set[String],
      purposes: Set[String],
      keyMaterialPresent: Boolean
  ) {
    def isEmpty: Boolean =
      providers.isEmpty && resourceIds.isEmpty
  }

  /** Compute cloud-key config files to process at a layer. */
  def computeCloudKeyFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values
      .filter(a => a.mimeType.contains(CryptoContentDetector.CloudKeyMime))
      .toVector

    val uuids = mine.map(_.uuid).toSet
    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map(a => new CloudKeyToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "CloudKey"
    )
  }
}

class CloudKeyToProcess(val artifact: ArtifactWrapper) extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = CloudKeyState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new CloudKeyState(artifact)
}

class CloudKeyState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, CloudKeyState] {

  private val adHoc = MKC.adHoc("CloudKey")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): CloudKeyState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, CloudKeyState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], CloudKeyState) = {
    val meta = buildMetadata(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, CloudKeyState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): CloudKeyState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): CloudKeyState = this

  private def buildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val text = Try {
      artifact.withStream { stream =>
        val buf = new Array[Byte](CloudKeyStrategy.MaxReadBytes)
        val n = stream.read(buf, 0, CloudKeyStrategy.MaxReadBytes)
        if (n <= 0) Array.emptyByteArray
        else java.util.Arrays.copyOf(buf, n)
      }
    }.getOrElse(Array.emptyByteArray)

    val parsed = CloudKeyStrategy.parse(
      new String(text, StandardCharsets.UTF_8)
    )
    if (parsed.isEmpty) TreeMap.empty[String, TreeSet[StringOrPair]]
    else {
      var tm = TreeMap[String, TreeSet[StringOrPair]](
        adHoc("provider") -> TreeSet.from(
          parsed.providers.toVector.sorted.map(StringOrPair(_))
        )
      )
      if (parsed.resourceIds.nonEmpty) {
        tm = tm + (adHoc("resource_id") -> TreeSet.from(
          parsed.resourceIds.map(StringOrPair(_))
        ))
      }
      if (parsed.keySpecs.nonEmpty) {
        tm = tm + (adHoc("algorithm") -> TreeSet.from(
          parsed.keySpecs.toVector.sorted.map(StringOrPair(_))
        ))
      }
      if (parsed.rotations.nonEmpty) {
        tm = tm + (adHoc("rotation_period") -> TreeSet.from(
          parsed.rotations.toVector.sorted.map(StringOrPair(_))
        ))
      }
      if (parsed.purposes.nonEmpty) {
        tm = tm + (adHoc("purpose") -> TreeSet.from(
          parsed.purposes.toVector.sorted.map(StringOrPair(_))
        ))
      }
      if (parsed.keyMaterialPresent) {
        tm =
          tm + (adHoc("key_material_present") -> TreeSet(StringOrPair("true")))
      }
      tm
    }
  }

  /** Test-accessible alias for buildMetadata. */
  private[strategies] def invokeBuildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = buildMetadata(artifact)
}
