package io.spicelabs.goatrodeo.omnibor.strategies

import com.typesafe.scalalogging.Logger
import io.spicelabs.goatrodeo.omnibor.*
import io.spicelabs.goatrodeo.util.*
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import org.json4s.*
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.{compact, render}

import java.io.ByteArrayInputStream
import java.security.cert.{CertificateFactory, X509Certificate}
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Strategy for iOS app bundles (`.ipa` and unpacked `.app` trees).
  *
  * An `.ipa` is a ZIP containing `Payload/<App>.app/` with a Mach-O
  * executable, `Info.plist` (often binary bplist00), and
  * `embedded.mobileprovision` (a CMS/PKCS#7 SignedData whose payload is a
  * plist of entitlements and whose certificate chain is the signing
  * chain).
  *
  * This strategy:
  *
  *   - claims `.ipa` containers (path ends `.ipa`, MIME is zip) and, when
  *     the bundle appears unpacked, the bare bundle members
  *     (`Payload/**/*.app/Info.plist`, `embedded.mobileprovision`);
  *   - parses `Info.plist` (binary or XML) for bundle identity
  *     (CFBundleIdentifier, CFBundleShortVersionString, CFBundleVersion,
  *     CFBundleName, MinimumOSVersion);
  *   - parses `embedded.mobileprovision` CMS SignedData via BouncyCastle
  *     `CMSSignedData` (`X509CertificateHolder`, chain capped at 32) so the
  *     signing chain surfaces as certificate Items using the Certificates
  *     metadata shape (one code path owns cert semantics:
  *     `Certificates.perCertMetadata`), and extracts the payload plist
  *     (TeamIdentifier, AppIDName, ExpirationDate, entitlements,
  *     ProvisionedDevices count);
  *   - accumulates the parsed metadata onto the container Item via the
  *     Dotnet/Maven parent-scope pattern: `accumulateInfo` harvests direct
  *     children during child processing, and
  *     `applyAccumulatedAugmentation` performs the single `store.write`
  *     merge after the children are done.
  *
  * Selection uses only path/MIME/size (the selection boundary); all
  * content reads happen in processing.
  */
object IpaStrategy {

  private[strategies] val logger = Logger(getClass())

  /** The metadata prefix for ipa keys. */
  private[strategies] val ipaAdHoc = MetadataKeyConstants.adHoc("ipa")

  /** MIME of a zip container (the massage turns .ipa into this). */
  private[strategies] val zipMime = "application/zip"

  /** Maximum number of certificates accepted from a mobileprovision CMS
    * chain (chain-bomb DoS guard).
    */
  val MaxCertChain: Int = 32

  /** Maximum bytes read from a plist or CMS payload. */
  val MaxReadBytes: Int = 16 * 1024 * 1024

