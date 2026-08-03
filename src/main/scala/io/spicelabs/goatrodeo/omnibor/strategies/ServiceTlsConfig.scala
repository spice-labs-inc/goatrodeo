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

/** Detects service-level TLS configuration files (e.g. OpenWrt UCI
  * `/etc/config/uhttpd`, nginx, lighttpd) that reference certificates and keys
  * but do not themselves contain PEM data.
  */
object ServiceTlsConfigStrategy {
  private val logger = Logger(this.getClass())

  private val TlsConfigPathPatterns: Seq[String] = Seq(
    "etc/config/",
    "etc/nginx/",
    "etc/lighttpd/",
    "etc/apache2/",
    "etc/httpd/"
  )

  private[strategies] val UciOption =
    "\\s*option\\s+(\\w+)\\s+['\"]([^'\"]+)['\"]".r
  private[strategies] val UciBool =
    "\\s*option\\s+(\\w+)\\s+(\\d+|on|off|true|false)".r
  private[strategies] val NginxSsl = "\\s*ssl_(\\w+)\\s+(.+);".r
  private[strategies] val LighttpdSsl =
    "\\s*ssl\\.(\\w+)\\s*=>\\s*['\"]([^'\"]+)['\"]".r

  /** Compute service TLS configuration files to process at a layer. */
  def computeServiceTlsConfigFiles(
      byUUID: ByUUID,
      byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    val mine = byUUID.values.filter { artifact =>
      isTlsConfigArtifact(artifact)
    }.toVector

    val uuids = mine.map(_.uuid).toSet

    val revisedByUUID = byUUID.filter { case (uuid, _) =>
      !uuids.contains(uuid)
    }
    val revisedByName = byName.filter { case (_, artifacts) =>
      !artifacts.exists(a => uuids.contains(a.uuid))
    }

    (
      mine.map(a => new ServiceTlsConfigToProcess(a)).toVector,
      revisedByUUID,
      revisedByName,
      "ServiceTlsConfig"
    )
  }

  private def isTlsConfigArtifact(artifact: ArtifactWrapper): Boolean = {
    val path = artifact.path()
    if (!TlsConfigPathPatterns.exists(path.contains)) return false
    Try {
      artifact.withStream { stream =>
        val text = new String(
          Helpers.slurpInput(stream),
          StandardCharsets.UTF_8
        )
        containsTlsConfiguration(text)
      }
    }.getOrElse(false)
  }

  private def containsTlsConfiguration(text: String): Boolean = {
    text.linesIterator.exists { line =>
      line.trim match {
        case UciOption("cert", _) | UciOption("key", _) => true
        case UciBool("redirect_https", _)               => true
        case NginxSsl("certificate", _) | NginxSsl("certificate_key", _) =>
          true
        case LighttpdSsl("pemfile", _) | LighttpdSsl("privkey", _) => true
        case _                                                     => false
      }
    }
  }
}

class ServiceTlsConfigToProcess(val artifact: ArtifactWrapper)
    extends ToProcess {
  override def markSuccessfulCompletion(): Unit = artifact.finished()
  override def itemCnt: Int = 1
  override def main: String = artifact.path()
  override def mimeType: Set[String] = artifact.mimeType

  type MarkerType = SingleMarker
  type StateType = ServiceTlsConfigState

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(artifact -> SingleMarker()) -> new ServiceTlsConfigState(artifact)
}

class ServiceTlsConfigState(artifact: ArtifactWrapper)
    extends ProcessingState[SingleMarker, ServiceTlsConfigState] {

  private val adHoc = MKC.adHoc("TLSConfig")

  override def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): ServiceTlsConfigState = this

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (PurlSet, ServiceTlsConfigState) = PurlSet.empty -> this

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], ServiceTlsConfigState) = {
    val meta = parseArtifact(artifact)
    meta -> this
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, ServiceTlsConfigState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): ServiceTlsConfigState = this

  override def applyAccumulatedAugmentation(
      item: Item,
      artifact: ArtifactWrapper,
      store: Storage
  ): ServiceTlsConfigState = this

  private def parseArtifact(
      artifact: ArtifactWrapper
  ): TreeMap[String, TreeSet[StringOrPair]] = {
    val path = artifact.path()
    val fileName = path.split('/').lastOption.getOrElse(path)
    val service = fileName match {
      case "uhttpd"        => "uhttpd"
      case "nginx.conf"    => "nginx"
      case "lighttpd.conf" => "lighttpd"
      case _               => fileName
    }

    val text = artifact.withStream { stream =>
      new String(Helpers.slurpInput(stream), StandardCharsets.UTF_8)
    }

    val isUci = path.contains("/config/") || text.linesIterator.exists(
      _.trim.startsWith("config")
    )

    val (cert, key, redirect) = if (isUci) {
      parseUci(text)
    } else {
      parseGeneric(text)
    }

    var tm = TreeMap[String, TreeSet[StringOrPair]](
      MKC.NAME -> TreeSet(StringOrPair(service)),
      MKC.DESCRIPTION -> TreeSet(StringOrPair("TLS service configuration")),
      adHoc("Service") -> TreeSet(StringOrPair(service)),
      adHoc("FilePath") -> TreeSet(StringOrPair("/" + path))
    )
    cert.foreach(c => tm = tm + (adHoc("CertFile") -> TreeSet(StringOrPair(c))))
    key.foreach(k => tm = tm + (adHoc("KeyFile") -> TreeSet(StringOrPair(k))))
    redirect.foreach(r =>
      tm = tm + (adHoc("RedirectHttps") -> TreeSet(StringOrPair(r)))
    )
    tm
  }

  private def parseUci(
      text: String
  ): (Option[String], Option[String], Option[String]) = {
    var cert: Option[String] = None
    var key: Option[String] = None
    var redirect: Option[String] = None
    text.linesIterator.foreach { line =>
      line.trim match {
        case ServiceTlsConfigStrategy.UciOption("cert", value) =>
          cert = Option(value).map(_.trim)
        case ServiceTlsConfigStrategy.UciOption("key", value) =>
          key = Option(value).map(_.trim)
        case ServiceTlsConfigStrategy.UciBool("redirect_https", value) =>
          redirect = Option(value).map(_.trim)
        case _ =>
      }
    }
    (cert, key, redirect)
  }

  private def parseGeneric(
      text: String
  ): (Option[String], Option[String], Option[String]) = {
    var cert: Option[String] = None
    var key: Option[String] = None
    var redirect: Option[String] = None
    text.linesIterator.foreach { line =>
      line.trim match {
        case ServiceTlsConfigStrategy.NginxSsl("certificate", value) =>
          cert = Option(value).map(_.trim.replaceAll("['\";]", ""))
        case ServiceTlsConfigStrategy.NginxSsl("certificate_key", value) =>
          key = Option(value).map(_.trim.replaceAll("['\";]", ""))
        case ServiceTlsConfigStrategy.LighttpdSsl("pemfile", value) =>
          cert = Option(value).map(_.trim)
        case ServiceTlsConfigStrategy.LighttpdSsl("privkey", value) =>
          key = Option(value).map(_.trim)
        case _ =>
      }
    }
    (cert, key, redirect)
  }
}
