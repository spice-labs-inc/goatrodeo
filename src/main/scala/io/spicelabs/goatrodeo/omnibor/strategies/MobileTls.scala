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

/** Detects mobile / JVM TLS policy configuration files:
  *
  * - Android `network_security_config.xml` (cleartext permission, custom trust
  *   anchors, trust-on-first-use).
  * - Android `AndroidManifest.xml` (`android:usesCleartextTraffic`).
  * - Apple `Info.plist` ATS (`NSAppTransportSecurity` / `NSAllowsArbitraryLoads`
  *   / `NSExceptionDomains`).
  * - JDK `crypto.policy` (`crypto.policy=unlimited`).
  *
  * Emits `MobileTls:` flags and `java.security:crypto_policy`. Policy files
  * carry no secrets; T-F-10 asserts no private-key/base64 material.
  */
object MobileTlsStrategy {
  private val logger = Logger(getClass())

  val MaxReadBytes: Int = 1024 * 1024

  private[strategies] def detectTlsPolicyArtifact(path: String): Option[String] = {
    val fileName = path.split('/').lastOption.getOrElse(path)
    if (fileName == "network_security_config.xml") Some("android-network-security-config")
    else if (fileName == "AndroidManifest.xml") Some("android-manifest")
    else if (fileName == "Info.plist") Some("apple-ats")
    else if (fileName == "crypto.policy") Some("jvm-crypto-policy")
    else None
  }

  def computeMobileTlsFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values.filter(a =>
      detectTlsPolicyArtifact(a.path()).isDefined
    ).toVector

    val uuids = mine.map(_.uuid).toSet
    (mine.map(a => new MobileTlsToProcess(a)).toVector,
      byUUID.filter { case (u, _) => !uuids.contains(u) },
      byName.filter { case (_, as) => !as.exists(a => uuids.contains(a.uuid)) },
      "MobileTls")
  }

  private[strategies] def contentOf(a: ArtifactWrapper): String = {
    val bytes = a.withStream { s =>
      val buf = new Array[Byte](MaxReadBytes)
      val n = s.read(buf, 0, MaxReadBytes)
      if (n <= 0) Array.emptyByteArray else java.util.Arrays.copyOf(buf, n)
    }
    new String(bytes, StandardCharsets.ISO_8859_1)
  }
}

class MobileTlsToProcess(val artifact: ArtifactWrapper) extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = MobileTlsState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new MobileTlsState(artifact)
}

class MobileTlsState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, MobileTlsState] {

  private val mtAdHoc = MKC.adHoc("MobileTls")
  private val jsAdHoc = MKC.adHoc("java.security")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): MobileTlsState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, MobileTlsState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], MobileTlsState) = {
    val ret = buildMetadata(artifact)
    ret -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, MobileTlsState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): MobileTlsState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): MobileTlsState = this

  private def parseNetworkSecurity(text: String): Option[TreeMap[String, TreeSet[StringOrPair]]] = {
    if (!text.contains("<network-security-config")) None
    else {
      var tm = TreeMap[String, TreeSet[StringOrPair]](
        mtAdHoc("FileType") -> TreeSet(StringOrPair("network-security-config"))
      )
      val clear = """cleartextTrafficPermitted\s*=\s*"(true|false)"""".r
      clear.findFirstMatchIn(text).foreach { m =>
        tm = tm + (mtAdHoc("cleartext_allowed") -> TreeSet(StringOrPair(Option(m.group(1)).getOrElse(""))))
      }
      if (text.contains("<trust-anchors")) {
        tm = tm + (mtAdHoc("custom_ca") -> TreeSet(StringOrPair("true")))
      }
      if (text.contains("trust-on-first-use")) {
        tm = tm + (mtAdHoc("trust_on_first_use") -> TreeSet(StringOrPair("true")))
      }
      Some(tm)
    }
  }

  private def parseManifest(text: String): Option[TreeMap[String, TreeSet[StringOrPair]]] = {
    if (!text.contains("<manifest")) None
    else {
      var tm = TreeMap[String, TreeSet[StringOrPair]](
        mtAdHoc("FileType") -> TreeSet(StringOrPair("android-manifest"))
      )
      val clear = """android:usesCleartextTraffic\s*=\s*"(true|false)"""".r
      clear.findFirstMatchIn(text).foreach { m =>
        tm = tm + (mtAdHoc("manifest_cleartext") -> TreeSet(StringOrPair(Option(m.group(1)).getOrElse(""))))
      }
      Some(tm)
    }
  }

  private def parseInfoPlist(text: String): Option[TreeMap[String, TreeSet[StringOrPair]]] = {
    if (!text.contains("NSAppTransportSecurity")) None
    else {
      var tm = TreeMap[String, TreeSet[StringOrPair]](
        mtAdHoc("FileType") -> TreeSet(StringOrPair("apple-ats"))
      )
      if (text.contains("NSAllowsArbitraryLoads")) {
        tm = tm + (mtAdHoc("ats_arbitrary_loads") -> TreeSet(StringOrPair("true")))
      }
      if (text.contains("NSExceptionDomains")) {
        tm = tm + (mtAdHoc("ats_exceptions") -> TreeSet(StringOrPair("true")))
      }
      if (text.contains("NSAllowsLocalNetworking")) {
        tm = tm + (mtAdHoc("ats_local_networking") -> TreeSet(StringOrPair("true")))
      }
      Some(tm)
    }
  }

  private def parseCryptoPolicy(text: String): Option[TreeMap[String, TreeSet[StringOrPair]]] = {
    val policy = "^\\s*crypto\\.policy\\s*=\\s*(.+)\\s*$".r
    text.linesIterator
      .collectFirst { case policy(v) => Option(v).getOrElse("").trim }
      .map(v =>
        TreeMap[String, TreeSet[StringOrPair]](
          jsAdHoc("crypto_policy") -> TreeSet(StringOrPair(v))
        )
      )
  }

  private def buildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val path = artifact.path()
    val kind = MobileTlsStrategy.detectTlsPolicyArtifact(path)
    val text = Try(MobileTlsStrategy.contentOf(artifact)).getOrElse("")
    kind match {
      case Some("android-network-security-config") => parseNetworkSecurity(text).getOrElse(TreeMap.empty)
      case Some("android-manifest")                => parseManifest(text).getOrElse(TreeMap.empty)
      case Some("apple-ats")                       => parseInfoPlist(text).getOrElse(TreeMap.empty)
      case Some("jvm-crypto-policy")               => parseCryptoPolicy(text).getOrElse(TreeMap.empty)
      case _                                       => TreeMap.empty
    }
  }

  /** Test-accessible alias for buildMetadata. */
  private[strategies] def invokeBuildMetadata(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = buildMetadata(artifact)
}