  /** Claim the .ipa containers and bare bundle members.
    *
    * Selection uses only path/MIME/size. The `.ipa` container must be a
    * zip (the ArtifactWrapper massage normalizes it). The bare bundle
    * members are claimed only when they appear inside a
    * `Payload/**/*.app/` path and are NOT inside a claimed .ipa (containers
    * take precedence).
    */
  def computeIpaFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    // 1. Claim .ipa containers.
    val ipaContainers: Vector[ArtifactWrapper] = byUUID.values
      .filter { a =>
        a.path().toLowerCase.endsWith(".ipa") &&
        a.mimeType.contains(zipMime)
      }
      .toVector

    val claimedUuids = ipaContainers.map(_.uuid).toSet

    // 2. Claim bare bundle members (unpacked .app trees).
    // Only when they are NOT under a claimed .ipa container (the container
    // absorbs its own children during processing).
    val bareMembers: Vector[ArtifactWrapper] = byUUID.values
      .filter { a =>
        !claimedUuids.contains(a.uuid) &&
        isBundleMemberPath(a.path())
      }
      .toVector

    val allUuids = claimedUuids ++ bareMembers.map(_.uuid).toSet

    val toProcess: Vector[ToProcess] =
      ipaContainers.map(a => new IpaToProcess(a)) ++
        bareMembers.map(a => new IpaToProcess(a))

    (
      toProcess,
      byUUID.filter { case (u, _) => !allUuids.contains(u) },
      byName.filter { case (_, as) =>
        !as.exists(a => allUuids.contains(a.uuid))
      },
      "Ipa"
    )
  }

  /** Is this a bundle member path (Payload/<app>.app/Info.plist or
    * embedded.mobileprovision) — the unpacked-app distribution form?
    */
  private[strategies] def isBundleMemberPath(path: String): Boolean = {
    val parts = path.split("/").toVector
    val hasPayloadApp = parts.contains("Payload") &&
      parts.exists(p => p.endsWith(".app"))
    val isKeyFile = path.endsWith("Info.plist") ||
      path.endsWith("embedded.mobileprovision")
    hasPayloadApp && isKeyFile
  }
}

/** A claimed iOS artifact (an .ipa container or a bare bundle member). */
class IpaToProcess(val artifact: ArtifactWrapper) extends ToProcess {
  type MarkerType = IpaMarkers
  type StateType = IpaState

  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  override def markSuccessfulCompletion(): Unit = artifact.finished()

  override def itemCnt: Int = 1

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> new IpaMarkers()) -> IpaState()
}

/** Marker type for the Ipa strategy: a single container/member item. */
final class IpaMarkers extends ProcessingMarker

/** Processing state for the Ipa strategy.
  *
  * The accumulated metadata (bundle identity + provisioning) is harvested
  * from direct children via [[accumulate]] during child processing and
  * merged onto the container Item in
  * [[applyAccumulatedAugmentation]] after all children are done
  * (Dotnet/Maven pattern). One mutable accumulator, written by the single
  * thread processing the container item.
  */
final case class IpaState(
    var bundleIdentifier: Option[String] = None,
    var bundleShortVersion: Option[String] = None,
    var bundleVersion: Option[String] = None,
    var bundleName: Option[String] = None,
    var minimumOS: Option[String] = None,
    var teamIdentifier: Option[String] = None,
    var appIDName: Option[String] = None,
    var provisioningExpiration: Option[String] = None,
    var entitlementsJson: Option[String] = None,
    var provisionedDeviceCount: Option[String] = None,
    var certificates: Vector[X509Certificate] = Vector.empty
) extends ProcessingState[IpaMarkers, IpaState] {

  private val ipaAdHoc = IpaStrategy.ipaAdHoc

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: IpaMarkers
  ): IpaState = {
    // Bare bundle members (a claimed Info.plist / embedded.mobileprovision
    // with no enclosing .ipa container) have no children to harvest from —
    // the artifact IS the metadata source. Self-accumulate; for .ipa
    // containers this is a no-op (their paths end in .ipa, failing the
    // app-bundle-member guard).
    accumulate(artifact)
    this
  }

  /** Identity metadata is surfaced via the accumulation; no per-item purls
    * here (pURLs are emitted in [[applyAccumulatedAugmentation]] once the
    * bundle identity is known, matching the Docker pattern where getPurls
    * returns empty and finalAugmentation emits).
    */
  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: IpaMarkers
  ): (PurlSet, IpaState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: IpaMarkers
  ): (TreeMap[String, TreeSet[StringOrPair]], IpaState) = {
    (TreeMap(), this)
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: IpaMarkers,
      parentScope: ParentScope,
      store: Storage
  ): (Item, IpaState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: IpaMarkers
  ): IpaState = {
    // Return `this` (not a fresh IpaState): the accumulator must survive
    // into applyAccumulatedAugmentation (Dotnet pattern).
    this
  }

  /** Generate per-package tag info. Only when the bundle identity has been
    * accumulated (a real `.ipa` container); bare members with no identity
    * yield None.
    */
  override def maybePackageTag(marker: IpaMarkers): Option[PackageTagInfo] =
    (bundleIdentifier, bundleShortVersion) match {
      case (Some(id), v) =>
        Some(
          PackageTagInfo(
            name = id,
            version = v,
            date = None
          )
        )
      case _ => None
    }

  /** Generate the parent scope for the container's children.
    *
    * Overrides accumulateInfo so that every DIRECT child (which includes
    * the `Info.plist` and `embedded.mobileprovision` entries) is offered to
    * IpaState to harvest metadata — the Dotnet analog. The scope accepts
    * only children whose `parentId` equals its own item:
    * `ParentScope.passToParent` also offers every child to the grandparent
    * scope, and without this guard a nested bundle's children would be
    * accumulated onto the outer item.
    */
  override def generateParentScope(
      artifact: ArtifactWrapper,
      item: Item,
      store: Storage,
      marker: IpaMarkers,
      parent: Option[ParentScope],
      augmentationByHash: Map[String, Vector[Augmentation]]
  ): ParentScope =
    new ParentScope(augmentationByHash) {
      def scopeFor(): String = item.identifier
      def parentOfParentScope(): Option[ParentScope] = parent
      def parentScopeInformation(): String =
        s"Ipa Scope for ${item.identifier}${parent match {
            case None     => ""
            case Some(ps) => s" Parent: ${ps.parentScopeInformation()}"
          }}"

      override def accumulateInfo(
          parentId: String,
          item: Item,
          artifact: ArtifactWrapper,
          store: Storage
      ): Unit = {
        if (parentId == scopeFor()) {
          IpaState.this.accumulate(artifact)
        }
      }
    }

  /** Harvest a direct child's content into the accumulation (called via the
    * container's ParentScope for direct children only — the parentId guard
    * in the scope prevents nested bundles from clobbering).
    *
    * Children that contribute:
    *   - `<...>.app/Info.plist` → bundle identity;
    *   - `<...>.app/embedded.mobileprovision` → CMS parse for certs and
    *     the provisioning plist (TeamIdentifier, AppIDName,
    *     ExpirationDate, entitlements, ProvisionedDevices).
    */
  private[strategies] def accumulate(artifact: ArtifactWrapper): Unit = {
    val path = artifact.path()
    val lower = path.toLowerCase
    // Only the APP bundle's own plist/mobileprovision counts: the plist at
    // Payload/<App>.app/Info.plist (its parent component ends in .app). A
    // nested framework's plist
    // (Payload/<App>.app/Frameworks/X.framework/Info.plist) has a parent of
    // .framework, so it must NOT clobber the app's identity.
    val parts = path.split("/").toVector
    val isAppBundleMember =
      parts.length >= 2 && parts(parts.length - 2).endsWith(".app")
    if (isAppBundleMember && lower.endsWith("info.plist")) {
      val bytes = readBounded(artifact)
      BinaryPlistParser.parse(bytes).foreach { jv =>
        bundleIdentifier = stringAt(jv, "CFBundleIdentifier")
        bundleShortVersion = stringAt(jv, "CFBundleShortVersionString")
        bundleVersion = stringAt(jv, "CFBundleVersion")
        bundleName = stringAt(jv, "CFBundleName")
        minimumOS = stringAt(jv, "MinimumOSVersion")
      }
    } else if (isAppBundleMember && lower.endsWith("embedded.mobileprovision")) {
      val bytes = readBounded(artifact)
      parseMobileProvision(bytes)
    }
  }

  private def readBounded(artifact: ArtifactWrapper): Array[Byte] = {
    Try {
      artifact.withStream { s =>
        val buf = new java.io.ByteArrayOutputStream()
        val chunk = new Array[Byte](64 * 1024)
        var total = 0
        var overflow = false
        var n = s.read(chunk)
        while (n >= 0) {
          if (total + n > IpaStrategy.MaxReadBytes) {
            overflow = true
            n = -1
          } else {
            buf.write(chunk, 0, n)
            total += n
            n = s.read(chunk)
          }
        }
        // Refuse to parse a blob that may be truncated: a partial plist/CMS
        // can coincidentally align into a parseable-but-wrong structure.
        if (overflow) Array.emptyByteArray else buf.toByteArray
      }
    }.getOrElse(Array.emptyByteArray)
  }

  /** Parse a mobileprovision blob: CMS SignedData → certs (bounded) and
    * the payload plist (entitlements, team, app id, expiration, devices).
    *
    * Failure is a value: any parse problem leaves the accumulators empty
    * and never throws.
    */
  private def parseMobileProvision(bytes: Array[Byte]): Unit = {
    if (bytes.isEmpty) return
    Try {
      val cms = new CMSSignedData(bytes)
      // Certificate chain, bounded (chain-bomb guard). The chain lives in
      // the CMS SignerInfos; getCertificates() returns the cert store.
      val holders: Vector[X509CertificateHolder] =
        Option(cms.getCertificates())
          .toVector
          .flatMap(_.getMatches(null).asScala)
          .collect { case h: X509CertificateHolder => h }
          .take(IpaStrategy.MaxCertChain)
      val converter =
        new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
          .setProvider("BC")
      certificates = holders.map(h => converter.getCertificate(h))

      // Payload plist: the signed content of the CMS is the plist bytes
      // (embedded.mobileprovision = CMS over the plist).
      val content: Option[Array[Byte]] = Option(cms.getSignedContent())
        .flatMap(sc => Try(sc.getContent).toOption)
        .collect { case b: Array[Byte] => b }

      content.flatMap(BinaryPlistParser.parse).foreach { jv =>
        teamIdentifier = (jv \ "TeamIdentifier") match {
          case JArray(items) =>
            items.collectFirst { case JString(s) => s }
          case JString(s) => Some(s)
          case _          => None
        }
        appIDName = stringAt(jv, "AppIDName")
        provisioningExpiration = stringAt(jv, "ExpirationDate")
        provisionedDeviceCount = (jv \ "ProvisionedDevices") match {
          case JArray(items) => Some(items.length.toString)
          case _             => None
        }
        entitlementsJson = (jv \ "Entitlements") match {
          case o @ JObject(_) => Some(compact(render(o)))
          case _              => None
        }
      }
    }.recover { case e =>
      IpaStrategy.logger.debug(
        f"Ipa mobileprovision parse failed: ${e.getMessage}"
      )
    }
  }

  /** Apply the accumulated metadata onto the container Item via a single
    * store.write (Dotnet pattern). Also emits the signing certs as
    * certificate Items with the Certificates metadata shape (one code path:
    * `Certificates.perCertMetadata`) and the provisional pURL.
    */
  def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): IpaState = {
    val extra: TreeMap[String, TreeSet[StringOrPair]] = TreeMap.from(
      (List(
        bundleIdentifier.map(v =>
          ipaAdHoc("BundleIdentifier") -> TreeSet(StringOrPair(v))
        ),
        bundleShortVersion.map(v =>
          ipaAdHoc("BundleShortVersion") -> TreeSet(StringOrPair(v))
        ),
        bundleVersion.map(v =>
          ipaAdHoc("BundleVersion") -> TreeSet(StringOrPair(v))
        ),
        bundleName.map(v =>
          ipaAdHoc("BundleName") -> TreeSet(StringOrPair(v))
        ),
        minimumOS.map(v => ipaAdHoc("MinimumOS") -> TreeSet(StringOrPair(v))),
        teamIdentifier.map(v =>
          ipaAdHoc("TeamIdentifier") -> TreeSet(StringOrPair(v))
        ),
        appIDName.map(v =>
          ipaAdHoc("AppIDName") -> TreeSet(StringOrPair(v))
        ),
        provisioningExpiration.map(v =>
          ipaAdHoc("ProvisioningExpiration") -> TreeSet(StringOrPair(v))
        ),
        entitlementsJson.map(v =>
          ipaAdHoc("Entitlements") -> TreeSet(StringOrPair(v))
        ),
        provisionedDeviceCount.map(v =>
          ipaAdHoc("ProvisionedDeviceCount") -> TreeSet(StringOrPair(v))
        )
      ).flatten)
    )

    if (extra.nonEmpty) {
      store.write(
        item.identifier,
        {
          case Some(existing) =>
            Some(
              existing.enhanceWithMetadata(
                extra = extra,
                filenames = Vector.empty,
                mimeTypes = Vector.empty
              )
            )
          case None =>
            Some(
              item.enhanceWithMetadata(
                extra = extra,
                filenames = Vector.empty,
                mimeTypes = Vector.empty
              )
            )
        },
        _ => "accumulated augmentation: ipa metadata"
      )
    }

    // Signing certificates as certificate Items (Certificates shape).
    emitCertificates(item, store)

    // Provisional pURL (owner decision 2026-09-25): pkg:apple/ios/<id>@<v>
    emitPurl(item, store)

    this
  }

  /** Emit each signing certificate as a certificate Item with the
    * Certificates metadata shape; each cert is a child (contains) of the
    * .ipa item.
    */
  private def emitCertificates(item: Item, store: Storage): Unit = {
    certificates.foreach { cert =>
      Try {
        val derBytes = cert.getEncoded
        val certGitoid = GitOIDUtils.hashAsHex(
          new ByteArrayInputStream(derBytes),
          derBytes.length.toLong
        )
        val id = s"gitoid:blob:sha256:$certGitoid"
        val certMeta =
          Certificates.perCertMetadata(MetadataKeyConstants.adHoc("Certificates"), cert)
        val certItem = Item(
          id,
          TreeSet(EdgeType.contains -> item.identifier),
          Some(ItemMetaData.mimeType),
          Some(
            ItemMetaData(
              fileNames = TreeSet(),
              mimeType = TreeSet("application/pkcs7-signature"),
              fileSize = derBytes.length,
              extra = certMeta
            )
          )
        )
        store.write(
          id,
          _ => Some(certItem),
          _ => "ipa signing certificate"
        )
      }.recover { case e =>
        IpaStrategy.logger.debug(
          f"Ipa cert emission failed: ${e.getMessage}"
        )
      }
    }
  }

  /** Emit the provisional pURL `pkg:apple/ios/<bundle-id>@<version>` when
    * the bundle id is known (owner decision 2026-09-25; provisional until
    * purl-spec ratifies an app-store type; arch qualifier omitted for now).
    */
  private def emitPurl(item: Item, store: Storage): Unit = {
    bundleIdentifier.flatMap { rawId =>
      PURLComponentSanitizer.sanitizeGenericIdentifier(rawId).flatMap { id =>
        val version = bundleShortVersion.flatMap(
          PURLComponentSanitizer.sanitizeGenericVersion
        )
        Try {
          PURLHelpers
            .purl(
              `type` = "apple",
              name = id,
              namespace = Some("ios"),
              version = version
            )
            .toCanonical()
        }.toOption
      }
    }.foreach { purl =>
      store.addPurl(purl)
      // canonical pURL metadata on the item (Docker pattern)
      store.write(
        item.identifier,
        {
          case Some(existing) =>
            Some(
              existing.enhanceWithMetadata(
                extra = TreeMap(
                  MetadataKeyConstants.CANONICAL_PURL ->
                    TreeSet(StringOrPair(purl))
                ),
                filenames = Vector.empty,
                mimeTypes = Vector.empty
              )
            )
          case None => Some(item)
        },
        _ => "ipa canonical purl"
      )
    }
  }

  private def stringAt(jv: JValue, key: String): Option[String] =
    (jv \ key) match {
      case JString(s) if s.nonEmpty => Some(s)
      case _                        => None
    }
}